package com.honeypot.app.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * On-device scam classification using the fine-tuned MuRIL BERT ONNX model.
 *
 * Model: scam_classifier.onnx (binary, Label 0 = HAM, Label 1 = SCAM)
 * Input tensors (each shape [1, 128], INT64):
 *   - input_ids
 *   - attention_mask
 *   - token_type_ids  (all zeros for single-sentence classification)
 * Output: logits [1, 2] → softmax → [ham_prob, scam_prob]
 */
class OnnxScamClassifier(private val context: Context) {

    private var session: OrtSession? = null
    private lateinit var tokenizer: BertWordPieceTokenizer
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    companion object {
        private const val TAG = "OnnxScamClassifier"
        private const val MODEL_ASSET = "scam_classifier.onnx"
    }

    data class ClassificationResult(
        val isScam: Boolean,
        val scamProbability: Float,
        val hamProbability: Float
    ) {
        /** Calibrated softmax confidence (always ≥ 0.5 for the winning class). */
        val confidence: Float get() = if (isScam) scamProbability else hamProbability
    }

    val isInitialized: Boolean get() = session != null

    /** Checks whether the ONNX model and tokenizer are present either on disk or in assets. */
    fun isModelAvailable(): Boolean {
        val resolved = com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, "scam_classifier_int8.onnx")
            ?: com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, MODEL_ASSET)
        if (resolved != null && resolved.exists() && resolved.length() > 0) return true

        return try {
            context.assets.open("muril/scam_classifier_int8.onnx").close()
            true
        } catch (_: Exception) {
            try {
                context.assets.open(MODEL_ASSET).close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Loads vocab + ONNX model. Must be called once before [classify].
     * Model is cached to internal storage so subsequent starts are faster.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (session != null) return@withContext

        tokenizer = BertWordPieceTokenizer(context)
        tokenizer.loadVocab()

        val modelFile = ensureModelFile()
            ?: throw IllegalStateException("MuRIL ONNX model not found on device or assets. Download via Settings.")

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        Log.d(TAG, "Creating ONNX session from: ${modelFile.absolutePath}")
        val t0 = System.currentTimeMillis()
        session = env.createSession(modelFile.absolutePath, opts)
        Log.d(TAG, "ONNX session ready in ${System.currentTimeMillis() - t0} ms. " +
                   "Inputs=${session!!.inputNames}  Outputs=${session!!.outputNames}")
    }

    /**
     * Classifies a raw SMS body.
     * Returns [ClassificationResult] with calibrated softmax probabilities.
     */
    suspend fun classify(text: String): ClassificationResult = withContext(Dispatchers.Default) {
        val s = session ?: error("OnnxScamClassifier not initialized")
        val tokenized = tokenizer.tokenize(text)
        val seqLen = BertWordPieceTokenizer.MAX_SEQ_LEN.toLong()
        val shape = longArrayOf(1L, seqLen)

        // ONNX Runtime Android requires LongBuffer (not Array<LongArray>) for INT64 tensors
        val inputIdsTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.inputIds),      shape)
        val attnMaskTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.attentionMask), shape)
        val tokenTypeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenized.tokenTypeIds),  shape)

        try {
            // Input names MUST match the ONNX graph — logged above on first run
            val inputs = mapOf(
                "input_ids"       to inputIdsTensor,
                "attention_mask"  to attnMaskTensor,
                "token_type_ids"  to tokenTypeTensor
            )

            s.run(inputs).use { result ->
                val logits = (result[0].value as Array<FloatArray>)[0]   // shape [2]
                val probs  = softmax(logits)
                Log.d(TAG, "logits=[${logits[0]}, ${logits[1]}]  probs=[ham=${probs[0]}, scam=${probs[1]}]")
                ClassificationResult(
                    isScam           = probs[1] > probs[0],
                    hamProbability   = probs[0],
                    scamProbability  = probs[1]
                )
            }
        } finally {
            inputIdsTensor.close()
            attnMaskTensor.close()
            tokenTypeTensor.close()
        }
    }

    fun close() {
        session?.close()
        session = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Resolves the ONNX model file. If downloaded into context.filesDir/models/,
     * returns it directly (zero copying). If bundled in assets, copies it to internal storage.
     */
    private fun ensureModelFile(): File? {
        // 1. Check if model is already downloaded to filesDir/models/
        val downloaded = com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, "scam_classifier_int8.onnx")
            ?: com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, MODEL_ASSET)
        if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
            Log.d(TAG, "Using downloaded ONNX model from: ${downloaded.absolutePath}")
            return downloaded
        }

        // 2. Check direct internal storage
        val dest = File(context.filesDir, MODEL_ASSET)
        if (dest.exists() && dest.length() > 0) return dest

        // 3. Fallback: Copy from assets if bundled during development
        val assetSource = try {
            context.assets.open("muril/scam_classifier_int8.onnx").close()
            "muril/scam_classifier_int8.onnx"
        } catch (_: Exception) {
            try {
                context.assets.open(MODEL_ASSET).close()
                MODEL_ASSET
            } catch (_: Exception) {
                null
            }
        } ?: return null

        Log.d(TAG, "Copying ONNX model from assets ($assetSource) to internal storage…")
        val tmp = File(context.filesDir, "$MODEL_ASSET.tmp")
        context.assets.open(assetSource).use { ins ->
            FileOutputStream(tmp).use { out -> ins.copyTo(out) }
        }
        tmp.renameTo(dest)
        Log.d(TAG, "Model copy done → ${dest.absolutePath}")
        return dest
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = logits.map { exp((it - maxLogit).toDouble()).toFloat() }
        val sum  = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }
}

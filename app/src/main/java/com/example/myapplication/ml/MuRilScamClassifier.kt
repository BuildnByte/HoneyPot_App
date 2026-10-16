package com.example.myapplication.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * Result returned by the MuRIL ONNX binary scam classifier.
 *
 * @property isScam True if scam probability >= 0.5.
 * @property scamProbability Model confidence for class 1 (SCAM/SPAM), range [0.0, 1.0].
 * @property hamProbability Model confidence for class 0 (HAM/SAFE), range [0.0, 1.0].
 * @property confidence Confidence of the winning class, max(scamProbability, hamProbability).
 */
data class MuRilResult(
    val isScam: Boolean,
    val scamProbability: Float,
    val hamProbability: Float,
    val confidence: Float
)

/**
 * On-device MuRIL (BERT-based) ONNX binary scam classifier.
 *
 * Model: scam_classifier_int8.onnx
 * Inputs:
 *   - input_ids: [1, 128] (int64)
 *   - attention_mask: [1, 128] (int64)
 *   - token_type_ids: [1, 128] (int64)
 * Output:
 *   - logits: [1, 2] (float) -> [HAM/SAFE, SCAM/SPAM]
 */
class MuRilScamClassifier(private val context: Context) : AutoCloseable {

    private val tokenizer = MuRilTokenizer(context)
    private val initMutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    companion object {
        private const val TAG = "MuRilScamClassifier"
        const val MODEL_ASSET_PATH = "muril/scam_classifier_int8.onnx"
        const val MODEL_FILE_NAME = "scam_classifier_int8.onnx"
        private const val SUBDIR = "muril"
        private const val SEQUENCE_LENGTH = 128L
    }

    /**
     * Initializes the tokenizer and ONNX Runtime session asynchronously.
     * Copies the model asset to [context.filesDir]/muril/ if not already present or corrupted.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (ortSession != null) return@withContext

        initMutex.withLock {
            if (ortSession != null) return@withLock

            // 1. Initialize tokenizer vocabulary
            tokenizer.initialize()

            // 2. Ensure model file exists on disk
            val modelDir = File(context.filesDir, SUBDIR).apply { mkdirs() }
            val modelFile = File(modelDir, MODEL_FILE_NAME)

            val assetSize = getAssetSize(MODEL_ASSET_PATH)
            val needsCopy = !modelFile.exists() || (assetSize > 0 && modelFile.length() != assetSize)

            if (needsCopy) {
                val freeSpace = context.filesDir.usableSpace
                if (assetSize > 0 && assetSize > freeSpace) {
                    val errorMsg = "Insufficient disk space for MuRIL model. Need ${assetSize / (1024 * 1024)}MB, available: ${freeSpace / (1024 * 1024)}MB."
                    Log.e(TAG, errorMsg)
                    throw IllegalStateException(errorMsg)
                }

                Log.d(TAG, "Copying model asset '$MODEL_ASSET_PATH' to '${modelFile.absolutePath}'...")
                modelFile.delete()
                copyAssetToFile(MODEL_ASSET_PATH, modelFile)
                Log.d(TAG, "Model copied successfully. Size: ${modelFile.length()} bytes")
            }

            // 3. Initialize ONNX runtime environment and session
            try {
                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    setIntraOpNumThreads(2)
                }

                Log.d(TAG, "Creating OrtSession from ${modelFile.absolutePath}...")
                ortSession = env.createSession(modelFile.absolutePath, sessionOptions)
                Log.d(TAG, "MuRilScamClassifier initialized successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create OrtSession for MuRIL model", e)
                throw IllegalStateException("Failed to initialize ONNX Runtime session: ${e.message}", e)
            }
        }
    }

    private fun getAssetSize(assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            -1L // Asset may be compressed or unseekable
        }
    }

    private fun copyAssetToFile(assetPath: String, destinationFile: File) {
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (tempFile.exists()) {
                if (destinationFile.exists()) destinationFile.delete()
                if (!tempFile.renameTo(destinationFile)) {
                    throw IllegalStateException("Failed to rename temporary model file to ${destinationFile.name}")
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Classifies the input text using the MuRIL ONNX binary classifier.
     * Executes off the main thread on [Dispatchers.Default].
     *
     * @param text Raw incoming SMS / notification message text.
     * @return [MuRilResult] containing classification flag, probabilities, and confidence.
     */
    suspend fun classify(text: String): MuRilResult = withContext(Dispatchers.Default) {
        var session = ortSession
        var env = ortEnv

        if (session == null || env == null) {
            Log.d(TAG, "OrtSession not initialized, initializing now...")
            initialize()
            session = ortSession ?: throw IllegalStateException("OrtSession could not be initialized")
            env = ortEnv ?: throw IllegalStateException("OrtEnvironment could not be initialized")
        }

        // 1. Tokenize input text to sequence length 128
        val tokenized = tokenizer.tokenize(text, maxLength = SEQUENCE_LENGTH.toInt())

        // 2. Prepare tensors with shape [1, 128]
        val shape = longArrayOf(1L, SEQUENCE_LENGTH)
        val inputIdsBuffer = LongBuffer.wrap(tokenized.inputIds)
        val attentionMaskBuffer = LongBuffer.wrap(tokenized.attentionMask)
        val tokenTypeIdsBuffer = LongBuffer.wrap(tokenized.tokenTypeIds)

        val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, shape)
        val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIdsBuffer, shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )

        var sessionResult: OrtSession.Result? = null
        try {
            // 3. Run inference
            sessionResult = session.run(inputs)

            // 4. Extract logits (output tensor shape: [1, 2])
            val outputTensor = sessionResult.get(0) as? OnnxTensor
                ?: throw IllegalStateException("Output tensor at index 0 is not an OnnxTensor")

            val logits = extractLogits(outputTensor)
            if (logits.size < 2) {
                throw IllegalStateException("Expected at least 2 logits, got ${logits.size}")
            }

            // 5. Compute numerically stable softmax
            val hamLogit = logits[0]
            val scamLogit = logits[1]
            val maxLogit = maxOf(hamLogit, scamLogit)

            val expHam = exp((hamLogit - maxLogit).toDouble()).toFloat()
            val expScam = exp((scamLogit - maxLogit).toDouble()).toFloat()
            val sumExp = expHam + expScam

            val hamProbability = (expHam / sumExp).coerceIn(0.0f, 1.0f)
            val scamProbability = (expScam / sumExp).coerceIn(0.0f, 1.0f)
            val isScam = scamProbability >= 0.5f
            val confidence = if (isScam) scamProbability else hamProbability

            Log.d(
                TAG,
                "Classified text: isScam=$isScam, scamProb=$scamProbability, hamProb=$hamProbability, confidence=$confidence"
            )

            MuRilResult(
                isScam = isScam,
                scamProbability = scamProbability,
                hamProbability = hamProbability,
                confidence = confidence
            )
        } finally {
            // Clean up native tensors and result container
            runCatching { inputIdsTensor.close() }
            runCatching { attentionMaskTensor.close() }
            runCatching { tokenTypeIdsTensor.close() }
            runCatching { sessionResult?.close() }
        }
    }

    private fun extractLogits(tensor: OnnxTensor): FloatArray {
        return when (val rawValue = tensor.value) {
            is Array<*> -> {
                when (val first = rawValue.firstOrNull()) {
                    is FloatArray -> first
                    is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        (first as Array<Float>).toFloatArray()
                    }
                    else -> throw IllegalStateException("Unexpected 2D tensor element type: ${first?.javaClass?.name}")
                }
            }
            is FloatBuffer -> {
                val buffer = rawValue.asReadOnlyBuffer()
                val array = FloatArray(buffer.remaining())
                buffer.get(array)
                array
            }
            is FloatArray -> rawValue
            else -> throw IllegalStateException("Unsupported OnnxTensor value type: ${rawValue?.javaClass?.name}")
        }
    }

    override fun close() {
        try {
            ortSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing OrtSession", e)
        } finally {
            ortSession = null
        }

        try {
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing OrtEnvironment", e)
        } finally {
            ortEnv = null
        }
    }
}

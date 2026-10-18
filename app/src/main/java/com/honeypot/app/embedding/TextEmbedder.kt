package com.honeypot.app.embedding

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScamTextEmbedder(private val context: Context) {

    private var embedder: TextEmbedder? = null
    private val MODEL_PATH = "universal_sentence_encoder_lite.tflite"

    val isInitialized: Boolean get() = embedder != null

    fun isModelAvailable(): Boolean {
        val downloaded = com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, MODEL_PATH)
        if (downloaded != null && downloaded.exists() && downloaded.length() > 0) return true
        return try {
            context.assets.open(MODEL_PATH).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (embedder != null) return@withContext

        val downloaded = com.honeypot.app.download.ModelDownloadManager.resolveModelFile(context, MODEL_PATH)
        val modelPathToUse: String = if (downloaded != null && downloaded.exists() && downloaded.length() > 0) {
            Log.d("ScamTextEmbedder", "Loading TextEmbedder model from file: ${downloaded.absolutePath}")
            downloaded.absolutePath
        } else {
            try {
                context.assets.open(MODEL_PATH).close()
                Log.d("ScamTextEmbedder", "Loading TextEmbedder model from assets: $MODEL_PATH")
                MODEL_PATH
            } catch (e: Exception) {
                Log.e("ScamTextEmbedder", "Model asset not found: $MODEL_PATH. Vector search disabled.")
                throw IllegalStateException("USE Lite model missing. Please download it via Settings.")
            }
        }

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelPathToUse)
            .setDelegate(Delegate.CPU)
            .build()

        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .setQuantize(false)
            .build()

        embedder = TextEmbedder.createFromOptions(context, options)
    }

    fun embed(text: String): FloatArray {
        val currentEmbedder = embedder ?: throw IllegalStateException("TextEmbedder not initialized")
        val result = currentEmbedder.embed(text)
        val embedding = result.embeddingResult().embeddings()[0]
        return embedding.floatEmbedding()
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vector dimensions must match" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun close() {
        embedder?.close()
        embedder = null
    }
}

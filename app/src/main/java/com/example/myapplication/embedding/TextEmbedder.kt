package com.example.myapplication.embedding

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder

class ScamTextEmbedder(context: Context) {

    private val embedder: TextEmbedder

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("universal_sentence_encoder_lite.tflite")
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
        try {
            // Try newer API first
            val inputClass = Class.forName(
                "com.google.mediapipe.tasks.text.textembedder.TextEmbedder\$TextEmbedderInput"
            )
            val createMethod = inputClass.getMethod("create", String::class.java)
            val input = createMethod.invoke(null, text)
            val result = embedder.javaClass.getMethod("embed", inputClass).invoke(embedder, input)
            val embeddingResult = result!!.javaClass.getMethod("embeddingResult").invoke(result)
            val embeddings = embeddingResult!!.javaClass.getMethod("embeddings").invoke(embeddingResult) as List<*>
            val embedding = embeddings[0]!!
            @Suppress("UNCHECKED_CAST")
            val floatList = embedding.javaClass.getMethod("floatEmbedding").invoke(embedding) as List<Float>
            return floatList.toFloatArray()
        } catch (e: Exception) {
            throw RuntimeException("TextEmbedder.embed() failed: ${e.message}", e)
        }
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
        embedder.close()
    }
}
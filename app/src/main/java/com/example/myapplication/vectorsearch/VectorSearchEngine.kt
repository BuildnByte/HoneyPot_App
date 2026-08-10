package com.example.myapplication.vectorsearch

import com.example.myapplication.embedding.ScamTextEmbedder
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.knowledge.KnowledgeBaseEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VectorSearchEngine(
    private val db: KnowledgeBaseDatabase,
    private val embedder: ScamTextEmbedder
) {

    data class SearchResult(
        val entry: KnowledgeBaseEntry,
        val similarity: Float
    )

    suspend fun search(
        queryText: String,
        topK: Int = 3,
        minSimilarity: Float = 0.50f,
        filterByTypes: List<String>? = null
    ): List<SearchResult> = withContext(Dispatchers.Default) {

        val queryVector = embedder.embed(queryText)

        val candidates: List<KnowledgeBaseEntry> = if (filterByTypes != null) {
            filterByTypes.flatMap { db.knowledgeBaseDao().getByType(it) }
        } else {
            db.knowledgeBaseDao().getAll()
        }

        candidates
            .filter { entry -> entry.embeddingBlob.isNotEmpty() }
            .map { entry ->
                val storedVector = byteArrayToFloatArray(entry.embeddingBlob)
                val similarity = embedder.cosineSimilarity(queryVector, storedVector)
                SearchResult(entry, similarity)
            }
            .filter { result -> result.similarity >= minSimilarity }
            .sortedByDescending { result -> result.similarity }
            .take(topK)
    }

    suspend fun precomputeEmbeddings() = withContext(Dispatchers.Default) {
        val entries: List<KnowledgeBaseEntry> = db.knowledgeBaseDao().getAll()
        val toUpdate = entries
            .filter { entry -> entry.embeddingBlob.isEmpty() }
            .map { entry ->
                val vector = embedder.embed(entry.exampleTriggerText)
                entry.copy(embeddingBlob = floatArrayToByteArray(vector))
            }
        if (toUpdate.isNotEmpty()) {
            db.knowledgeBaseDao().insertAll(toUpdate)
        }
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(floats.size * 4)
        buffer.asFloatBuffer().put(floats)
        return buffer.array()
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return FloatArray(bytes.size / 4) { buffer.float }
    }
}
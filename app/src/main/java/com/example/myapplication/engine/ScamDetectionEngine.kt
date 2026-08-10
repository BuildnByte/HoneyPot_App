package com.example.myapplication.engine

import android.content.Context
import com.example.myapplication.embedding.ScamTextEmbedder
import com.example.myapplication.filter.FilterResult
import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.llm.ScamLlmClassifier
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.ScamResult
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.trigger.TriggerEngine
import com.example.myapplication.vectorsearch.VectorSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScamDetectionEngine(context: Context) {

    private val db = KnowledgeBaseDatabase.getInstance(context)
    private val embedder = ScamTextEmbedder(context)
    private val vectorSearch = VectorSearchEngine(db, embedder)
    private val llmClassifier = ScamLlmClassifier(context)

    data class DetectionResult(
        val sms: IncomingSms,
        val filterResult: FilterResult,
        val triggerResult: TriggerEngine.TriggerResult?,
        val kbResults: List<VectorSearchEngine.SearchResult>,
        val scamResult: ScamResult?,
        val conversationText: String
    )

    suspend fun initialize() {
        embedder.initialize()
        llmClassifier.initialize()
    }

    suspend fun processMessage(
        sms: IncomingSms,
        history: List<String>
    ): DetectionResult {
        // --- STAGE 1: Pre-filter ---
        val filterResult = SmsPreFilter.shouldIgnore(sms.sender, sms.body)
        if (filterResult == FilterResult.IGNORE) {
            return DetectionResult(sms, filterResult, null, emptyList(), null, "")
        }

        // --- STAGE 2: Preprocess ---
        val preprocessed = withContext(Dispatchers.Default) {
            SmsPreprocessor.process(sms)
        }

        // --- STAGE 3: Track conversation ---
        val fullHistory = history + "[${sms.sender}]: ${sms.body}"
        val conversationText = fullHistory.joinToString("\n")

        // --- STAGE 4: Trigger check ---
        val triggerResult = TriggerEngine.evaluate(preprocessed, history)
        if (!triggerResult.shouldProcess) {
            return DetectionResult(sms, filterResult, triggerResult, emptyList(), null, conversationText)
        }

        // --- STAGE 5: Embed + Vector Search ---
        val kbResults = withContext(Dispatchers.Default) {
            vectorSearch.search(
                queryText = conversationText,
                topK = 3,
                minSimilarity = 0.45f
            )
        }

        // --- STAGE 6: LLM Classification ---
        if (kbResults.isEmpty() && triggerResult.priority == TriggerEngine.Priority.LOW) {
            return DetectionResult(sms, filterResult, triggerResult, kbResults, null, conversationText)
        }

        val scamResult = withContext(Dispatchers.Default) {
            llmClassifier.classify(
                conversationText = conversationText,
                retrievedKbEntries = kbResults,
                firedTriggers = triggerResult.firedTriggers
            )
        }

        return DetectionResult(sms, filterResult, triggerResult, kbResults, scamResult, conversationText)
    }

    fun close() {
        llmClassifier.close()
        embedder.close()
    }
}

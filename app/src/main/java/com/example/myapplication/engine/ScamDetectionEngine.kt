package com.example.myapplication.engine

import android.content.Context
import com.example.myapplication.embedding.ScamTextEmbedder
import com.example.myapplication.filter.FilterResult
import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.llm.ScamLlmClassifier
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.ScamResult
import com.example.myapplication.model.ScamEvidence
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.trigger.TriggerEngine
import com.example.myapplication.vectorsearch.VectorSearchEngine
import com.example.myapplication.verification.FactCheckService
import com.example.myapplication.llm.ReasoningContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScamDetectionEngine(context: Context) {

    private val db = KnowledgeBaseDatabase.getInstance(context)
    private val embedder = ScamTextEmbedder(context)
    private val vectorSearch = VectorSearchEngine(db, embedder)
    private val llmClassifier = ScamLlmClassifier(context)
    private val factCheckService = FactCheckService(context)

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

        val factCheckResults = factCheckService.verifyEntities(preprocessed.extractedEntities)

        val reasoningContext = ReasoningContext(
            conversationText = conversationText,
            firedTriggers = triggerResult.firedTriggers,
            triggerPriority = triggerResult.priority.name,
            patternMatches = kbResults.map { it.entry.patternDescription to it.similarity },
            factCheckResults = factCheckResults,
            extractedEntities = preprocessed.extractedEntities
        )

        val scamResult = withContext(Dispatchers.Default) {
            llmClassifier.classify(reasoningContext)
        }

        // --- STAGE 7: Evidence Extraction ---
        if (scamResult.isScam) {
            withContext(Dispatchers.IO) {
                val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sms.timestamp))
                val sessionId = "${sms.sender}_$datePart"
                val evidenceList = mutableListOf<ScamEvidence>()

                val entities = preprocessed.extractedEntities

                entities.upiIds.filter { it.isNotBlank() }.forEach {
                    evidenceList.add(ScamEvidence(
                        sessionId = sessionId, sourceSender = sms.sender, entityType = "UPI_ID",
                        entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp
                    ))
                }
                entities.phoneNumbers.filter { it.isNotBlank() }.forEach {
                    evidenceList.add(ScamEvidence(
                        sessionId = sessionId, sourceSender = sms.sender, entityType = "PHONE",
                        entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp
                    ))
                }
                entities.accountNumbers.filter { it.isNotBlank() }.forEach {
                    evidenceList.add(ScamEvidence(
                        sessionId = sessionId, sourceSender = sms.sender, entityType = "ACCOUNT",
                        entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp
                    ))
                }
                entities.urls.filter { it.isNotBlank() }.forEach {
                    evidenceList.add(ScamEvidence(
                        sessionId = sessionId, sourceSender = sms.sender, entityType = "URL",
                        entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp
                    ))
                }
                entities.amounts.filter { it.isNotBlank() }.forEach {
                    evidenceList.add(ScamEvidence(
                        sessionId = sessionId, sourceSender = sms.sender, entityType = "AMOUNT",
                        entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp
                    ))
                }

                if (evidenceList.isNotEmpty()) {
                    db.knowledgeBaseDao().insertEvidenceBatch(evidenceList)
                }
            }
        }

        return DetectionResult(sms, filterResult, triggerResult, kbResults, scamResult, conversationText)
    }

    fun close() {
        llmClassifier.close()
        embedder.close()
    }
}

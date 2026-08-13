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
        val conversationText: String,
        val suggestedReply: String = "",      // Populated only when scamResult?.isScam == true
        val conversationHistory: List<String> = emptyList(), // Raw history lines for the reply generator
    )

    suspend fun initialize() {
        populateKnowledgeBaseIfNeeded()
        embedder.initialize()
        vectorSearch.precomputeEmbeddings()
        llmClassifier.initialize()
    }

    private suspend fun populateKnowledgeBaseIfNeeded() = withContext(Dispatchers.IO) {
        if (db.knowledgeBaseDao().count() == 0) {
            android.util.Log.d("ScamDetectionEngine", "Populating initial knowledge base...")
            val initialEntries = listOf(
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "KYC Phishing",
                    patternDescription = "Urgent request to update KYC via suspicious link to avoid account blocking.",
                    exampleTriggerText = "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-bank.com",
                    responseStrategy = "Warn user and suggest calling bank official number.",
                    severity = 3
                ),
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "Lottery Scam",
                    patternDescription = "Message claiming you won a large sum of money and asking for personal details.",
                    exampleTriggerText = "Congratulations! You won 1 Crore in KBC. Call 9988776655 to claim your prize.",
                    responseStrategy = "Inform user it's a common fraud. Do not call or share details.",
                    severity = 4
                ),
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "Electricity Bill Scam",
                    patternDescription = "Threatening to disconnect electricity due to unpaid bill, providing a phone number to call.",
                    exampleTriggerText = "Dear consumer, your electricity will be disconnected tonight at 9:30 PM because your previous month bill was not updated. Please contact our officer 8877665544. Thank you.",
                    responseStrategy = "Suggest checking with official utility provider website.",
                    severity = 3
                )
            )
            db.knowledgeBaseDao().insertAll(initialEntries)
        }
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
        if (kbResults.isEmpty() && (triggerResult.priority == TriggerEngine.Priority.LOW)) {
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

        // --- STAGE 8: Honeypot Reply Suggestion ---
        // Only generated when scam is confirmed — skipped for safe/low-risk messages.
        val suggestedReply = if (scamResult.isScam) {
            withContext(Dispatchers.Default) {
                llmClassifier.generateReplyHint(
                    conversationHistory = fullHistory,
                    scamType = scamResult.scamType
                )
            }
        } else ""

        return DetectionResult(
            sms = sms,
            filterResult = filterResult,
            triggerResult = triggerResult,
            kbResults = kbResults,
            scamResult = scamResult,
            conversationText = conversationText,
            suggestedReply = suggestedReply,
            conversationHistory = fullHistory
        )
    }

    /**
     * Fast-path for attacker messages in a session that has already been confirmed as a scam.
     *
     * Skips: pre-filter, trigger engine, vector search, LLM classification.
     * Runs:  entity extraction → evidence storage → honeypot reply generation.
     *
     * @param sms             The new incoming message to process.
     * @param history         All prior "[Sender]: message" lines in this session.
     * @param originalScamType The scamType from the session's first confirmed scam detection.
     * @param originalConfidence The confidence score from the first confirmed detection.
     */
    suspend fun processConfirmedScamFollowUp(
        sms: IncomingSms,
        history: List<String>,
        originalScamType: String,
        originalConfidence: Float
    ): DetectionResult {
        // Build full conversation including this new message
        val fullHistory = history + "[${sms.sender}]: ${sms.body}"
        val conversationText = fullHistory.joinToString("\n")

        // Extract entities from the new message for evidence logging
        val preprocessed = withContext(Dispatchers.Default) {
            SmsPreprocessor.process(sms)
        }

        // Persist any new evidence entities revealed by the attacker in this follow-up
        withContext(Dispatchers.IO) {
            val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sms.timestamp))
            val sessionId = "${sms.sender}_$datePart"
            val evidenceList = mutableListOf<ScamEvidence>()
            val entities = preprocessed.extractedEntities

            entities.upiIds.filter      { it.isNotBlank() }.forEach { evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "UPI_ID",     entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.phoneNumbers.filter{ it.isNotBlank() }.forEach { evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "PHONE",       entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.accountNumbers.filter{ it.isNotBlank() }.forEach { evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "ACCOUNT",  entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.urls.filter        { it.isNotBlank() }.forEach { evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "URL",         entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.amounts.filter     { it.isNotBlank() }.forEach { evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "AMOUNT",      entityValue = it, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }

            if (evidenceList.isNotEmpty()) {
                db.knowledgeBaseDao().insertEvidenceBatch(evidenceList)
            }
        }

        // Generate the next honeypot reply based on what the attacker just said
        val suggestedReply = withContext(Dispatchers.Default) {
            llmClassifier.generateReplyHint(
                conversationHistory = fullHistory,
                scamType = originalScamType
            )
        }

        // Synthesise a ScamResult that reflects the ongoing confirmed session
        val scamResult = ScamResult(
            isScam = true,
            scamType = originalScamType,
            confidence = originalConfidence,
            reasoning = "⚠️ Scam session active. This message is a continuation of a confirmed " +
                "$originalScamType scam. New information from the attacker has been captured.",
            extractedEntities = (preprocessed.extractedEntities.phoneNumbers +
                preprocessed.extractedEntities.upiIds +
                preprocessed.extractedEntities.accountNumbers +
                preprocessed.extractedEntities.urls +
                preprocessed.extractedEntities.amounts).filter { it.isNotBlank() },
            evidenceFlags = emptyList()
        )

        return DetectionResult(
            sms = sms,
            filterResult = com.example.myapplication.filter.FilterResult.PROCESS,
            triggerResult = null,
            kbResults = emptyList(),
            scamResult = scamResult,
            conversationText = conversationText,
            suggestedReply = suggestedReply,
            conversationHistory = fullHistory
        )
    }

    fun close() {
        llmClassifier.close()
        embedder.close()
    }
}

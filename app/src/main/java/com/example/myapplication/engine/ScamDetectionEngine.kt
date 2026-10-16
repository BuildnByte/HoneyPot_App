package com.example.myapplication.engine

import android.content.Context
import com.example.myapplication.filter.FilterPreferenceManager
import com.example.myapplication.filter.FilterResult
import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.llm.CloudReasoningService
import com.example.myapplication.llm.CloudReplyGenerator
import com.example.myapplication.model.ExtractedEntity
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.ScamEvidence
import com.example.myapplication.model.ScamResult
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.trigger.TriggerEngine
import com.example.myapplication.vectorsearch.VectorSearchEngine
import com.example.myapplication.verification.FactCheckService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScamDetectionEngine(private val context: Context) {

    private val db             = KnowledgeBaseDatabase.getInstance(context)
    private val embedder       = com.example.myapplication.embedding.ScamTextEmbedder(context)
    private val vectorSearch   = VectorSearchEngine(db, embedder)
    private val onnxClassifier = OnnxScamClassifier(context)
    private val factCheckService = FactCheckService(context)
    private val filterPrefs    = FilterPreferenceManager(context)

    data class DetectionResult(
        val sms: IncomingSms,
        val filterResult: FilterResult,
        val triggerResult: TriggerEngine.TriggerResult?,
        val kbResults: List<VectorSearchEngine.SearchResult>,
        val scamResult: ScamResult?,
        val conversationText: String,
        val suggestedReply: String = "",
        val conversationHistory: List<String> = emptyList(),
    )

    suspend fun initialize() {
        populateKnowledgeBaseIfNeeded()
        embedder.initialize()
        vectorSearch.precomputeEmbeddings()
        onnxClassifier.initialize()   // loads vocab + ONNX model
    }

    // ── Main processing pipeline ──────────────────────────────────────────────

    suspend fun processMessage(
        sms: IncomingSms,
        history: List<String>,
        explicitSessionId: String? = null,
        skipHoneypotReply: Boolean = false
    ): DetectionResult {

        // ── STAGE 1: Pre-filter ───────────────────────────────────────────────
        val filterResult = SmsPreFilter.shouldIgnore(sms.sender, sms.body, filterPrefs)
        if (filterResult == FilterResult.IGNORE) {
            return DetectionResult(sms, filterResult, null, emptyList(), null, "")
        }

        // ── STAGE 2: Preprocess ───────────────────────────────────────────────
        val preprocessed = withContext(Dispatchers.Default) {
            SmsPreprocessor.process(sms)
        }

        // ── STAGE 3: Conversation tracking ───────────────────────────────────
        val fullHistory     = history + "[${sms.sender}]: ${sms.body}"
        val conversationText = fullHistory.joinToString("\n")

        // ── STAGE 4: Trigger check ────────────────────────────────────────────
        val triggerResult = TriggerEngine.evaluate(preprocessed, history)
        if (!triggerResult.shouldProcess) {
            return DetectionResult(sms, filterResult, triggerResult, emptyList(), null, conversationText)
        }

        // ── STAGE 5: Vector search ────────────────────────────────────────────
        val kbResults = withContext(Dispatchers.Default) {
            vectorSearch.search(queryText = conversationText, topK = 3, minSimilarity = 0.45f)
        }

        if (kbResults.isEmpty() && triggerResult.priority == TriggerEngine.Priority.LOW) {
            return DetectionResult(sms, filterResult, triggerResult, kbResults, null, conversationText)
        }

        // ── STAGE 6: MuRIL BERT ONNX Classification ───────────────────────────
        val bertResult = withContext(Dispatchers.Default) {
            onnxClassifier.classify(sms.body)
        }

        if (!bertResult.isScam) {
            // Safe — return early with no ScamResult
            return DetectionResult(sms, filterResult, triggerResult, kbResults, null, conversationText)
        }

        // ── STAGE 6b: Confidence blending ─────────────────────────────────────
        // BERT softmax is well-calibrated; blend lightly with trigger score for robustness
        val triggerScore = when (triggerResult.priority) {
            TriggerEngine.Priority.CRITICAL -> 1.0f
            TriggerEngine.Priority.HIGH     -> 0.8f
            TriggerEngine.Priority.MEDIUM   -> 0.5f
            TriggerEngine.Priority.LOW      -> 0.2f
        }
        val blendedConf = if (bertResult.scamProbability >= 0.90f) {
            bertResult.scamProbability                                  // high confidence → trust BERT
        } else {
            (bertResult.scamProbability * 0.75f) + (triggerScore * 0.25f) // blend for borderline
        }

        // ── STAGE 6c: Scam type resolution ────────────────────────────────────
        val scamType = ScamTypeResolver.resolve(triggerResult, kbResults)

        // ── STAGE 6d: On-device entity extraction (rule-based NER) ────────────
        val onDeviceEntities = ScamEntityExtractor.extract(sms.body)

        // Merge with preprocessor regex entities (phones, UPIs, URLs, amounts, accounts)
        val regexEntities = preprocessed.extractedEntities.let { e ->
            e.phoneNumbers.filter { it.isNotBlank() }.map { ExtractedEntity("PHONE", it) } +
            e.upiIds.filter      { it.isNotBlank() }.map { ExtractedEntity("UPI_ID", it) } +
            e.urls.filter        { it.isNotBlank() }.map { ExtractedEntity("URL", it) } +
            e.amounts.filter     { it.isNotBlank() }.map { ExtractedEntity("AMOUNT", it) } +
            e.accountNumbers.filter { it.isNotBlank() }.map { ExtractedEntity("ACCOUNT", it) }
        }
        val allOnDeviceEntities = (regexEntities + onDeviceEntities)
            .distinctBy { "${it.type}:${it.value.trim().lowercase()}" }

        // ── STAGE 7: Fact-check ───────────────────────────────────────────────
        val factCheckResults = factCheckService.verifyEntities(preprocessed.extractedEntities)
        val evidenceFlags = factCheckResults.map { fc ->
            com.example.myapplication.model.EvidenceFlag(
                indicator       = fc.indicatorValue,
                verdict         = fc.verdict,
                confidenceLevel = when {
                    fc.confidence >= 0.8f -> "HIGH"
                    fc.confidence >= 0.5f -> "MEDIUM"
                    else                  -> "LOW"
                },
                type       = fc.indicatorType,
                confidence = "${(fc.confidence * 100).toInt()}%"
            )
        }

        // ── STAGE 8: Cloud reasoning — FIRST MESSAGE ONLY ─────────────────────
        // Makes one Gemini call to get reasoning + catch any entities the on-device
        // NER missed. Uses filterPrefs.useCloudReasoning toggle.
        val kbMatchNames = kbResults.map { it.entry.patternDescription }
        val cloudResult = if (filterPrefs.useCloudReasoning) {
            withContext(Dispatchers.IO) {
                CloudReasoningService.analyzeFirstMessage(
                    smsBody       = sms.body,
                    scamType      = scamType,
                    firedTriggers = triggerResult.firedTriggers,
                    kbMatchNames  = kbMatchNames
                )
            }
        } else {
            CloudReasoningService.analyzeFirstMessage(      // always get at least template fallback
                smsBody       = sms.body,
                scamType      = scamType,
                firedTriggers = triggerResult.firedTriggers,
                kbMatchNames  = kbMatchNames
            )
        }

        // Merge cloud entities with on-device entities
        val allEntities = (allOnDeviceEntities + cloudResult.additionalEntities)
            .distinctBy { "${it.type}:${it.value.trim().lowercase()}" }

        // ── Build final ScamResult ─────────────────────────────────────────────
        val scamResult = ScamResult(
            isScam            = true,
            scamType          = scamType,
            confidence        = blendedConf,
            reasoning         = cloudResult.reasoning,
            extractedEntities = allEntities,
            evidenceFlags     = evidenceFlags
        )

        // ── STAGE 9: Evidence storage ─────────────────────────────────────────
        withContext(Dispatchers.IO) {
            val sessionId = explicitSessionId ?: buildSessionId(sms)
            val evidenceList = allEntities
                .filter { it.value.isNotBlank() }
                .map { entity ->
                    ScamEvidence(
                        sessionId         = sessionId,
                        sourceSender      = sms.sender,
                        entityType        = entity.type,
                        entityValue       = entity.value,
                        sourceMessageText = sms.body,
                        analysisLogId     = null,
                        timestamp         = sms.timestamp
                    )
                }
            if (evidenceList.isNotEmpty()) {
                db.knowledgeBaseDao().insertEvidenceBatch(evidenceList)
            }
        }

        // ── STAGE 10: Honeypot reply suggestion ───────────────────────────────
        val suggestedReply = if (skipHoneypotReply) "" else withContext(Dispatchers.Default) {
            CloudReplyGenerator.generateReply(fullHistory, scamType)
        }

        return DetectionResult(
            sms               = sms,
            filterResult      = filterResult,
            triggerResult     = triggerResult,
            kbResults         = kbResults,
            scamResult        = scamResult,
            conversationText  = conversationText,
            suggestedReply    = suggestedReply,
            conversationHistory = fullHistory
        )
    }

    // ── Fast-path for confirmed-scam follow-up messages ───────────────────────

    /**
     * Fast-path for subsequent attacker messages in an already-confirmed scam session.
     * Skips: filter, trigger, vector search, BERT, cloud reasoning.
     * Runs:  regex entity extraction → evidence storage → reply generation.
     */
    suspend fun processConfirmedScamFollowUp(
        sms: IncomingSms,
        history: List<String>,
        originalScamType: String,
        originalConfidence: Float,
        explicitSessionId: String? = null
    ): DetectionResult {
        val fullHistory      = history + "[${sms.sender}]: ${sms.body}"
        val conversationText = fullHistory.joinToString("\n")

        val preprocessed = withContext(Dispatchers.Default) { SmsPreprocessor.process(sms) }

        // Persist new evidence entities revealed in this follow-up message
        withContext(Dispatchers.IO) {
            val sessionId = explicitSessionId ?: buildSessionId(sms)
            val entities  = preprocessed.extractedEntities
            val evidenceList = mutableListOf<ScamEvidence>()

            entities.phoneNumbers.filter   { it.isNotBlank() }.forEach { v -> evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "PHONE",   entityValue = v, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.upiIds.filter         { it.isNotBlank() }.forEach { v -> evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "UPI_ID",  entityValue = v, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.accountNumbers.filter { it.isNotBlank() }.forEach { v -> evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "ACCOUNT", entityValue = v, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.urls.filter           { it.isNotBlank() }.forEach { v -> evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "URL",     entityValue = v, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }
            entities.amounts.filter        { it.isNotBlank() }.forEach { v -> evidenceList.add(ScamEvidence(sessionId = sessionId, sourceSender = sms.sender, entityType = "AMOUNT",  entityValue = v, sourceMessageText = sms.body, analysisLogId = null, timestamp = sms.timestamp)) }

            if (evidenceList.isNotEmpty()) db.knowledgeBaseDao().insertEvidenceBatch(evidenceList)
        }

        val suggestedReply = withContext(Dispatchers.Default) {
            CloudReplyGenerator.generateReply(fullHistory, originalScamType)
        }

        val scamResult = ScamResult(
            isScam            = true,
            scamType          = originalScamType,
            confidence        = originalConfidence,
            reasoning         = "⚠️ Scam session active. This message is a continuation of a confirmed " +
                                "$originalScamType scam. New information from the attacker has been captured.",
            extractedEntities = preprocessed.extractedEntities.let { e ->
                e.phoneNumbers.map     { ExtractedEntity("PHONE", it) } +
                e.upiIds.map           { ExtractedEntity("UPI_ID", it) } +
                e.accountNumbers.map   { ExtractedEntity("ACCOUNT", it) } +
                e.urls.map             { ExtractedEntity("URL", it) } +
                e.amounts.map          { ExtractedEntity("AMOUNT", it) }
            }.filter { it.value.isNotBlank() },
            evidenceFlags = emptyList()
        )

        return DetectionResult(
            sms               = sms,
            filterResult      = FilterResult.PROCESS,
            triggerResult     = null,
            kbResults         = emptyList(),
            scamResult        = scamResult,
            conversationText  = conversationText,
            suggestedReply    = suggestedReply,
            conversationHistory = fullHistory
        )
    }

    fun close() {
        onnxClassifier.close()
        embedder.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildSessionId(sms: IncomingSms): String {
        val datePart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sms.timestamp))
        return "${sms.sender}_$datePart"
    }

    private suspend fun populateKnowledgeBaseIfNeeded() = withContext(Dispatchers.IO) {
        if (db.knowledgeBaseDao().count() == 0) {
            android.util.Log.d("ScamDetectionEngine", "Populating initial knowledge base…")
            val initialEntries = listOf(
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "KYC Phishing",
                    patternDescription = "Urgent request to update KYC via suspicious link to avoid account blocking.",
                    exampleTriggerText = "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-bank.com",
                    responseStrategy = "Warn user and suggest calling bank official number.", severity = 3
                ),
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "Lottery Scam",
                    patternDescription = "Message claiming you won a large sum of money and asking for personal details.",
                    exampleTriggerText = "Congratulations! You won 1 Crore in KBC. Call 9988776655 to claim your prize.",
                    responseStrategy = "Inform user it's a common fraud. Do not call or share details.", severity = 4
                ),
                com.example.myapplication.knowledge.KnowledgeBaseEntry(
                    scamType = "Electricity Bill Scam",
                    patternDescription = "Threatening to disconnect electricity due to unpaid bill.",
                    exampleTriggerText = "Dear consumer, your electricity will be disconnected tonight at 9:30 PM because your previous month bill was not updated. Please contact our officer 8877665544.",
                    responseStrategy = "Suggest checking with official utility provider website.", severity = 3
                )
            )
            db.knowledgeBaseDao().insertAll(initialEntries)
        }
    }
}

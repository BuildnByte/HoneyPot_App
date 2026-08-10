// service/SmsProcessingService.kt
class SmsProcessingService : LifecycleService() {

    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var embedder: ScamTextEmbedder
    private lateinit var vectorSearch: VectorSearchEngine
    private lateinit var llmClassifier: ScamLlmClassifier

    // In-memory conversation store: senderNumber → list of messages
    private val conversationStore = mutableMapOf<String, MutableList<String>>()

    override fun onCreate() {
        super.onCreate()
        db = KnowledgeBaseDatabase.getInstance(this)
        embedder = ScamTextEmbedder(this)
        vectorSearch = VectorSearchEngine(db, embedder)
        llmClassifier = ScamLlmClassifier(this)

        // Pre-compute KB embeddings on first launch (runs once, ~2-5s)
        lifecycleScope.launch(Dispatchers.IO) {
            if (db.knowledgeBaseDao().count() > 0 &&
                db.knowledgeBaseDao().getAll().first().embeddingBlob.isEmpty()) {
                vectorSearch.precomputeEmbeddings()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val incomingSms = intent?.getParcelableExtra<IncomingSms>("sms")
            ?: return START_NOT_STICKY

        lifecycleScope.launch {
            processSms(incomingSms)
        }

        return START_NOT_STICKY
    }

    private suspend fun processSms(sms: IncomingSms) {

        // --- STAGE 1: Pre-filter (sync, ~1ms) ---
        val filterResult = SmsPreFilter.shouldIgnore(sms.sender, sms.body)
        if (filterResult == FilterResult.IGNORE) return

        // --- STAGE 2: Preprocess (sync, ~5ms) ---
        val preprocessed = withContext(Dispatchers.Default) {
            SmsPreprocessor.process(sms)
        }

        // --- STAGE 3: Track conversation ---
        val history = conversationStore.getOrPut(sms.sender) { mutableListOf() }
        history.add("[${sms.sender}]: ${sms.body}")
        // Keep last 10 messages to bound memory + context window
        if (history.size > 10) history.removeAt(0)

        val conversationText = history.joinToString("\n")

        // --- STAGE 4: Trigger check (sync, ~2ms) ---
        val triggerResult = TriggerEngine.evaluate(preprocessed, history)
        if (!triggerResult.shouldProcess) return

        // --- STAGE 5: Embed + Vector Search (async, ~15ms total) ---
        val kbResults = withContext(Dispatchers.Default) {
            vectorSearch.search(
                queryText = conversationText,
                topK = 3,
                minSimilarity = 0.45f
            )
        }

        // If no KB entries match at all, this is likely not a known scam pattern
        // Use a lower-confidence fallback based on trigger count alone
        if (kbResults.isEmpty() && triggerResult.priority == TriggerEngine.Priority.LOW) return

        // --- STAGE 6: LLM Classification (async, ~500ms–2s on-device) ---
        val result = withContext(Dispatchers.Default) {
            llmClassifier.classify(
                conversationText = conversationText,
                retrievedKbEntries = kbResults,
                firedTriggers = triggerResult.firedTriggers
            )
        }

        // --- STAGE 7: Act on result ---
        when {
            result.isScam && result.confidence >= 0.85f -> {
                showScamAlert(sms, result)
            }
            result.isScam && result.confidence >= 0.60f -> {
                showWarningNotification(sms, result)
            }
            else -> {
                logForMonitoring(sms, result, triggerResult)
            }
        }
    }

    private fun showScamAlert(sms: IncomingSms, result: ScamResult) {
        // Fire high-priority notification
        // Store to Room for report generation later
        // Offer user the option to hand off to AI agent
    }

    private fun showWarningNotification(sms: IncomingSms, result: ScamResult) {
        // Fire warning notification with "This looks suspicious"
    }

    private fun logForMonitoring(sms: IncomingSms, result: ScamResult,
                                 trigger: TriggerEngine.TriggerResult) {
        // Write to a monitoring log table in Room — useful for debugging
    }

    override fun onDestroy() {
        super.onDestroy()
        llmClassifier.close()
    }
}
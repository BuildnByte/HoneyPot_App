package com.example.myapplication.ui

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.engine.ScamDetectionEngine
import com.example.myapplication.engine.ScamDetectionEngineManager
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.model.AiAnalysisLog
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.SimulatedMessage
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

class SimulationActivity : AppCompatActivity() {

    private lateinit var engine: ScamDetectionEngine
    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var senderToggle: MaterialButtonToggleGroup
    private lateinit var sendButton: MaterialButton
    
    private val sessionId = UUID.randomUUID().toString()
    private var isEngineReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulation)

        db = KnowledgeBaseDatabase.getInstance(this)

        setupUI()
        loadHistory()
        
        lifecycleScope.launch {
            try {
                sendButton.isEnabled = false
                engine = ScamDetectionEngineManager.getEngine(this@SimulationActivity)
                isEngineReady = true
                sendButton.isEnabled = true
            } catch (e: Throwable) {
                android.util.Log.e("SimulationActivity", "Failed to initialize engine", e)
                com.google.android.material.snackbar.Snackbar.make(
                    findViewById(android.R.id.content),
                    "Failed to load ML models: ${e.message}",
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).show()
            }
        }
    }

    private fun setupUI() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        recyclerView = findViewById(R.id.chatRecyclerView)
        adapter = ChatAdapter { messageId ->
            handleBrainClick(messageId)
        }
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        messageInput = findViewById(R.id.messageInput)
        senderToggle = findViewById(R.id.senderToggleGroup)
        sendButton = findViewById(R.id.sendButton)

        sendButton.setOnClickListener {
            val senderType = if (senderToggle.checkedButtonId == R.id.selectV) "V" else "A"
            sendMessage(senderType)
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = db.knowledgeBaseDao().getSimulationHistory(sessionId)
            adapter.submitList(history)
            
            // Load existing analysis logs for this session
            val logs = mutableListOf<AiAnalysisLog>()
            history.forEach { msg ->
                db.knowledgeBaseDao().getAnalysisForMessage(msg.id)?.let { logs.add(it) }
            }
            adapter.setAnalysisResults(logs)

            if (history.isNotEmpty()) {
                recyclerView.scrollToPosition(history.size - 1)
            }
        }
    }

    private fun sendMessage(senderType: String) {
        val content = messageInput.text.toString().trim()
        if (content.isEmpty()) return

        val timestamp = System.currentTimeMillis()
        val message = SimulatedMessage(
            senderType = senderType,
            content = content,
            timestamp = timestamp,
            sessionId = sessionId
        )

        messageInput.text.clear()

        lifecycleScope.launch {
            val id = db.knowledgeBaseDao().insertSimulatedMessage(message)
            val savedMessage = message.copy(id = id.toInt())
            
            adapter.addMessage(savedMessage)
            recyclerView.smoothScrollToPosition(adapter.itemCount - 1)

            if (senderType == "A") {
                processWithEngine(savedMessage)
            }
        }
    }

    private suspend fun processWithEngine(message: SimulatedMessage) {
        // Prevent duplicate processing
        val existingLog = db.knowledgeBaseDao().getAnalysisForMessage(message.id)
        if (existingLog != null) return

        adapter.setProcessing(message.id, true)
        
        val historyFromDb = db.knowledgeBaseDao().getSimulationHistory(sessionId)
        val engineHistory = historyFromDb
            .filter { it.id != message.id }
            .map { "[${it.senderType}]: ${it.content}" }

        try {
            val sms = IncomingSms(message.senderType, message.content)

            // --- Session Scam Lock ---
            // If this session has already been confirmed as a scam, skip re-classification.
            // The attacker's follow-up messages are treated as scam continuations:
            // evidence is still captured and a new honeypot reply is generated.
            val priorScamLog = db.knowledgeBaseDao().getFirstScamLogForSession(sessionId)
            val result = if (priorScamLog != null) {
                engine.processConfirmedScamFollowUp(
                    sms = sms,
                    history = engineHistory,
                    originalScamType = priorScamLog.scamType,
                    originalConfidence = priorScamLog.confidence
                )
            } else {
                engine.processMessage(sms, engineHistory)
            }

            if (result.scamResult != null) {
                val log = AiAnalysisLog(
                    messageId = message.id,
                    confidence = result.scamResult.confidence,
                    isScam = result.scamResult.isScam,
                    scamType = result.scamResult.scamType,
                    reason = if (result.scamResult.reasoning == "Parse failed") 
                        "AI returned invalid format. Please try again." else result.scamResult.reasoning,
                    firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                    kbMatches = result.kbResults.joinToString("\n") { 
                        "${it.entry.scamType} (${String.format(Locale.getDefault(), "%.2f", it.similarity)})" 
                    },
                    evidenceFlagsJson = Json.encodeToString(result.scamResult.evidenceFlags),
                    suggestedReply = result.suggestedReply,
                    timestamp = System.currentTimeMillis()
                )
                db.knowledgeBaseDao().insertAiAnalysisLog(log)
                adapter.addAnalysisResult(log)
            } else {
                // Result was null (e.g. low priority, no KB matches)
                val reason = when {
                    result.filterResult == com.example.myapplication.filter.FilterResult.IGNORE -> "Message ignored by pre-filter."
                    result.triggerResult?.shouldProcess == false -> "No suspicious triggers detected."
                    else -> "Low risk message. No detailed analysis required."
                }
                
                val log = AiAnalysisLog(
                    messageId = message.id,
                    confidence = 0f,
                    isScam = false,
                    reason = reason,
                    firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                    kbMatches = "",
                    evidenceFlagsJson = "[]",
                    timestamp = System.currentTimeMillis()
                )
                db.knowledgeBaseDao().insertAiAnalysisLog(log)
                adapter.addAnalysisResult(log)
            }
        } catch (e: Exception) {
            android.util.Log.e("SimulationActivity", "Error during inference", e)
            adapter.setProcessing(message.id, false)
        }
    }

    private fun handleBrainClick(messageId: Int) {
        lifecycleScope.launch {
            val log = db.knowledgeBaseDao().getAnalysisForMessage(messageId)
            if (log != null) {
                AnalysisBottomSheet.newInstance(log).show(supportFragmentManager, AnalysisBottomSheet.TAG)
            } else {
                // If no log, try to trigger inference (if it failed or was skipped)
                val messages = db.knowledgeBaseDao().getSimulationHistory(sessionId)
                val message = messages.find { it.id == messageId }
                if (message != null && message.senderType == "A") {
                    processWithEngine(message)
                } else {
                    com.google.android.material.snackbar.Snackbar.make(
                        findViewById(android.R.id.content),
                        "Analysis only available for attacker messages.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not close global engine here, it might be used by the background service
    }
}

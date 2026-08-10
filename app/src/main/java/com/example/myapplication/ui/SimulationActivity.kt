package com.example.myapplication.ui

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.engine.ScamDetectionEngine
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.model.AiAnalysisLog
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.SimulatedMessage
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import java.util.*

class SimulationActivity : AppCompatActivity() {

    private lateinit var engine: ScamDetectionEngine
    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var senderToggle: MaterialButtonToggleGroup
    
    private val sessionId = UUID.randomUUID().toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulation)

        db = KnowledgeBaseDatabase.getInstance(this)
        engine = ScamDetectionEngine(this)

        setupUI()
        loadHistory()
        
        lifecycleScope.launch {
            try {
                engine.initialize()
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
            showAnalysis(messageId)
        }
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter

        messageInput = findViewById(R.id.messageInput)
        senderToggle = findViewById(R.id.senderToggleGroup)

        findViewById<MaterialButton>(R.id.sendButton).setOnClickListener {
            val senderType = if (senderToggle.checkedButtonId == R.id.selectV) "V" else "A"
            sendMessage(senderType)
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val history = db.knowledgeBaseDao().getSimulationHistory(sessionId)
            adapter.submitList(history)
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
        val historyFromDb = db.knowledgeBaseDao().getSimulationHistory(sessionId)
        val engineHistory = historyFromDb
            .filter { it.id != message.id }
            .map { "[${it.senderType}]: ${it.content}" }

        val sms = IncomingSms(message.senderType, message.content)
        val result = engine.processMessage(sms, engineHistory)

        if (result.scamResult != null) {
            val log = AiAnalysisLog(
                messageId = message.id,
                confidence = result.scamResult.confidence,
                isScam = result.scamResult.isScam,
                reason = result.scamResult.reasoning,
                firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                kbMatches = result.kbResults.joinToString("\n") { 
                    "${it.entry.scamType} (${String.format(Locale.getDefault(), "%.2f", it.similarity)})" 
                },
                timestamp = System.currentTimeMillis()
            )
            db.knowledgeBaseDao().insertAiAnalysisLog(log)
            
            adapter.notifyItemChanged(adapter.itemCount - 1)
        }
    }

    private fun showAnalysis(messageId: Int) {
        lifecycleScope.launch {
            val log = db.knowledgeBaseDao().getAnalysisForMessage(messageId)
            if (log != null) {
                AnalysisBottomSheet.newInstance(log).show(supportFragmentManager, AnalysisBottomSheet.TAG)
            } else {
                com.google.android.material.snackbar.Snackbar.make(
                    findViewById(android.R.id.content),
                    "Analysis still in progress or not available.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.close()
    }
}

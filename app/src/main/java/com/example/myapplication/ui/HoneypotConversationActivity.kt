package com.example.myapplication.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.telephony.SmsManager
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.example.myapplication.engine.ScamDetectionEngineManager
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.llm.CloudReplyGenerator
import com.example.myapplication.llm.ConsentPreferenceManager
import com.example.myapplication.model.AiAnalysisLog
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.SimulatedMessage
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

class HoneypotConversationActivity : AppCompatActivity() {

    private lateinit var db: KnowledgeBaseDatabase
    private lateinit var consentManager: ConsentPreferenceManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: View
    private lateinit var suggestionContainer: View
    private lateinit var suggestionText: TextView
    private lateinit var useSuggestionButton: MaterialButton
    private var scamAlertBanner: View? = null

    private lateinit var sessionId: String
    private var attackerPhone: String = ""

    /** Tracks how many messages the adapter last displayed, so we know when to scroll. */
    private var lastKnownMessageCount = 0

    companion object {
        private const val TAG = "HoneypotConvActivity"
        private const val PERMISSION_SEND_SMS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_honeypot_conversation)
        SystemBars.apply(this, findViewById(android.R.id.content))

        db = KnowledgeBaseDatabase.getInstance(this)
        consentManager = ConsentPreferenceManager(this)
        sessionId = intent.getStringExtra("EXTRA_SESSION_ID") ?: return finish()

        setupUI()
        loadStaticSessionMeta()
        observeMessages()        // real-time message list
        observeSuggestedReply()  // auto-surface suggestion bar when AI finishes
    }

    private fun setupUI() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.chatRecyclerView)
        adapter = ChatAdapter { messageId -> handleBrainClick(messageId) }
        // Messages flow from top — stackFromEnd=false
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = false }
        recyclerView.adapter = adapter

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        // Send button: send whatever is typed in the field
        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                attemptSendSms(text)
                messageInput.setText("")
            }
        }

        // Scam alert banner + end session
        scamAlertBanner = findViewById(R.id.scamAlertBanner)
    }

    // ── Real-time message observation ─────────────────────────────────────────

    /**
     * Watches the message list and updates the RecyclerView whenever rows change.
     * This covers: new attacker messages arriving, user-sent messages appearing.
     * Suggestion bar logic is handled separately by [observeSuggestedReply].
     */
    private fun observeMessages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.knowledgeBaseDao().observeSessionMessages(sessionId).collect { messages ->
                    val logs = withContext(Dispatchers.IO) {
                        messages.mapNotNull { msg ->
                            db.knowledgeBaseDao().getAnalysisForMessage(msg.id)
                        }
                    }

                    adapter.submitList(messages)
                    adapter.setAnalysisResults(logs)

                    // Auto-scroll only when new messages arrive
                    if (messages.size > lastKnownMessageCount && messages.isNotEmpty()) {
                        recyclerView.post {
                            recyclerView.scrollToPosition(messages.size - 1)
                        }
                    }
                    lastKnownMessageCount = messages.size
                }
            }
        }
    }

    /**
     * Watches ai_analysis_logs for this session and automatically shows the suggestion bar
     * as soon as a reply is ready — even for follow-up messages that are auto-analysed
     * by SmsProcessingService in the background.
     *
     * Logic:
     *  - Only shows if the LAST message in the conversation is an attacker message
     *    (i.e. the user has not yet replied).
     *  - Hides the bar when the user sends a message (lastMsg becomes senderType="U").
     */
    private fun observeSuggestedReply() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.knowledgeBaseDao().observeLatestSuggestedReply(sessionId).collect { log ->
                    if (log == null) return@collect

                    // Only surface if the last message is unanswered attacker turn
                    val lastMsg = withContext(Dispatchers.IO) {
                        db.knowledgeBaseDao().getSimulationHistory(sessionId).lastOrNull()
                    }
                    if (lastMsg != null && lastMsg.senderType == "A") {
                        // Directly fill the text box
                        if (messageInput.text.toString().isBlank()) {
                            messageInput.setText(log.suggestedReply)
                            messageInput.setSelection(log.suggestedReply.length)
                        }
                    }
                }
            }
        }
    }

    /** Load one-time static data (session phone, toolbar title). */
    private fun loadStaticSessionMeta() {
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.knowledgeBaseDao().getSession(sessionId)
            if (session == null) {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }
            attackerPhone = session.attackerPhone
            withContext(Dispatchers.Main) {
                // Clean title: masked number for phone sessions, plain label for manual captures
                val rawName = session.attackerPhone.ifBlank { session.name }
                val isManualCapture = session.name.contains("MANUAL_CAPTURE", ignoreCase = true)
                    || session.name.contains("CAPTURE", ignoreCase = true)
                    || session.attackerPhone.isBlank()
                val title = when {
                    isManualCapture -> "Screenshot Analysis"
                    rawName.matches(Regex(".*\\d{10,}.*")) -> {
                        // Mask the phone: keep last 4, show +91 XXXXX XXXX
                        val digits = rawName.filter { it.isDigit() }.takeLast(10)
                        val last4 = digits.takeLast(4)
                        val first2 = digits.take(2)
                        "+91 XXXXX $last4"
                    }
                    else -> rawName.removePrefix("Honeypot - ").removePrefix("[CAPTURE] ")
                        .replace("MANUAL_CAPTURE", "Manual Scan")
                        .trim()
                }
                supportActionBar?.title = title
                supportActionBar?.subtitle = "Honeypot Session"
            }
        }
    }

    // ── Brain click: run AI engine on tapped message ──────────────────────────

    private fun handleBrainClick(messageId: Int) {
        Log.d(TAG, "Brain click on message $messageId")
        lifecycleScope.launch {
            // If already analysed, just show the cached result
            val existing = withContext(Dispatchers.IO) {
                db.knowledgeBaseDao().getAnalysisForMessage(messageId)
            }
            if (existing != null) {
                AnalysisBottomSheet.newInstance(existing).show(supportFragmentManager, "analysis")
                return@launch
            }

            adapter.setProcessing(messageId, true)

            val history = withContext(Dispatchers.IO) {
                db.knowledgeBaseDao().getSimulationHistory(sessionId)
            }
            val message = history.find { it.id == messageId }
            if (message == null) {
                adapter.setProcessing(messageId, false)
                return@launch
            }

            if (message.senderType != "A") {
                adapter.setProcessing(messageId, false)
                Snackbar.make(recyclerView, "AI analysis is for attacker messages only.", Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            val engineHistory = history
                .filter { it.id != messageId }
                .map { "[${it.senderType}]: ${it.content}" }

            try {
                val engine = ScamDetectionEngineManager.getEngine(this@HoneypotConversationActivity)

                val sms = IncomingSms(
                    sender = attackerPhone.takeIf { it.isNotBlank() } ?: message.senderType,
                    body = message.content,
                    timestamp = message.timestamp
                )

                val priorScamLog = withContext(Dispatchers.IO) {
                    db.knowledgeBaseDao().getFirstScamLogForSession(sessionId)
                }

                val result = withContext(Dispatchers.Default) {
                    if (priorScamLog != null) {
                        engine.processConfirmedScamFollowUp(
                            sms = sms,
                            history = engineHistory,
                            originalScamType = priorScamLog.scamType,
                            originalConfidence = priorScamLog.confidence,
                            explicitSessionId = sessionId
                        )
                    } else {
                        engine.processMessage(sms, engineHistory, explicitSessionId = sessionId)
                    }
                }

                val scamResult = result.scamResult

                // Cloud consent check
                val finalReply: String
                val usedCloud: Boolean

                if (scamResult != null && scamResult.isScam) {
                    val consent = resolveCloudConsent(
                        contactNumber = attackerPhone,
                        conversationHistory = engineHistory + "[${sms.sender}]: ${sms.body}"
                    )
                    if (consent) {
                        val cloudHistory = engineHistory + "[${sms.sender}]: ${sms.body}"
                        finalReply = CloudReplyGenerator.generateReply(cloudHistory, scamResult.scamType)
                        usedCloud = true
                    } else {
                        finalReply = result.suggestedReply
                        usedCloud = false
                    }
                } else {
                    finalReply = result.suggestedReply
                    usedCloud = false
                }

                val log = if (scamResult != null) {
                    AiAnalysisLog(
                        messageId = messageId,
                        confidence = scamResult.confidence,
                        isScam = scamResult.isScam,
                        scamType = scamResult.scamType,
                        reason = scamResult.reasoning.takeIf { it.isNotBlank() } ?: "Analysis complete.",
                        firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                        kbMatches = result.kbResults.joinToString("\n") {
                            "${it.entry.scamType} (${String.format(java.util.Locale.getDefault(), "%.2f", it.similarity)})"
                        },
                        evidenceFlagsJson = try { Json.encodeToString(scamResult.evidenceFlags) } catch (_: Exception) { "[]" },
                        suggestedReply = finalReply,
                        suggestedReplySource = if (usedCloud) "cloud" else "local",
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    val reason = when {
                        result.filterResult == com.example.myapplication.filter.FilterResult.IGNORE ->
                            "Message ignored by pre-filter (e.g. legitimate institutional sender)."
                        result.triggerResult?.shouldProcess == false ->
                            "No suspicious triggers detected in this message."
                        else -> "Low risk message. No detailed analysis required."
                    }
                    AiAnalysisLog(
                        messageId = messageId,
                        confidence = 0f,
                        isScam = false,
                        reason = reason,
                        firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                        kbMatches = "",
                        evidenceFlagsJson = "[]",
                        suggestedReply = "",
                        timestamp = System.currentTimeMillis()
                    )
                }

                withContext(Dispatchers.IO) { db.knowledgeBaseDao().insertAiAnalysisLog(log) }
                
                // Immediately push the analysis result to the adapter so the UI changes instantly
                adapter.addAnalysisResult(log)

                AnalysisBottomSheet.newInstance(log).show(supportFragmentManager, "analysis")

            } catch (e: Exception) {
                Log.e(TAG, "AI analysis failed", e)
                adapter.setProcessing(messageId, false)
                Snackbar.make(recyclerView, "AI analysis failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ── Cloud consent dialog ──────────────────────────────────────────────────

    private suspend fun resolveCloudConsent(
        contactNumber: String,
        conversationHistory: List<String>
    ): Boolean {
        val stored = consentManager.getConsent(contactNumber)
        if (stored != null) return stored

        return suspendCancellableCoroutine { cont ->
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("🛡️ Let AI craft smarter replies?")
                    .setMessage(
                        "A scam from $contactNumber was detected.\n\n" +
                        "To suggest more natural replies, the conversation can be sent to Google's Gemini API.\n\n" +
                        "✅ What gets sent: message text only\n" +
                        "🔒 What stays local: your number, all detection & evidence"
                    )
                    .setPositiveButton("Enable Cloud AI") { _, _ ->
                        consentManager.setConsent(contactNumber, true)
                        if (cont.isActive) cont.resume(true)
                    }
                    .setNegativeButton("Keep Local") { _, _ ->
                        consentManager.setConsent(contactNumber, false)
                        if (cont.isActive) cont.resume(false)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    // ── SMS sending ───────────────────────────────────────────────────────────

    private fun attemptSendSms(text: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), PERMISSION_SEND_SMS)
        } else {
            sendRealSms(text)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_SEND_SMS &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) sendRealSms(text)
        } else {
            Toast.makeText(this, "SEND_SMS permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendRealSms(text: String) {
        if (attackerPhone.isBlank()) {
            Toast.makeText(this, "No phone number to send to", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(attackerPhone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(attackerPhone, null, text, null, null)
            }

            messageInput.text.clear()
            suggestionContainer.visibility = View.GONE

            // Save with senderType "U" — ChatAdapter and ReplyProgressionEngine both
            // treat "U" as an outgoing/victim message now.
            lifecycleScope.launch(Dispatchers.IO) {
                val simMsg = SimulatedMessage(
                    sessionId = sessionId,
                    senderType = "U",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
                db.knowledgeBaseDao().insertSimulatedMessage(simMsg)
                // No need to manually update the adapter — the Flow observer handles it.
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send SMS: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Toolbar overflow menu ────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_session_actions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_archive -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.knowledgeBaseDao().archiveSession(sessionId)
                    withContext(Dispatchers.Main) { finish() }
                }
                true
            }
            R.id.action_delete_session -> {
                confirmDeleteSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteSession() {
        // Load session name for a personalised message
        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.knowledgeBaseDao().getSession(sessionId)
            val name = session?.name?.removePrefix("[LIVE] ") ?: attackerPhone.ifBlank { "this session" }
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(this@HoneypotConversationActivity)
                    .setTitle("🗑️ Delete Session")
                    .setMessage(
                        "Permanently delete conversation with ‘$name’?\n\n" +
                        "This will erase all messages, AI analysis logs and captured evidence for this session. " +
                        "This action cannot be undone."
                    )
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.knowledgeBaseDao().deleteAnalysisLogsForSession(sessionId)
                            db.knowledgeBaseDao().deleteEvidenceForSession(sessionId)
                            db.knowledgeBaseDao().deleteSimulation(sessionId)
                            db.knowledgeBaseDao().deleteSession(sessionId)
                            withContext(Dispatchers.Main) {
                                setResult(RESULT_OK)
                                finish()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}

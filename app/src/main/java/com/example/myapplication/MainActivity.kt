package com.example.myapplication

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.trigger.TriggerEngine
import com.example.myapplication.ui.EvidenceActivity
import com.example.myapplication.ui.SessionListActivity
import com.example.myapplication.ui.SettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.myapplication.ui.SystemBars
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.verification.FactCheckService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERM_REQUEST_CODE = 100
    }

    private lateinit var resultText: TextView
    private lateinit var liveAdapter: com.example.myapplication.ui.SessionAdapter
    private lateinit var sandboxAdapter: com.example.myapplication.ui.SessionAdapter
    private lateinit var historyAdapter: com.example.myapplication.ui.EvidenceSessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.apply(this, findViewById(android.R.id.content))
        setSupportActionBar(findViewById(R.id.toolbar))

        val mainToolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        mainToolbar.inflateMenu(R.menu.main_menu)
        mainToolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                } else false
            }

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<BottomNavigationView>(R.id.bottomNavigation)
            .setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> { showPage(R.id.homeContent, "Honeypot", "SMS threat analysis"); true }
                    R.id.nav_messages -> {
                        showPage(R.id.messagesContent, "Flagged messages", "Live honeypot conversations"); true
                    }
                    R.id.nav_history -> {
                        showPage(R.id.historyContent, "History", "Captured evidence and sessions"); true
                    }
                    R.id.nav_sandbox -> {
                        showPage(R.id.sandboxContent, "Testing Sandbox", "Controlled pipeline sessions"); true
                    }
                    else -> false
                }
            }

        if (intent.getBooleanExtra("SHOW_ADVANCED_TOOLS", false)) {
            findViewById<View>(R.id.advancedTools).visibility = View.VISIBLE
        }

        resultText = findViewById(R.id.resultText)
        setupInlineSections()

        requestAllPermissions()

        // ── Navigation buttons ──────────────────────────────────────────────
        findViewById<Button>(R.id.testButton).setOnClickListener { runPipelineTest() }

        findViewById<Button>(R.id.simulationButton).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_sandbox
        }

        findViewById<View>(R.id.evidenceButton).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_history
        }

        findViewById<View>(R.id.liveHoneypotButton).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_messages
        }

        findViewById<View>(R.id.openMessagesButton).setOnClickListener {
            loadInlineSections()
        }
        findViewById<View>(R.id.openHistoryButton).setOnClickListener {
            loadInlineSections()
        }
        findViewById<View>(R.id.openSandboxButton).setOnClickListener {
            showCreateSessionSheet()
        }

        // ── Debug buttons ───────────────────────────────────────────────────

        // Button 1: Inject a synthetic SMS_RECEIVED broadcast.
        // If the SmsReceiver logs appear → broadcast works, issue is elsewhere.
        // If NO logs appear → the receiver itself is broken or permission denied.
        findViewById<View>(R.id.injectSmsButton).setOnClickListener {
            injectTestSmsBroadcast()
        }

        // Button 2: Skip the broadcast entirely and poke the service directly.
        // If this works but broadcast doesn't → it's a broadcast delivery problem.
        // (Custom ROM killing broadcasts, wrong receiver registration, etc.)
        findViewById<View>(R.id.directProcessButton).setOnClickListener {
            directStartProcessingService()
        }

        // Button 3: Test Safe Browsing API end-to-end with Google's own test malicious URL.
        // Expected result: MALICIOUS | confidence ~0.9 | source: safe_browsing
        // If you see UNVERIFIED: your API key is missing or incorrect.
        findViewById<View>(R.id.testSafeBrowsingButton).setOnClickListener {
            runSafeBrowsingTest()
        }
    }

    private fun showPage(pageId: Int, title: String, subtitle: String) {
        listOf(R.id.homeContent, R.id.messagesContent, R.id.historyContent, R.id.sandboxContent).forEach { id ->
            findViewById<View>(id).visibility = if (id == pageId) View.VISIBLE else View.GONE
        }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            this.title = title
            this.subtitle = subtitle
        }
    }

    private fun setupInlineSections() {
        liveAdapter = com.example.myapplication.ui.SessionAdapter(
            onClick = { session -> startActivity(Intent(this, com.example.myapplication.ui.HoneypotConversationActivity::class.java).apply { putExtra("EXTRA_SESSION_ID", session.id) }) },
            onLongClick = { }
        )
        sandboxAdapter = com.example.myapplication.ui.SessionAdapter(
            onClick = { session -> startActivity(Intent(this, com.example.myapplication.ui.SimulationActivity::class.java).apply { putExtra("EXTRA_SESSION_ID", session.id) }) },
            onLongClick = { }
        )
        historyAdapter = com.example.myapplication.ui.EvidenceSessionAdapter { summary ->
            startActivity(Intent(this, com.example.myapplication.ui.EvidenceDetailActivity::class.java).apply {
                putExtra("EXTRA_SESSION_ID", summary.sessionId)
                putExtra("EXTRA_SOURCE_SENDER", summary.sourceSender)
            })
        }
        findViewById<RecyclerView>(R.id.messagesRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = liveAdapter }
        findViewById<RecyclerView>(R.id.sandboxRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = sandboxAdapter }
        findViewById<RecyclerView>(R.id.historyRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = historyAdapter }
        loadInlineSections()
    }

    private fun loadInlineSections() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao()
            val live = dao.getLiveSessions()
            val sandbox = dao.getSimulatedSessions()
            val history = dao.getEvidenceSessionSummaries()
            withContext(Dispatchers.Main) {
                liveAdapter.submitList(live)
                sandboxAdapter.submitList(sandbox)
                historyAdapter.setItems(history)
                findViewById<View>(R.id.messagesEmptyText).visibility = if (live.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View>(R.id.historyEmptyText).visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View>(R.id.sandboxEmptyText).visibility = if (sandbox.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showCreateSessionSheet() {
        val sheet = com.example.myapplication.ui.CreateSessionBottomSheet { newSession ->
            lifecycleScope.launch(Dispatchers.IO) {
                KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao().insertSession(newSession)
                loadInlineSections()
            }
        }
        sheet.show(supportFragmentManager, "CreateSessionBottomSheet")
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        if (::liveAdapter.isInitialized) loadInlineSections()
    }

    // ── Safe Browsing API test ────────────────────────────────────────────────

    /**
     * Hits Google's guaranteed-malicious test domain: testsafebrowsing.appspot.com
     * Expected response: verdict=MALICIOUS, confidence≈0.9, source=safe_browsing
     *
     * If you see UNVERIFIED → your API key in local.properties is missing or wrong.
     * If you see a network error → no internet, or the app lacks INTERNET permission.
     */
    private fun runSafeBrowsingTest() {
        val apiKey = BuildConfig.SAFE_BROWSING_API_KEY
        if (apiKey.isBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("❌ API Key Missing")
                .setMessage(
                    "SAFE_BROWSING_API_KEY is not set in local.properties.\n\n" +
                    "1. Go to console.cloud.google.com\n" +
                    "2. Enable the Safe Browsing API\n" +
                    "3. Create an API key\n" +
                    "4. Add it to local.properties:\n\n" +
                    "SAFE_BROWSING_API_KEY=your_key_here\n\n" +
                    "5. Rebuild the app"
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val testUrl = "http://testsafebrowsing.appspot.com/apiv4/ANY_PLATFORM/MALWARE/URL/"
        resultText.text = "⏳ Calling Safe Browsing API...\nTest URL: $testUrl"

        val factCheckService = FactCheckService(this)
        lifecycleScope.launch {
            try {
                // Google's Safe Browsing API requires the exact test URL path to trigger the MALICIOUS flag
                val result = factCheckService.checkDomainReputation(testUrl)

                val icon = when (result.verdict) {
                    "MALICIOUS" -> "🔴"
                    "SAFE"      -> "🟢"
                    else        -> "🟡"
                }

                val summary = buildString {
                    appendLine("$icon Verdict: ${result.verdict}")
                    appendLine("📊 Confidence: ${(result.confidence * 100).toInt()}%")
                    appendLine("🔍 Source: ${result.source}")
                    appendLine("🌐 Domain: ${result.indicatorValue}")
                }

                resultText.text = summary

                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Safe Browsing Test Result")
                    .setMessage(
                        "$summary\n" +
                        if (result.verdict == "MALICIOUS") {
                            "✅ API key is working correctly!\nThe test malicious domain was detected."
                        } else {
                            "⚠️ Expected MALICIOUS but got ${result.verdict}.\n" +
                            "Check your API key — it may be invalid or restricted."
                        }
                    )
                    .setPositiveButton("OK", null)
                    .show()

            } catch (e: Exception) {
                val msg = "❌ Test failed: ${e.message}"
                resultText.text = msg
                Log.e(TAG, "Safe Browsing test failed", e)
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Safe Browsing Test Error")
                    .setMessage("$msg\n\nMake sure the device has internet access.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── Diagnostic: Inject a fake SMS broadcast directly to our own receiver ──

    private fun injectTestSmsBroadcast() {
        Log.d(TAG, "=== INJECTING TEST SMS BROADCAST ===")
        resultText.text = "Injecting SMS broadcast...\nWatch Logcat for 'SmsReceiver' tag"

        // Send the intent directly to our specific receiver component.
        // This avoids any OS routing issues and goes straight to our class.
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            component = ComponentName(
                packageName,
                "com.example.myapplication.receiver.SmsReceiver"
            )
            // Note: we can't forge real SmsMessage PDUs from Java/Kotlin,
            // so this broadcast will arrive at SmsReceiver but getMessagesFromIntent()
            // will return empty — that's expected. We just want to confirm onReceive fires.
        }

        try {
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent! Now watch Logcat for SmsReceiver logs.")
            Toast.makeText(this, "Broadcast sent! Check Logcat for SmsReceiver tag.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast: ${e.message}", e)
            resultText.text = "Failed: ${e.message}"
        }
    }

    // ── Diagnostic: Directly start SmsProcessingService with a fake SMS ──────

    private fun directStartProcessingService() {
        Log.d(TAG, "=== DIRECTLY STARTING SmsProcessingService ===")
        resultText.text = "Starting service directly...\nWatch Logcat for 'SmsProcessingService'"

        val fakeSms = IncomingSms(
            sender = "+919876543210",
            body = "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-rbi.com/verify"
        )

        val serviceIntent = Intent(
            this,
            com.example.myapplication.service.SmsProcessingService::class.java
        ).apply {
            putExtra("sms", fakeSms)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "Service started directly. Watch Logcat for SmsProcessingService logs.")
            Toast.makeText(this, "Service started! Check Logcat + notifications.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}", e)
            resultText.text = "Failed to start service: ${e.message}"
        }
    }

    // ── Pipeline test (existing functionality) ────────────────────────────────

    private fun runPipelineTest() {
        lifecycleScope.launch {
            val output = StringBuilder()

            output.appendLine("=== PERMISSION STATUS ===")
            getRequiredPermissions().forEach { perm ->
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, perm) == PackageManager.PERMISSION_GRANTED
                output.appendLine("${if (granted) "✅" else "❌"} ${perm.substringAfterLast(".")}")
            }

            output.appendLine("\n=== FILTER TEST ===")
            val scamSms = IncomingSms("+919876543210",
                "Dear customer your KYC is expired. Account will be blocked. Update now: http://fake-rbi.com")
            output.appendLine("Scam from mobile → ${SmsPreFilter.shouldIgnore(scamSms.sender, scamSms.body)}")

            val otpSms = IncomingSms("VM-HDFCBK", "Your OTP is 123456. Valid for 10 minutes.")
            output.appendLine("OTP from DLT → ${SmsPreFilter.shouldIgnore(otpSms.sender, otpSms.body)}")

            output.appendLine("\n=== TRIGGER TEST ===")
            val preprocessed = SmsPreprocessor.process(scamSms)
            val triggerResult = TriggerEngine.evaluate(preprocessed)
            output.appendLine("shouldProcess: ${triggerResult.shouldProcess}")
            output.appendLine("triggers: ${triggerResult.firedTriggers}")
            output.appendLine("priority: ${triggerResult.priority}")

            resultText.text = output.toString()
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun requestAllPermissions() {
        val notGranted = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: ${notGranted.map { it.substringAfterLast(".") }}")
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERM_REQUEST_CODE)
        } else {
            Log.d(TAG, "All permissions already granted ✅")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            permissions.forEachIndexed { i, perm ->
                val granted = grantResults.getOrElse(i) { PackageManager.PERMISSION_DENIED } == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "${perm.substringAfterLast(".")}: ${if (granted) "GRANTED ✅" else "DENIED ❌"}")
            }
            updatePermissionStatus()
        }
    }

    private fun updatePermissionStatus() {
        val status = StringBuilder("Permission Status:\n")
        getRequiredPermissions().forEach { perm ->
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            status.appendLine("${if (granted) "✅" else "❌"} ${perm.substringAfterLast(".")}")
        }
        resultText.text = status.toString()
    }
}

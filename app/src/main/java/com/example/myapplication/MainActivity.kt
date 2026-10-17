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
import com.example.myapplication.onboarding.OnboardingActivity
import com.example.myapplication.ui.EvidenceActivity
import com.example.myapplication.ui.SessionListActivity
import com.example.myapplication.ui.SettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var scansAdapter: com.example.myapplication.ui.ScanSessionsAdapter

    private var currentHistoryFilter = "ALL"
    private var currentScansFilter = "ALL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themePrefs = getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            themePrefs.getInt("night_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )

        // ── Onboarding gate ─────────────────────────────────────────────────
        val onboardingPrefs = getSharedPreferences(
            OnboardingActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE
        )
        if (!onboardingPrefs.getBoolean(OnboardingActivity.KEY_COMPLETED, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        // ────────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_main)
        SystemBars.apply(this, findViewById(android.R.id.content))
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

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
                    R.id.nav_home -> { showPage(R.id.homeContent, "Honeypot"); true }
                    R.id.nav_messages -> {
                        showPage(R.id.messagesContent, "Flagged messages"); true
                    }
                    R.id.nav_history -> {
                        showPage(R.id.historyContent, "History"); true
                    }
                    R.id.nav_scans -> {
                        showPage(R.id.scansContent, "Scanned Messages"); true
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
            startActivity(Intent(this, com.example.myapplication.ui.SimulationActivity::class.java))
        }

        findViewById<View>(R.id.evidenceButton).setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_history
        }

        findViewById<View>(R.id.liveHoneypotButton).setOnClickListener {
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_messages
        }

        // ── Quick Actions ───────────────────────────────────────────────────
        findViewById<View>(R.id.chipScanMessage).setOnClickListener {
            startActivity(android.content.Intent(this, com.example.myapplication.manualcapture.CrossAppSettingsActivity::class.java))
        }

        findViewById<View>(R.id.chipViewThreats).setOnClickListener {
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_messages
        }

        findViewById<View>(R.id.chipFileReport).setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://cybercrime.gov.in/"))
            startActivity(intent)
        }

        findViewById<View>(R.id.statScamsCaught)?.setOnClickListener {
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_messages
        }
        findViewById<View>(R.id.statThreatsToday)?.setOnClickListener {
            findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_messages
        }

        // openMessagesButton and openHistoryButton removed in redesigned layout
        // Scans FAB and sandbox FAB are wired via the new ExtendedFAB IDs
        try { findViewById<View>(R.id.openSandboxButton)?.setOnClickListener { showCreateSessionSheet() } } catch (_: Exception) {}
        try { findViewById<View>(R.id.openScansButton)?.setOnClickListener { loadInlineSections() } } catch (_: Exception) {}

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

    private fun showPage(pageId: Int, title: String) {
        listOf(R.id.homeContent, R.id.messagesContent, R.id.historyContent, R.id.sandboxContent, R.id.scansContent).forEach { id ->
            findViewById<View>(id)?.visibility = if (id == pageId) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.customTitleText)?.text = title
        findViewById<TextView>(R.id.customSubtitleText)?.visibility = View.GONE
        applyEmptyStateBottomMargin()
    }

    private fun applyEmptyStateBottomMargin() {
        val navContainer = findViewById<View>(R.id.bottomNavContainer)
            ?: findViewById<View>(R.id.bottomNavigation)
            ?: return
        navContainer.post {
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val distance = (0.35f * screenHeight).toInt()
            val navHeight = navContainer.height.takeIf { it > 0 } ?: (70 * displayMetrics.density).toInt()
            val targetMargin = navHeight + distance

            val emptyViewIds = listOf(
                R.id.messagesEmptyState,
                R.id.historyEmptyState,
                R.id.sandboxEmptyState,
                R.id.scansEmptyState
            )

            emptyViewIds.forEach { id ->
                findViewById<View?>(id)?.let { view ->
                    val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@let
                    lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                    lp.bottomMargin = targetMargin
                    view.layoutParams = lp
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyEmptyStateBottomMargin()
    }

    private fun setupInlineSections() {
        liveAdapter = com.example.myapplication.ui.SessionAdapter(
            onClick = { session -> startActivity(Intent(this, com.example.myapplication.ui.HoneypotConversationActivity::class.java).apply { putExtra("EXTRA_SESSION_ID", session.id) }) },
            onLongClick = { session -> showInlineSessionOptions(session.id, session.name) }
        )
        sandboxAdapter = com.example.myapplication.ui.SessionAdapter(
            onClick = { session -> startActivity(Intent(this, com.example.myapplication.ui.SimulationActivity::class.java).apply { putExtra("EXTRA_SESSION_ID", session.id) }) },
            onLongClick = { session -> showInlineSessionOptions(session.id, session.name) }
        )
        historyAdapter = com.example.myapplication.ui.EvidenceSessionAdapter(
            onClick = { summary ->
                startActivity(Intent(this, com.example.myapplication.ui.EvidenceDetailActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", summary.sessionId)
                    putExtra("EXTRA_SOURCE_SENDER", summary.sourceSender)
                })
            },
            onLongClick = { summary -> confirmInlineDeleteEvidence(summary) }
        )
        scansAdapter = com.example.myapplication.ui.ScanSessionsAdapter(
            onClick = { session ->
                startActivity(com.example.myapplication.manualcapture.ScanDetailActivity.buildIntentForSession(this, session.id))
            }
        )
        // Threats tab (full list) — this is the only messagesRecyclerView now
        findViewById<RecyclerView>(R.id.messagesRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = liveAdapter }
        // Home tab recent-activity preview — separate ID, shares same adapter
        findViewById<RecyclerView?>(R.id.recentActivityRecyclerView)?.apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = liveAdapter }
        findViewById<RecyclerView>(R.id.sandboxRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = sandboxAdapter }
        findViewById<RecyclerView>(R.id.historyRecyclerView).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = historyAdapter }
        findViewById<RecyclerView?>(R.id.scansRecyclerView)?.apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = scansAdapter }

        // Setup Swipe Refresh
        findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.messagesContent).setOnRefreshListener { loadInlineSections() }
        findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.historySwipeRefresh).setOnRefreshListener { loadInlineSections() }

        setupHistoryFilters()
        setupScansFilters()
        loadInlineSections()
        applyEmptyStateBottomMargin()
    }

    private fun setupScansFilters() {
        // Will be populated dynamically in loadInlineSections
    }

    private fun setupHistoryFilters() {
        val all = findViewById<TextView>(R.id.filterAll)
        val high = findViewById<TextView>(R.id.filterHighRisk)
        val low = findViewById<TextView>(R.id.filterLowRisk)
        val reported = findViewById<TextView>(R.id.filterReported)
        val chips = listOf(all, high, low, reported)

        fun updateChips(active: TextView) {
            chips.forEach { chip ->
                chip.setBackgroundResource(if (chip == active) R.drawable.bg_chip_active else R.drawable.bg_chip_neutral)
                chip.setTextColor(getColor(if (chip == active) R.color.text_primary else R.color.text_secondary))
                if (chip == active) chip.setTypeface(null, android.graphics.Typeface.BOLD) else chip.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        all.setOnClickListener { currentHistoryFilter = "ALL"; updateChips(all); loadInlineSections() }
        high.setOnClickListener { currentHistoryFilter = "HIGH_RISK"; updateChips(high); loadInlineSections() }
        low.setOnClickListener { currentHistoryFilter = "LOW_RISK"; updateChips(low); loadInlineSections() }
        reported.setOnClickListener { currentHistoryFilter = "REPORTED"; updateChips(reported); loadInlineSections() }
    }

    private fun loadInlineSections() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao()
            val live = dao.getLiveSessions()
            val sandbox = dao.getSimulatedSessions()
            val scans = dao.getOverlayCaptureSessions()
            val history = dao.getEvidenceSessionSummaries()

            // ── Live App Stats ──────────────────────────────────────────────
            val inboxSmsCount = com.example.myapplication.util.AppStatsManager.getDeviceInboxSmsCount(this@MainActivity)
            com.example.myapplication.util.AppStatsManager.recordInboxBaseline(this@MainActivity, inboxSmsCount)
            val liveSmsProcessed = com.example.myapplication.util.AppStatsManager.getScannedSmsCount(this@MainActivity)
            val scannedSmsCount = maxOf(inboxSmsCount, liveSmsProcessed)
            val manualCapturesCount = dao.getManualCapturesCount()
            val sandboxMessagesCount = dao.getSandboxAttackerMessagesCount()
            val totalScanned = scannedSmsCount + manualCapturesCount + sandboxMessagesCount

            val scamCaptures = dao.getScamManualCaptureAnalyses()
            val scamAiLogs = dao.getAllScamAiLogs()
            val scamAiSessionIds = dao.getScamAiSessionIds()

            // Unique scams caught across all sources
            val scamSessionIds = mutableSetOf<String>()
            live.forEach { scamSessionIds.add(it.id) }
            history.forEach { scamSessionIds.add(it.sessionId) }
            scamCaptures.forEach { scamSessionIds.add(it.captureId) }
            scamAiSessionIds.forEach { scamSessionIds.add(it) }
            val scamsCaught = scamSessionIds.size

            // Threats today (since midnight today in device local time)
            val startOfDay = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val todayThreatIds = mutableSetOf<String>()
            live.filter { it.createdAt >= startOfDay }.forEach { todayThreatIds.add(it.id) }
            history.filter { it.lastTimestamp >= startOfDay }.forEach { todayThreatIds.add(it.sessionId) }
            scamCaptures.filter { it.completedAt >= startOfDay }.forEach { todayThreatIds.add(it.captureId) }
            val scamAiSessionIdsToday = dao.getScamAiSessionIdsSince(startOfDay)
            todayThreatIds.addAll(scamAiSessionIdsToday)

            val scamAiLogsTodayCount = scamAiLogs.count { it.timestamp >= startOfDay }
            val scamCapturesTodayCount = scamCaptures.count { it.completedAt >= startOfDay }
            val threatsToday = maxOf(todayThreatIds.size, scamAiLogsTodayCount + scamCapturesTodayCount)

            val filteredHistory = when (currentHistoryFilter) {
                "HIGH_RISK" -> history.filter { it.maxConfidence != null && it.maxConfidence >= 0.8f }
                "LOW_RISK" -> history.filter { it.maxConfidence == null || it.maxConfidence < 0.8f }
                "REPORTED" -> history.filter { it.reportedAt != null }
                else -> history
            }
            
            // Scans filtering
            val allSources = scans.map { com.example.myapplication.ui.UiUtil.formatSourceApp(it.serviceCenterAddress.removePrefix("manual:")) }.distinct().sorted()
            val filteredScans = if (currentScansFilter == "ALL") scans else scans.filter {
                val app = com.example.myapplication.ui.UiUtil.formatSourceApp(it.serviceCenterAddress.removePrefix("manual:"))
                if (currentScansFilter == "Other") {
                    app != "WhatsApp" && app != "Messages" && app != "Telegram"
                } else {
                    app.equals(currentScansFilter, ignoreCase = true)
                }
            }

            withContext(Dispatchers.Main) {
                // ── Hero Card Dynamic Stats ──────────────────────────────────
                findViewById<TextView?>(R.id.statMessagesScanned)?.text =
                    "$totalScanned ${if (totalScanned == 1) "message" else "messages"} scanned"
                val statScamsCaught = findViewById<TextView?>(R.id.statScamsCaught)
                val statThreatsToday = findViewById<TextView?>(R.id.statThreatsToday)
                val heroProtectionStatus = findViewById<TextView?>(R.id.heroProtectionStatus)

                statScamsCaught?.text = "$scamsCaught ${if (scamsCaught == 1) "scam" else "scams"} caught"
                statThreatsToday?.text = "$threatsToday ${if (threatsToday == 1) "threat" else "threats"} today"

                statScamsCaught?.setTextColor(getColor(if (scamsCaught > 0) R.color.accent_red else R.color.text_primary))
                statThreatsToday?.setTextColor(getColor(if (threatsToday > 0) R.color.accent_red else R.color.text_primary))

                val hasSmsPerm = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                heroProtectionStatus?.text = if (hasSmsPerm) "Protection Active" else "Setup Needed"
                heroProtectionStatus?.setTextColor(getColor(if (hasSmsPerm) R.color.accent_green else R.color.accent_amber))

                val scamSessionIdsWithScreenshot = dao.getActiveManualCaptures()
                    .filter { it.imagePath.isNotBlank() && java.io.File(it.imagePath).exists() }
                    .mapNotNull { it.analysisSessionId }
                    .toSet()

                scansAdapter.evidenceSessionIds = scamSessionIdsWithScreenshot

                liveAdapter.submitList(live)
                sandboxAdapter.submitList(sandbox)
                scansAdapter.submitList(filteredScans)
                historyAdapter.setItems(filteredHistory)
                findViewById<View?>(R.id.recentActivityEmptyText)?.visibility = if (live.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View?>(R.id.messagesEmptyState)?.visibility = if (live.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View?>(R.id.historyEmptyState)?.visibility = if (filteredHistory.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View?>(R.id.historyEmptyText)?.visibility = View.GONE
                findViewById<View?>(R.id.sandboxEmptyState)?.visibility = if (sandbox.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View?>(R.id.sandboxEmptyText)?.visibility = View.GONE
                findViewById<View?>(R.id.scansEmptyState)?.visibility = if (filteredScans.isEmpty()) View.VISIBLE else View.GONE
                
                // Build dynamic filter chips for Scans
                updateScansFilterUI(allSources)
                
                // Stop swipe refresh spinners if active
                findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.messagesContent).isRefreshing = false
                findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.historySwipeRefresh).isRefreshing = false
            }
        }
    }

    private fun updateScansFilterUI(sources: List<String>) {
        val container = findViewById<android.widget.LinearLayout>(R.id.scansFilterContainer)
        val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.scansFilterScroll)
        if (container == null || scroll == null) return

        if (sources.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        container.removeAllViews()

        val chips = mutableListOf<TextView>()

        fun addChip(label: String, filterValue: String) {
            val tv = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    0,
                    (16 * resources.displayMetrics.density).toInt(),
                    0
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * resources.displayMetrics.density).toInt()
                ).apply {
                    if (container.childCount > 0) marginStart = (8 * resources.displayMetrics.density).toInt()
                }
            }
            tv.setOnClickListener {
                currentScansFilter = filterValue
                loadInlineSections()
            }
            container.addView(tv)
            chips.add(tv)

            if (currentScansFilter == filterValue) {
                tv.setBackgroundResource(R.drawable.bg_chip_active)
                tv.setTextColor(getColor(R.color.text_primary))
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundResource(R.drawable.bg_chip_neutral)
                tv.setTextColor(getColor(R.color.text_secondary))
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        addChip("All", "ALL")
        if (sources.contains("WhatsApp")) addChip("WhatsApp", "WhatsApp")
        if (sources.contains("Messages")) addChip("Messages", "Messages")
        if (sources.contains("Telegram")) addChip("Telegram", "Telegram")

        val hasOther = sources.any { it != "WhatsApp" && it != "Messages" && it != "Telegram" }
        if (hasOther) addChip("Other", "Other")
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

    /** Long-press on an inline session row → archive or permanently delete. */
    private fun showInlineSessionOptions(sessionId: String, sessionName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(sessionName)
            .setItems(arrayOf("Archive", "Delete Permanently")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) {
                        KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao().archiveSession(sessionId)
                        withContext(Dispatchers.Main) { loadInlineSections() }
                    }
                    1 -> confirmInlineDelete(sessionId, sessionName)
                }
            }
            .show()
    }

    private fun confirmInlineDelete(sessionId: String, sessionName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("🗑️ Delete Session")
            .setMessage("Permanently delete ‘$sessionName’ and all its messages? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao()
                    dao.deleteAnalysisLogsForSession(sessionId)
                    dao.deleteEvidenceForSession(sessionId)
                    dao.deleteSimulation(sessionId)
                    dao.deleteSession(sessionId)
                    withContext(Dispatchers.Main) { loadInlineSections() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmInlineDeleteEvidence(summary: com.example.myapplication.knowledge.EvidenceSessionSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle("🗑️ Delete Evidence")
            .setMessage(
                "Permanently delete all captured evidence for ‘${summary.sourceSender}’?\n\n" +
                "This will also remove the associated session, messages and analysis logs. " +
                "This action cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = KnowledgeBaseDatabase.getInstance(this@MainActivity).knowledgeBaseDao()
                    dao.deleteAnalysisLogsForSession(summary.sessionId)
                    dao.deleteEvidenceForSession(summary.sessionId)
                    dao.deleteSimulation(summary.sessionId)
                    dao.deleteSession(summary.sessionId)
                    withContext(Dispatchers.Main) { loadInlineSections() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
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
            loadInlineSections()
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

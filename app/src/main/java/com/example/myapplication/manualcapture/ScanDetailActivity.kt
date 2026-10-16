package com.example.myapplication.manualcapture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCapture
import com.example.myapplication.manualcapture.model.ManualCaptureAnalysis
import com.example.myapplication.ui.SystemBars
import com.example.myapplication.ui.UiUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only scan session detail screen.
 *
 * Shows scanned message text, analysis results, and a safe reply to copy.
 * This is NOT a conversation — there is NO input bar, NO send button.
 *
 * Opened from the Scans tab when the user taps a scan session card.
 * Accepts either a captureId (single scan) or a sessionId (grouped scan).
 */
class ScanDetailActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var multiScanBanner: View
    private lateinit var tvScanCount: TextView
    private lateinit var tvViewAll: TextView
    private lateinit var layoutProgress: View
    private lateinit var scrollContent: View
    private lateinit var messagesContainer: LinearLayout
    private lateinit var sectionLabelAnalysis: View
    private lateinit var analysisCard: View
    private lateinit var tvScamTypeBadge: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var confidenceBarFill: View
    private lateinit var tvReasoning: TextView
    private lateinit var triggersChipRow: LinearLayout
    private lateinit var tvMultiScanNote: TextView
    private lateinit var sectionSafeReply: View
    private lateinit var safeReplyCard: View
    private lateinit var tvSafeReply: TextView
    private lateinit var bottomActionBar: View
    private lateinit var btnCopySafeReply: MaterialButton
    private lateinit var btnShareEvidence: MaterialButton

    private var captureId: String? = null
    private var sessionId: String? = null
    private var safeReplyText: String = ""
    private var sourceApp: String = "Screenshot"

    companion object {
        const val EXTRA_CAPTURE_ID = "extra_capture_id"
        const val EXTRA_SESSION_ID = "extra_session_id"

        fun buildIntent(context: Context, captureId: String): Intent =
            Intent(context, ScanDetailActivity::class.java).apply {
                putExtra(EXTRA_CAPTURE_ID, captureId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        fun buildIntentForSession(context: Context, sessionId: String): Intent =
            Intent(context, ScanDetailActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_detail)
        SystemBars.apply(this, findViewById(android.R.id.content))

        captureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)

        if (captureId == null && sessionId == null) {
            finish()
            return
        }

        bindViews()
        setupToolbar()
        loadData()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        multiScanBanner = findViewById(R.id.multiScanBanner)
        tvScanCount = findViewById(R.id.tvScanCount)
        tvViewAll = findViewById(R.id.tvViewAll)
        layoutProgress = findViewById(R.id.layoutProgress)
        scrollContent = findViewById(R.id.scrollContent)
        messagesContainer = findViewById(R.id.messagesContainer)
        sectionLabelAnalysis = findViewById(R.id.sectionLabelAnalysis)
        analysisCard = findViewById(R.id.analysisCard)
        tvScamTypeBadge = findViewById(R.id.tvScamTypeBadge)
        tvConfidence = findViewById(R.id.tvConfidence)
        confidenceBarFill = findViewById(R.id.confidenceBarFill)
        tvReasoning = findViewById(R.id.tvReasoning)
        triggersChipRow = findViewById(R.id.triggersChipRow)
        tvMultiScanNote = findViewById(R.id.tvMultiScanNote)
        sectionSafeReply = findViewById(R.id.sectionSafeReply)
        safeReplyCard = findViewById(R.id.safeReplyCard)
        tvSafeReply = findViewById(R.id.tvSafeReply)
        bottomActionBar = findViewById(R.id.bottomActionBar)
        btnCopySafeReply = findViewById(R.id.btnCopySafeReply)
        btnShareEvidence = findViewById(R.id.btnShareEvidence)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun showProgress() {
        layoutProgress.visibility = View.VISIBLE
        scrollContent.visibility = View.GONE
        bottomActionBar.visibility = View.GONE
    }

    private fun showContent() {
        layoutProgress.visibility = View.GONE
        scrollContent.visibility = View.VISIBLE
    }

    private fun loadData() {
        showProgress()
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@ScanDetailActivity)
            val dao = db.knowledgeBaseDao()

            // Resolve captureId and sessionId
            val resolvedCaptureId: String?
            val resolvedSessionId: String?

            if (captureId != null) {
                resolvedCaptureId = captureId
                val capture = dao.getManualCapture(captureId!!)
                resolvedSessionId = capture?.analysisSessionId
            } else {
                // Given a sessionId — find the capture for this session
                resolvedSessionId = sessionId
                val captures = dao.getAllManualCaptures()
                resolvedCaptureId = captures.firstOrNull { it.analysisSessionId == sessionId }?.id
            }

            if (resolvedCaptureId == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScanDetailActivity, "Scan data not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }

            val capture = dao.getManualCapture(resolvedCaptureId)
            val analysis = dao.getManualCaptureAnalysis(resolvedCaptureId)

            // Mark as viewed
            dao.markManualCaptureViewed(resolvedCaptureId, System.currentTimeMillis())
            val prefs = ManualCapturePrefs(this@ScanDetailActivity)
            if (prefs.unviewedCaptureId == resolvedCaptureId) {
                prefs.unviewedCaptureId = null
            }

            // Check if analysis needs to be run
            val needsAnalysis = capture?.status == "REVIEWED" || 
                (capture?.status == "COMPLETE" && analysis == null)

            val finalAnalysis: ManualCaptureAnalysis? = if (needsAnalysis && capture != null) {
                val repo = ManualAnalysisRepository(this@ScanDetailActivity)
                val result = repo.analyseCapture(resolvedCaptureId)
                if (result.isSuccess) result.getOrNull() else analysis
            } else {
                analysis
            }

            withContext(Dispatchers.Main) {
                if (capture == null) {
                    Toast.makeText(this@ScanDetailActivity, "Scan not found", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                populateUI(capture, finalAnalysis)
            }
        }
    }

    private fun populateUI(capture: ManualCapture, analysis: ManualCaptureAnalysis?) {
        sourceApp = UiUtil.formatSourceApp(capture.sourceApp)

        // App bar title / subtitle
        toolbar.title = "Scanned Message"
        toolbar.subtitle = "Via $sourceApp"

        // Build message card(s)
        messagesContainer.removeAllViews()
        addMessageCard(capture)

        // Analysis section
        if (analysis != null) {
            sectionLabelAnalysis.visibility = View.VISIBLE
            analysisCard.visibility = View.VISIBLE
            populateAnalysis(analysis)
        }

        // Safe reply
        val reply = analysis?.safeReply?.ifBlank { buildSafeReply(analysis) } ?: buildSafeReply(analysis)
        if (reply.isNotBlank()) {
            safeReplyText = reply
            tvSafeReply.text = reply
            sectionSafeReply.visibility = View.VISIBLE
            safeReplyCard.visibility = View.VISIBLE
            bottomActionBar.visibility = View.VISIBLE

            btnCopySafeReply.setOnClickListener { copyReplyAndSnackbar() }
            btnShareEvidence.setOnClickListener { shareEvidence(capture, analysis) }
        }

        showContent()
    }

    private fun addMessageCard(capture: ManualCapture) {
        val card = layoutInflater.inflate(R.layout.item_scan_message_card, messagesContainer, false)

        val tvSourceLabel = card.findViewById<TextView>(R.id.tvSourceLabel)
        val tvDateTime = card.findViewById<TextView>(R.id.tvDateTime)
        val tvMessageText = card.findViewById<TextView>(R.id.tvMessageText)
        val btnCopyMessage = card.findViewById<TextView>(R.id.btnCopyMessage)

        val friendlySource = UiUtil.formatSourceApp(capture.sourceApp)
        tvSourceLabel.text = "From $friendlySource"

        val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            .format(Date(capture.capturedAt))
        tvDateTime.text = dateStr

        val text = capture.selectedText.ifBlank { "No text captured" }
        tvMessageText.text = text
        tvMessageText.maxLines = 4

        // Show more / show less
        if (text.length > 200) {
            tvMessageText.maxLines = 4
            val showMoreBtn = card.findViewById<TextView?>(R.id.btnShowMore)
            showMoreBtn?.visibility = View.VISIBLE
            showMoreBtn?.setOnClickListener {
                if (tvMessageText.maxLines == 4) {
                    tvMessageText.maxLines = Int.MAX_VALUE
                    showMoreBtn.text = "Show less"
                } else {
                    tvMessageText.maxLines = 4
                    showMoreBtn.text = "Show more"
                }
            }
        }

        btnCopyMessage.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Scanned message", text))
            Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show()
        }

        messagesContainer.addView(card)
    }

    private fun populateAnalysis(analysis: ManualCaptureAnalysis) {
        val pct = (analysis.confidence * 100).toInt()
        tvConfidence.text = "$pct% confidence"

        // Scam type badge
        val scamTypeLabel = analysis.scamType
            .split("_").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifBlank { "Suspicious" }

        if (analysis.isScam) {
            tvScamTypeBadge.text = scamTypeLabel
            tvScamTypeBadge.setBackgroundResource(
                if (analysis.confidence >= 0.8f) R.drawable.bg_badge_red else R.drawable.bg_badge_amber
            )
            tvScamTypeBadge.setTextColor(
                if (analysis.confidence >= 0.8f) getColor(R.color.accent_red) else getColor(R.color.accent_amber)
            )
        } else {
            tvScamTypeBadge.text = "Low Risk"
            tvScamTypeBadge.setBackgroundResource(R.drawable.bg_badge_green)
            tvScamTypeBadge.setTextColor(getColor(R.color.accent_green))
        }

        // Confidence bar — show proportional fill by setting layout_width dynamically
        confidenceBarFill.post {
            val parent = confidenceBarFill.parent as? ViewGroup ?: return@post
            val totalWidth = parent.width
            val fillWidth = (totalWidth * analysis.confidence).toInt()
            val params = confidenceBarFill.layoutParams
            params.width = fillWidth.coerceAtLeast(8)
            confidenceBarFill.layoutParams = params

            val fillColor = when {
                analysis.confidence >= 0.8f -> getColor(R.color.accent_red)
                analysis.confidence >= 0.5f -> getColor(R.color.accent_amber)
                else -> getColor(R.color.accent_green)
            }
            confidenceBarFill.setBackgroundColor(fillColor)
        }

        // One-sentence reasoning — take first sentence
        val fullReasoning = analysis.reasoning
        val firstSentence = fullReasoning.split(Regex("[.!?]")).firstOrNull()?.trim() ?: fullReasoning
        tvReasoning.text = if (firstSentence.endsWith('.')) firstSentence else "$firstSentence."

        // Trigger chips
        triggersChipRow.removeAllViews()
        if (analysis.firedTriggers.isNotBlank()) {
            val triggers = analysis.firedTriggers.split(",").map { it.trim() }.filter { it.isNotBlank() }
            triggers.forEach { raw ->
                val chip = Chip(this).apply {
                    text = UiUtil.formatTriggerName(raw)
                    isClickable = false
                    isCheckable = false
                    textSize = 12f
                    setChipBackgroundColorResource(R.color.bg_elevated)
                    setTextColor(getColor(R.color.text_secondary))
                    chipStrokeWidth = 0f
                }
                triggersChipRow.addView(chip)
            }
        }
    }

    private fun buildSafeReply(analysis: ManualCaptureAnalysis?): String {
        if (analysis == null || !analysis.isScam) return ""
        return com.example.myapplication.llm.CloudReplyGenerator.fallbackSafeReply(analysis.scamType)
    }

    private fun copyReplyAndSnackbar() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Safe Reply", safeReplyText))
        Snackbar.make(
            findViewById(android.R.id.content),
            "Copied — switch to $sourceApp and paste your reply",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun shareEvidence(capture: ManualCapture, analysis: ManualCaptureAnalysis?) {
        val appLabel = UiUtil.formatSourceApp(capture.sourceApp)
        val pct = analysis?.let { (it.confidence * 100).toInt() } ?: 0
        val scamType = analysis?.scamType?.split("_")
            ?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: "Unknown"

        val shareText = buildString {
            appendLine("⚠️ SCAM EVIDENCE REPORT — Honeypot App")
            appendLine("Source: $appLabel")
            appendLine("Confidence: $pct%")
            appendLine("Type: $scamType")
            appendLine()
            appendLine("Scanned message:")
            appendLine(capture.selectedText)
            appendLine()
            appendLine("Generated by Honeypot Security App")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Scam Evidence Report")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Evidence Report"))
    }
}

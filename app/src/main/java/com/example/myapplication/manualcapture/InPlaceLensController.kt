package com.example.myapplication.manualcapture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCaptureAnalysis
import com.example.myapplication.manualcapture.model.OcrTextBlock
import com.example.myapplication.manualcapture.ocr.MessageSideClassifier
import com.example.myapplication.manualcapture.ocr.ScreenTextRecognizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the in-place Google Lens / Circle-to-Search style interactive overlay.
 *
 * Sits directly on top of the host chat app (WhatsApp, Telegram, Instagram) without
 * switching activities:
 * 1. Shows the captured screenshot 1:1 in [BlockOverlayView].
 * 2. Allows users to drag custom bounding boxes, tap blocks, and flip L/R orientation.
 * 3. On "Analyze", smoothly slides up an in-place Scam Analysis Card.
 * 4. Provides a one-tap "Copy Safe Reply" button that copies the AI advice and dismisses
 *    the overlay, returning the user instantly to their active conversation.
 */
class InPlaceLensController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val prefs by lazy { ManualCapturePrefs(context) }
    private val recognizer by lazy { ScreenTextRecognizer() }

    private var rootOverlayView: View? = null
    private var scope: CoroutineScope? = null

    private var currentCaptureId: String? = null
    private var currentBitmap: Bitmap? = null
    private var ocrBlocks = mutableListOf<OcrTextBlock>()
    private var currentSelectedText: String = ""
    private var onDismissCallback: (() -> Unit)? = null

    val isShowing: Boolean
        get() = rootOverlayView != null

    fun show(captureId: String, bitmap: Bitmap, onDismiss: () -> Unit) {
        dismiss() // Clear any existing overlay view

        currentCaptureId = captureId
        currentBitmap = bitmap
        onDismissCallback = onDismiss
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val themedContext = ContextThemeWrapper(context, R.style.Theme_MyApplication)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_in_place_lens, null)
        rootOverlayView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        setupViews(view, captureId, bitmap)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add in-place lens overlay to WindowManager", e)
            dismiss()
            return
        }

        // Run OCR on the bitmap
        runOcr(bitmap)
    }

    private fun setupViews(view: View, captureId: String, bitmap: Bitmap) {
        val blockOverlayView = view.findViewById<BlockOverlayView>(R.id.blockOverlayView)
        val topBar = view.findViewById<View>(R.id.topBar)
        val bottomDock = view.findViewById<View>(R.id.bottomDock)
        val btnCloseLens = view.findViewById<ImageButton>(R.id.btnCloseLens)
        val tvBoxHint = view.findViewById<TextView>(R.id.tvBoxHint)
        val btnClearBoxes = view.findViewById<MaterialButton>(R.id.btnClearBoxes)
        val btnAutoSelectIncoming = view.findViewById<MaterialButton>(R.id.btnAutoSelectIncoming)
        val btnSelectAll = view.findViewById<MaterialButton>(R.id.btnSelectAll)

        val fabViewText = view.findViewById<MaterialButton>(R.id.fabViewText)
        val btnAnalyse = view.findViewById<MaterialButton>(R.id.btnAnalyse)

        val cardSelectedText = view.findViewById<MaterialCardView>(R.id.cardSelectedText)
        val tvSelectedTextPreview = view.findViewById<TextView>(R.id.tvSelectedTextPreview)
        val btnCloseSelectedText = view.findViewById<ImageButton>(R.id.btnCloseSelectedText)

        val cardAnalysis = view.findViewById<MaterialCardView>(R.id.cardAnalysis)

        // Set bitmap immediately into the overlay
        blockOverlayView.setImageBitmap(bitmap)

        // Handle Back key on overlay
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (cardAnalysis.visibility == View.VISIBLE) {
                    dismiss()
                    true
                } else if (cardSelectedText.visibility == View.VISIBLE) {
                    cardSelectedText.visibility = View.GONE
                    true
                } else {
                    dismiss()
                    true
                }
            } else {
                false
            }
        }

        // Close lens button
        btnCloseLens.setOnClickListener {
            dismiss()
        }

        // Top bar box management
        btnClearBoxes.setOnClickListener {
            blockOverlayView.clearCustomBoxes()
        }
        btnAutoSelectIncoming.setOnClickListener {
            blockOverlayView.selectAllIncoming()
        }
        btnSelectAll.setOnClickListener {
            blockOverlayView.selectAll()
        }

        blockOverlayView.onBoxesChanged = { count ->
            if (count > 0) {
                tvBoxHint.text = "$count custom selection box${if (count > 1) "es" else ""}"
                btnClearBoxes.visibility = View.VISIBLE
            } else {
                tvBoxHint.text = "Drag boxes or tap text to select"
                btnClearBoxes.visibility = View.GONE
            }
        }

        blockOverlayView.onSelectionChanged = {
            refreshSelection(view)
        }


        // View Text card toggle
        fabViewText.setOnClickListener {
            val isVisible = cardSelectedText.visibility == View.VISIBLE
            if (isVisible) {
                cardSelectedText.visibility = View.GONE
            } else {
                tvSelectedTextPreview.text = currentSelectedText.ifBlank { "No text selected" }
                cardSelectedText.visibility = View.VISIBLE
            }
        }
        btnCloseSelectedText.setOnClickListener {
            cardSelectedText.visibility = View.GONE
        }

        // Analyze button
        btnAnalyse.setOnClickListener {
            val text = currentSelectedText.trim()
            if (text.isBlank()) {
                Toast.makeText(context, "Please select at least one message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startInPlaceAnalysis(view, captureId, text)
        }
    }

    private fun runOcr(bitmap: Bitmap) {
        val view = rootOverlayView ?: return
        val blockOverlayView = view.findViewById<BlockOverlayView>(R.id.blockOverlayView)
        val tvBoxHint = view.findViewById<TextView>(R.id.tvBoxHint)

        tvBoxHint.text = "Detecting messages…"

        scope?.launch {
            val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)
            val blocks = withContext(Dispatchers.Default) {
                recognizer.recognise(bitmap, classifier)
            }
            ocrBlocks = blocks.toMutableList()
            blockOverlayView.setBlocks(ocrBlocks, bitmap.width.toFloat())
            refreshSelection(view)
        }
    }

    private fun refreshSelection(view: View) {
        currentSelectedText = ocrBlocks
            .filter { it.isSelected }
            .sortedBy { it.bounds.top }
            .joinToString("\n") { it.text }

        val btnAnalyse = view.findViewById<MaterialButton>(R.id.btnAnalyse)
        val fabViewText = view.findViewById<MaterialButton>(R.id.fabViewText)
        val tvBoxHint = view.findViewById<TextView>(R.id.tvBoxHint)
        val blockOverlayView = view.findViewById<BlockOverlayView>(R.id.blockOverlayView)

        val selectedCount = ocrBlocks.count { it.isSelected }
        btnAnalyse.isEnabled = currentSelectedText.isNotBlank()
        fabViewText.text = if (selectedCount > 0) "View Text ($selectedCount)" else "View Text"

        if (blockOverlayView.getCustomBoxCount() == 0) {
            tvBoxHint.text = if (selectedCount > 0)
                "$selectedCount block${if (selectedCount > 1) "s" else ""} selected"
            else
                "Drag boxes or tap text to select"
        }
    }

    private fun startInPlaceAnalysis(view: View, captureId: String, text: String) {
        val topBar = view.findViewById<View>(R.id.topBar)
        val bottomDock = view.findViewById<View>(R.id.bottomDock)
        val cardSelectedText = view.findViewById<View>(R.id.cardSelectedText)
        val cardAnalysis = view.findViewById<MaterialCardView>(R.id.cardAnalysis)
        val layoutProgress = view.findViewById<View>(R.id.layoutAnalysisProgress)
        val layoutContent = view.findViewById<View>(R.id.layoutAnalysisContent)

        // Hide floating controls and preview card
        topBar.visibility = View.GONE
        bottomDock.visibility = View.GONE
        cardSelectedText.visibility = View.GONE

        // Show analysis card with loading spinner
        cardAnalysis.visibility = View.VISIBLE
        layoutProgress.visibility = View.VISIBLE
        layoutContent.visibility = View.GONE

        scope?.launch {
            try {
                // 1. Save reviewed text & layout to Database
                withContext(Dispatchers.IO) {
                    val db = KnowledgeBaseDatabase.getInstance(context)
                    db.knowledgeBaseDao().updateManualCaptureReview(captureId, text, prefs.senderLayout)
                }

                // 2. Run Scam Detection Engine
                val repo = ManualAnalysisRepository(context)
                val result = repo.analyseCapture(captureId)

                if (result.isSuccess) {
                    val analysis = result.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (analysis != null) {
                            showAnalysisResult(view, analysis)
                        } else {
                            showAnalysisError(view, "No analysis data produced")
                        }
                    }
                } else {
                    val errMsg = result.exceptionOrNull()?.message ?: "Analysis failed"
                    withContext(Dispatchers.Main) {
                        showAnalysisError(view, errMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running in-place analysis", e)
                withContext(Dispatchers.Main) {
                    showAnalysisError(view, e.message ?: "Analysis failed")
                }
            }
        }
    }

    private fun showAnalysisResult(view: View, analysis: ManualCaptureAnalysis) {
        val layoutProgress = view.findViewById<View>(R.id.layoutAnalysisProgress)
        val layoutContent = view.findViewById<View>(R.id.layoutAnalysisContent)
        val tvRiskBadge = view.findViewById<TextView>(R.id.tvRiskBadge)
        val tvConfidence = view.findViewById<TextView>(R.id.tvConfidence)
        val tvScamType = view.findViewById<TextView>(R.id.tvScamType)
        val tvReasoning = view.findViewById<TextView>(R.id.tvReasoning)
        val tvTriggers = view.findViewById<TextView>(R.id.tvTriggers)
        val tvSafeReply = view.findViewById<TextView>(R.id.tvSafeReply)
        val btnCopyReply = view.findViewById<MaterialButton>(R.id.btnCopyReply)
        val btnCloseAnalysis = view.findViewById<ImageButton>(R.id.btnCloseAnalysis)
        val btnDoneAnalysis = view.findViewById<MaterialButton>(R.id.btnDoneAnalysis)

        layoutProgress.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE

        val pct = (analysis.confidence * 100).toInt()
        tvConfidence.text = "$pct% Confidence"

        if (analysis.isScam) {
            if (analysis.confidence >= 0.75f) {
                tvRiskBadge.text = "⚠️ HIGH SCAM RISK"
                tvRiskBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt()) // Red
            } else {
                tvRiskBadge.text = "⚡ SUSPICIOUS"
                tvRiskBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt()) // Amber
            }
            tvRiskBadge.setTextColor(0xFFFFFFFF.toInt())
        } else {
            tvRiskBadge.text = "✓ SAFE / LOW RISK"
            tvRiskBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt()) // Emerald Green
            tvRiskBadge.setTextColor(0xFFFFFFFF.toInt())
        }

        tvScamType.text = "Category: ${analysis.scamType.ifBlank { "General Communication" }}"
        tvReasoning.text = analysis.reasoning

        if (analysis.firedTriggers.isNotBlank()) {
            tvTriggers.text = "Triggers: ${analysis.firedTriggers}"
            tvTriggers.visibility = View.VISIBLE
        } else {
            tvTriggers.visibility = View.GONE
        }

        // Contextual Recommended Safe Reply
        val safeReply = buildRecommendedReply(analysis)
        tvSafeReply.text = safeReply

        // One-tap copy & dismiss
        btnCopyReply.setOnClickListener {
            val clip = ClipData.newPlainText("Safe Reply", safeReply)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Safe reply copied to clipboard!", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        btnCloseAnalysis.setOnClickListener { dismiss() }
        btnDoneAnalysis.setOnClickListener { dismiss() }
    }

    private fun showAnalysisError(view: View, errorMsg: String) {
        val layoutProgress = view.findViewById<View>(R.id.layoutAnalysisProgress)
        val layoutContent = view.findViewById<View>(R.id.layoutAnalysisContent)
        val tvRiskBadge = view.findViewById<TextView>(R.id.tvRiskBadge)
        val tvConfidence = view.findViewById<TextView>(R.id.tvConfidence)
        val tvScamType = view.findViewById<TextView>(R.id.tvScamType)
        val tvReasoning = view.findViewById<TextView>(R.id.tvReasoning)
        val tvTriggers = view.findViewById<TextView>(R.id.tvTriggers)
        val btnCloseAnalysis = view.findViewById<ImageButton>(R.id.btnCloseAnalysis)
        val btnDoneAnalysis = view.findViewById<MaterialButton>(R.id.btnDoneAnalysis)

        layoutProgress.visibility = View.GONE
        layoutContent.visibility = View.VISIBLE

        tvRiskBadge.text = "ANALYSIS INCOMPLETE"
        tvRiskBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF64748B.toInt())
        tvConfidence.visibility = View.GONE
        tvScamType.text = "Could not verify scam status"
        tvReasoning.text = errorMsg
        tvTriggers.visibility = View.GONE

        btnCloseAnalysis.setOnClickListener { dismiss() }
        btnDoneAnalysis.setOnClickListener { dismiss() }
    }

    private fun buildRecommendedReply(analysis: ManualCaptureAnalysis): String {
        if (!analysis.isScam) {
            return "No suspicious patterns detected. Safe to continue normal conversation."
        }
        val lowerType = analysis.scamType.lowercase()
        return when {
            lowerType.contains("delivery") || lowerType.contains("courier") || lowerType.contains("package") ->
                "I do not open tracking links sent over chat. I verify all shipments directly via the official merchant app."
            lowerType.contains("bank") || lowerType.contains("financial") || lowerType.contains("payment") || lowerType.contains("upi") ->
                "I am verifying this transaction directly with my bank before taking any action. Do not send further payment requests."
            lowerType.contains("job") || lowerType.contains("task") || lowerType.contains("crypto") ->
                "I am not interested in unsolicited task or investment offers. Please remove me from your contact list."
            lowerType.contains("impersonat") || lowerType.contains("friend") || lowerType.contains("family") ->
                "Please call me directly or send a voice note so I can verify who you are before proceeding."
            else ->
                "I do not click external links or share verification codes. Please contact me through verified official channels."
        }
    }

    fun dismiss() {
        scope?.cancel()
        scope = null

        rootOverlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view from WindowManager", e)
            }
        }
        rootOverlayView = null

        currentBitmap?.let {
            try {
                if (!it.isRecycled) it.recycle()
            } catch (_: Exception) {}
        }
        currentBitmap = null
        currentCaptureId = null
        ocrBlocks.clear()
        currentSelectedText = ""

        onDismissCallback?.invoke()
        onDismissCallback = null
    }

    companion object {
        private const val TAG = "InPlaceLensController"
    }
}

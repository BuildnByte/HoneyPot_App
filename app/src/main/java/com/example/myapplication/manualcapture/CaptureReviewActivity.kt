package com.example.myapplication.manualcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.BlockRole
import com.example.myapplication.manualcapture.model.MessageSide
import com.example.myapplication.manualcapture.model.OcrTextBlock
import com.example.myapplication.manualcapture.ocr.MessageSideClassifier
import com.example.myapplication.manualcapture.ocr.ScreenTextRecognizer
import com.example.myapplication.ui.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Review activity shown after a successful screenshot capture.
 *
 * Features:
 * - Displays the screenshot with semi-transparent bounding boxes overlaid on OCR blocks.
 * - Pre-selects INCOMING message blocks; right/system blocks use muted outlines.
 * - Tapping a block cycles its classification: INCOMING → OUTGOING → SYSTEM_OR_UNKNOWN.
 * - Editable text preview shows only selected INCOMING blocks (top-to-bottom order).
 * - Source-app selector (WhatsApp / Telegram / Instagram / Other).
 * - "Analyse selected text" enabled only when preview is non-blank.
 * - Cancel/discard deletes the screenshot file and the draft DB row.
 */
class CaptureReviewActivity : AppCompatActivity() {

    private lateinit var overlayView: BlockOverlayView
    private lateinit var editPreview: EditText
    private lateinit var btnAnalyse: Button
    private lateinit var btnDiscard: Button
    private lateinit var layoutGroup: RadioGroup
    private lateinit var sourceChipGroup: ChipGroup
    private lateinit var tvOcrStatus: TextView

    private val prefs by lazy { ManualCapturePrefs(this) }
    private val recognizer = ScreenTextRecognizer()

    private var captureId: String = ""
    private var imagePath: String = ""
    private var capturedBitmap: Bitmap? = null
    private var ocrBlocks: MutableList<OcrTextBlock> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_review)
        SystemBars.apply(this, findViewById(android.R.id.content))

        captureId = intent.getStringExtra(EXTRA_CAPTURE_ID) ?: run { finish(); return }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { confirmDiscard() }

        overlayView = findViewById(R.id.blockOverlayView)
        editPreview = findViewById(R.id.editSelectedText)
        btnAnalyse = findViewById(R.id.btnAnalyse)
        btnDiscard = findViewById(R.id.btnDiscard)
        layoutGroup = findViewById(R.id.layoutRadioGroup)
        sourceChipGroup = findViewById(R.id.sourceChipGroup)
        tvOcrStatus = findViewById(R.id.tvOcrStatus)

        btnDiscard.setOnClickListener { confirmDiscard() }
        btnAnalyse.setOnClickListener { startAnalysis() }
        btnAnalyse.isEnabled = false

        // Handle back press the modern way (avoids deprecated onBackPressed override)
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmDiscard()
                }
            }
        )

        // Layout preference
        layoutGroup.check(
            if (prefs.senderLayout == ManualCapturePrefs.LAYOUT_LEFT_IS_MINE)
                R.id.radioLeftIsMine else R.id.radioRightIsMine
        )
        layoutGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.senderLayout = if (checkedId == R.id.radioLeftIsMine)
                ManualCapturePrefs.LAYOUT_LEFT_IS_MINE
            else ManualCapturePrefs.DEFAULT_LAYOUT
            // Re-classify blocks with new layout
            ocrBlocks.forEach { block ->
                val newSide = MessageSideClassifier(prefs.senderLayout)
                    .classifyBlock(block.bounds, capturedBitmap?.width?.toFloat() ?: 1f)
                block.isSelected = (newSide == MessageSide.INCOMING && block.role == BlockRole.MESSAGE)
                // Update side too
            }
            overlayView.setBlocks(ocrBlocks, capturedBitmap?.width?.toFloat() ?: 1f)
            refreshPreview()
        }

        loadCaptureAndRunOcr()
    }

    private fun loadCaptureAndRunOcr() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CaptureReviewActivity)
            val capture = db.knowledgeBaseDao().getManualCapture(captureId) ?: run {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }
            imagePath = capture.imagePath

            val bitmap = BitmapFactory.decodeFile(imagePath)
            withContext(Dispatchers.Main) {
                when (capture.sourceApp) {
                    "WhatsApp" -> sourceChipGroup.check(R.id.chipWhatsApp)
                    "Telegram" -> sourceChipGroup.check(R.id.chipTelegram)
                    "Instagram" -> sourceChipGroup.check(R.id.chipInstagram)
                    else -> sourceChipGroup.check(R.id.chipOther)
                }
                capturedBitmap = bitmap
                overlayView.setImageBitmap(bitmap)
                tvOcrStatus.text = getString(R.string.ocr_running)
            }

            val classifier = MessageSideClassifier(prefs.senderLayout)
            val blocks = withContext(Dispatchers.Default) {
                recognizer.recognise(bitmap, classifier)
            }

            withContext(Dispatchers.Main) {
                ocrBlocks = blocks.toMutableList()
                overlayView.setBlocks(ocrBlocks, bitmap.width.toFloat())
                overlayView.onBlockTapped = { block ->
                    // Cycle: INCOMING → OUTGOING → SYSTEM_OR_UNKNOWN
                    block.isSelected = when {
                        block.side == MessageSide.INCOMING && block.isSelected -> {
                            false
                        }
                        else -> block.side == MessageSide.INCOMING
                    }
                    // Cycle side classification on repeated taps
                    val newSide = when (block.side) {
                        MessageSide.INCOMING -> MessageSide.OUTGOING
                        MessageSide.OUTGOING -> MessageSide.SYSTEM_OR_UNKNOWN
                        MessageSide.SYSTEM_OR_UNKNOWN -> MessageSide.INCOMING
                    }
                    // Mutate the block object directly (it's in ocrBlocks)
                    val idx = ocrBlocks.indexOf(block)
                    if (idx >= 0) {
                        ocrBlocks[idx] = block.copy(
                            side = newSide,
                            isSelected = newSide == MessageSide.INCOMING && block.role == BlockRole.MESSAGE
                        )
                    }
                    overlayView.setBlocks(ocrBlocks, capturedBitmap?.width?.toFloat() ?: 1f)
                    refreshPreview()
                }

                tvOcrStatus.text = if (blocks.isEmpty()) getString(R.string.ocr_no_text)
                else getString(R.string.ocr_tap_hint)
                refreshPreview()
            }
        }
    }

    private fun refreshPreview() {
        val incoming = ocrBlocks
            .filter { it.isSelected && it.side == MessageSide.INCOMING }
            .sortedBy { it.bounds.top }
            .joinToString("\n") { it.text }
        editPreview.setText(incoming)
        btnAnalyse.isEnabled = incoming.isNotBlank()
    }

    private fun getSelectedSourceApp(): String {
        return when (sourceChipGroup.checkedChipId) {
            R.id.chipWhatsApp -> "WhatsApp"
            R.id.chipTelegram -> "Telegram"
            R.id.chipInstagram -> "Instagram"
            else -> "Other"
        }
    }

    private fun startAnalysis() {
        val selectedText = editPreview.text.toString().trim()
        if (selectedText.isBlank()) return

        val sourceApp = getSelectedSourceApp()

        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CaptureReviewActivity)
            db.knowledgeBaseDao().updateManualCaptureReview(captureId, selectedText, prefs.senderLayout)
            // Update source app too (full update)
            val capture = db.knowledgeBaseDao().getManualCapture(captureId)
            if (capture != null) {
                db.knowledgeBaseDao().updateManualCapture(capture.copy(sourceApp = sourceApp))
            }

            withContext(Dispatchers.Main) {
                val intent = ManualAnalysisActivity.buildIntent(
                    this@CaptureReviewActivity, captureId
                )
                startActivity(intent)
                finish()
            }
        }
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setPositiveButton(R.string.discard_confirm) { _, _ -> discardCapture() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun discardCapture() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CaptureReviewActivity)
            db.knowledgeBaseDao().updateManualCaptureStatus(captureId, "DISCARDED")
            // Delete the screenshot file
            if (imagePath.isNotBlank()) File(imagePath).delete()
            withContext(Dispatchers.Main) { finish() }
        }
    }

    override fun onDestroy() {
        recognizer.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAPTURE_ID = "extra_capture_id"

        fun buildIntent(context: Context, captureId: String) =
            android.content.Intent(context, CaptureReviewActivity::class.java).apply {
                putExtra(EXTRA_CAPTURE_ID, captureId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

/**
 * Custom view that draws the screenshot and overlays semi-transparent bounding boxes
 * for each OCR block. Tap-to-cycle is forwarded via [onBlockTapped].
 */
class BlockOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var blocks: List<OcrTextBlock> = emptyList()
    private var imageWidthPx: Float = 1f
    var onBlockTapped: ((OcrTextBlock) -> Unit)? = null

    private val incomingSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 180, 90)
        style = Paint.Style.FILL
    }
    private val incomingSelectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 200, 100)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val outgoingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 100, 100, 100)
        style = Paint.Style.FILL
    }
    private val outgoingStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 120, 120, 120)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val systemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 200, 200, 200)
        style = Paint.Style.FILL
    }

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun setBlocks(newBlocks: List<OcrTextBlock>, imgWidth: Float) {
        blocks = newBlocks
        imageWidthPx = imgWidth
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * scale) / 2
        val dy = (vh - bmp.height * scale) / 2

        canvas.drawBitmap(bmp, null,
            android.graphics.RectF(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale),
            null)

        for (block in blocks) {
            val r = block.bounds
            val scaledRect = android.graphics.RectF(
                dx + r.left * scale,
                dy + r.top * scale,
                dx + r.right * scale,
                dy + r.bottom * scale
            )
            when {
                block.isSelected -> {
                    canvas.drawRect(scaledRect, incomingSelectedPaint)
                    canvas.drawRect(scaledRect, incomingSelectedStrokePaint)
                }
                block.side == MessageSide.OUTGOING -> {
                    canvas.drawRect(scaledRect, outgoingPaint)
                    canvas.drawRect(scaledRect, outgoingStrokePaint)
                }
                else -> canvas.drawRect(scaledRect, systemPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val bmp = bitmap ?: return true

        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * scale) / 2
        val dy = (vh - bmp.height * scale) / 2

        // Convert touch to image coordinates
        val imgX = ((event.x - dx) / scale).toInt()
        val imgY = ((event.y - dy) / scale).toInt()

        val tapped = blocks.lastOrNull { it.bounds.contains(imgX, imgY) }
        if (tapped != null) {
            onBlockTapped?.invoke(tapped)
        }
        return true
    }
}

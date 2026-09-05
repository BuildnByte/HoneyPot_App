package com.example.myapplication.manualcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.OcrTextBlock
import com.example.myapplication.manualcapture.ocr.MessageSideClassifier
import com.example.myapplication.manualcapture.ocr.ScreenTextRecognizer
import com.example.myapplication.ui.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Review activity shown after a successful screenshot capture.
 *
 * Features:
 * - Displays the screenshot with Google Lens-style multi-bounding box selection in full screen.
 * - Floating orientation toggle button ("L | R") to quickly switch between sender sides.
 * - Floating "View Text" button that opens an editable bottom sheet modal.
 * - Floating "Analyse" button to trigger scam analysis directly.
 * - Quick-action tools bar to clear boxes, auto-select incoming, or select all text.
 */
class CaptureReviewActivity : AppCompatActivity() {

    private lateinit var overlayView: BlockOverlayView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvBoxHint: TextView
    private lateinit var btnClearBoxes: Button
    private lateinit var btnAutoSelectIncoming: Button
    private lateinit var btnSelectAll: Button
    private lateinit var fabOrientation: MaterialButton
    private lateinit var fabViewText: ExtendedFloatingActionButton
    private lateinit var btnAnalyse: ExtendedFloatingActionButton

    private val prefs by lazy { ManualCapturePrefs(this) }
    private val recognizer = ScreenTextRecognizer()

    private var captureId: String = ""
    private var imagePath: String = ""
    private var captureSourceApp: String = "Other"
    private var capturedBitmap: Bitmap? = null
    private var ocrBlocks: MutableList<OcrTextBlock> = mutableListOf()
    private var currentSelectedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_review)
        SystemBars.apply(this, findViewById(android.R.id.content))

        captureId = intent.getStringExtra(EXTRA_CAPTURE_ID) ?: run { finish(); return }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { confirmDiscard() }

        overlayView = findViewById(R.id.blockOverlayView)
        tvBoxHint = findViewById(R.id.tvBoxHint)
        btnClearBoxes = findViewById(R.id.btnClearBoxes)
        btnAutoSelectIncoming = findViewById(R.id.btnAutoSelectIncoming)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        fabOrientation = findViewById(R.id.fabOrientation)
        fabViewText = findViewById(R.id.fabViewText)
        btnAnalyse = findViewById(R.id.btnAnalyse)

        btnAnalyse.setOnClickListener { startAnalysis() }
        btnAnalyse.isEnabled = false

        // Bounding box toolbar button listeners
        btnClearBoxes.setOnClickListener {
            overlayView.clearCustomBoxes()
        }
        btnAutoSelectIncoming.setOnClickListener {
            overlayView.selectAllIncoming()
        }
        btnSelectAll.setOnClickListener {
            overlayView.selectAll()
        }

        // Floating orientation toggle (L | R)
        updateOrientationFab()
        fabOrientation.setOnClickListener {
            toggleOrientation()
        }

        // Floating view text button opens modal bottom sheet
        fabViewText.setOnClickListener {
            showSelectedTextBottomSheet()
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmDiscard()
                }
            }
        )

        loadCaptureAndRunOcr()
    }

    private fun updateOrientationFab() {
        val isLeft = prefs.senderLayout == ManualCapturePrefs.LAYOUT_LEFT_IS_MINE
        fabOrientation.text = if (isLeft) "L" else "R"
    }

    private fun toggleOrientation() {
        val isCurrentlyLeft = prefs.senderLayout == ManualCapturePrefs.LAYOUT_LEFT_IS_MINE
        prefs.senderLayout = if (isCurrentlyLeft) {
            ManualCapturePrefs.DEFAULT_LAYOUT
        } else {
            ManualCapturePrefs.LAYOUT_LEFT_IS_MINE
        }
        updateOrientationFab()

        val width = capturedBitmap?.width?.toFloat() ?: 1f
        val height = capturedBitmap?.height?.toFloat() ?: 1f
        val classifier = MessageSideClassifier(prefs.senderLayout)

        // Re-classify blocks with new layout orientation
        ocrBlocks.forEach { block ->
            val newSide = classifier.classifyBlock(block.bounds, width, height)
            val newRole = classifier.inferRole(block.text, block.bounds, width, height)
            val idx = ocrBlocks.indexOf(block)
            if (idx >= 0) {
                ocrBlocks[idx] = block.copy(
                    side = newSide,
                    role = newRole
                )
            }
        }
        overlayView.setBlocks(ocrBlocks, width)
        refreshPreview()
    }

    private fun loadCaptureAndRunOcr() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CaptureReviewActivity)
            val capture = db.knowledgeBaseDao().getManualCapture(captureId) ?: run {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }
            imagePath = capture.imagePath
            captureSourceApp = capture.sourceApp

            val bitmap = BitmapFactory.decodeFile(imagePath)
            withContext(Dispatchers.Main) {
                capturedBitmap = bitmap
                overlayView.setImageBitmap(bitmap)
                toolbar.subtitle = getString(R.string.ocr_running)
            }

            val classifier = MessageSideClassifier(prefs.senderLayout)
            val blocks = withContext(Dispatchers.Default) {
                recognizer.recognise(bitmap, classifier)
            }

            withContext(Dispatchers.Main) {
                ocrBlocks = blocks.toMutableList()
                overlayView.setBlocks(ocrBlocks, bitmap.width.toFloat())

                overlayView.onSelectionChanged = {
                    refreshPreview()
                }

                overlayView.onBoxesChanged = { count ->
                    updateBoxToolbar(count)
                }

                overlayView.onBlockTapped = {
                    refreshPreview()
                }

                updateBoxToolbar(0)

                toolbar.subtitle = if (blocks.isEmpty()) getString(R.string.ocr_no_text)
                else getString(R.string.ocr_tap_hint)
                refreshPreview()
            }
        }
    }

    private fun updateBoxToolbar(boxCount: Int) {
        if (boxCount > 0) {
            tvBoxHint.text = getString(R.string.box_hint_count, boxCount)
            btnClearBoxes.visibility = View.VISIBLE
        } else {
            tvBoxHint.text = getString(R.string.box_hint_zero)
            btnClearBoxes.visibility = View.GONE
        }
    }

    private fun refreshPreview() {
        // Collect all currently selected text blocks in top-to-bottom reading order
        currentSelectedText = ocrBlocks
            .filter { it.isSelected }
            .sortedBy { it.bounds.top }
            .joinToString("\n") { it.text }

        btnAnalyse.isEnabled = currentSelectedText.isNotBlank()
        updateViewTextFab()
    }

    private fun updateViewTextFab() {
        val count = ocrBlocks.count { it.isSelected }
        fabViewText.text = if (count > 0) "View Text ($count)" else "View Text"
    }

    private fun showSelectedTextBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_selected_text, null)
        dialog.setContentView(sheetView)

        val tvSheetText = sheetView.findViewById<TextView>(R.id.tvSelectedText)
        val btnClose = sheetView.findViewById<Button>(R.id.btnSheetClose)
        val btnSheetAnalyse = sheetView.findViewById<Button>(R.id.btnSheetAnalyse)

        tvSheetText.text = currentSelectedText.ifBlank { getString(R.string.ocr_no_text) }
        btnSheetAnalyse.isEnabled = currentSelectedText.isNotBlank()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnSheetAnalyse.setOnClickListener {
            dialog.dismiss()
            startAnalysis()
        }

        dialog.show()
    }

    private fun startAnalysis() {
        val selectedText = currentSelectedText.trim()
        if (selectedText.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CaptureReviewActivity)
            db.knowledgeBaseDao().updateManualCaptureReview(captureId, selectedText, prefs.senderLayout)

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

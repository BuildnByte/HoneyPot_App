package com.example.myapplication.manualcapture

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCapture
import com.example.myapplication.manualcapture.model.ManualCaptureAnalysis
import com.example.myapplication.ui.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the completed analysis result for a manual screen capture.
 *
 * When opened from the overlay, marks [viewedAt] on the capture row and
 * clears the unviewed badge in [ManualCapturePrefs].
 *
 * If an analysis is still running when this activity launches, it starts the
 * analysis via [ManualAnalysisRepository] and shows a loading state.
 */
class ManualAnalysisActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvScamType: TextView
    private lateinit var tvReasoning: TextView
    private lateinit var tvTriggers: TextView
    private lateinit var tvSelectedText: TextView
    private lateinit var tvSourceApp: TextView
    private lateinit var tvCaptureTime: TextView
    private lateinit var tvSha256: TextView
    private lateinit var ivThumbnail: ImageView
    private lateinit var btnDelete: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private lateinit var cardResult: View
    private lateinit var progressView: View
    private lateinit var chipScam: Chip

    private val prefs by lazy { ManualCapturePrefs(this) }
    private var captureId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_analysis)
        SystemBars.apply(this, findViewById(android.R.id.content))

        captureId = intent.getStringExtra(EXTRA_CAPTURE_ID) ?: run { finish(); return }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        tvStatus = findViewById(R.id.tvStatus)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvScamType = findViewById(R.id.tvScamType)
        tvReasoning = findViewById(R.id.tvReasoning)
        tvTriggers = findViewById(R.id.tvTriggers)
        tvSelectedText = findViewById(R.id.tvSelectedText)
        tvSourceApp = findViewById(R.id.tvSourceApp)
        tvCaptureTime = findViewById(R.id.tvCaptureTime)
        tvSha256 = findViewById(R.id.tvSha256)
        ivThumbnail = findViewById(R.id.ivThumbnail)
        btnDelete = findViewById(R.id.btnDelete)
        btnHistory = findViewById(R.id.btnOpenHistory)
        cardResult = findViewById(R.id.cardResult)
        progressView = findViewById(R.id.layoutProgress)
        chipScam = findViewById(R.id.chipScamIndicator)

        btnDelete.setOnClickListener { confirmDelete() }
        btnHistory.setOnClickListener {
            val intent = Intent(this, com.example.myapplication.ui.EvidenceActivity::class.java)
            startActivity(intent)
        }

        loadOrStartAnalysis()
    }

    private fun loadOrStartAnalysis() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@ManualAnalysisActivity)
            val capture = db.knowledgeBaseDao().getManualCapture(captureId) ?: run {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            when (capture.status) {
                "COMPLETE" -> {
                    val analysis = db.knowledgeBaseDao().getManualCaptureAnalysis(captureId)
                    // Mark as viewed
                    db.knowledgeBaseDao().markManualCaptureViewed(captureId, System.currentTimeMillis())
                    prefs.unviewedCaptureId = null
                    withContext(Dispatchers.Main) {
                        showResult(capture, analysis)
                    }
                }
                "REVIEWED" -> {
                    withContext(Dispatchers.Main) { showProgress("Analysing…") }
                    val repo = ManualAnalysisRepository(this@ManualAnalysisActivity)
                    val result = repo.analyseCapture(captureId)
                    val updatedCapture = db.knowledgeBaseDao().getManualCapture(captureId)
                    val analysis = db.knowledgeBaseDao().getManualCaptureAnalysis(captureId)
                    db.knowledgeBaseDao().markManualCaptureViewed(captureId, System.currentTimeMillis())
                    prefs.unviewedCaptureId = null
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            showResult(updatedCapture ?: capture, analysis)
                        } else {
                            showError(result.exceptionOrNull()?.message ?: "Analysis failed")
                        }
                    }
                }
                "ANALYSING" -> {
                    withContext(Dispatchers.Main) { showProgress("Analysing in progress…") }
                    // Poll briefly then try to show result
                    kotlinx.coroutines.delay(3000)
                    val updated = db.knowledgeBaseDao().getManualCapture(captureId)
                    val analysis = db.knowledgeBaseDao().getManualCaptureAnalysis(captureId)
                    db.knowledgeBaseDao().markManualCaptureViewed(captureId, System.currentTimeMillis())
                    prefs.unviewedCaptureId = null
                    withContext(Dispatchers.Main) {
                        showResult(updated ?: capture, analysis)
                    }
                }
                "FAILED" -> {
                    withContext(Dispatchers.Main) {
                        showError(capture.errorMessage ?: "Analysis failed")
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        showError("Capture is not ready for analysis (status: ${capture.status})")
                    }
                }
            }
        }
    }

    private fun showProgress(msg: String) {
        progressView.visibility = View.VISIBLE
        cardResult.visibility = View.GONE
        val tv = progressView.findViewById<TextView?>(R.id.tvProgressLabel)
        tv?.text = msg
    }

    private fun showError(msg: String) {
        progressView.visibility = View.GONE
        cardResult.visibility = View.VISIBLE
        tvStatus.text = "Analysis failed"
        tvReasoning.text = msg
        chipScam.visibility = View.GONE
        tvConfidence.visibility = View.GONE
        tvScamType.visibility = View.GONE
        tvTriggers.visibility = View.GONE
    }

    private fun showResult(capture: ManualCapture, analysis: ManualCaptureAnalysis?) {
        progressView.visibility = View.GONE
        cardResult.visibility = View.VISIBLE

        // Thumbnail
        if (capture.imagePath.isNotBlank() && File(capture.imagePath).exists()) {
            val bmp = BitmapFactory.decodeFile(capture.imagePath)
            if (bmp != null) ivThumbnail.setImageBitmap(bmp)
        }

        // Source + timestamp
        tvSourceApp.text = capture.sourceApp
        tvCaptureTime.text = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(capture.capturedAt))
        tvSha256.text = "SHA-256: ${capture.imageSha256.take(24)}…"
        tvSelectedText.text = capture.selectedText

        if (analysis == null) {
            tvStatus.text = "No analysis available"
            chipScam.visibility = View.GONE
            tvConfidence.visibility = View.GONE
            tvScamType.visibility = View.GONE
            tvReasoning.text = "Analysis result was not found."
            tvTriggers.visibility = View.GONE
            return
        }

        if (analysis.isScam) {
            tvStatus.text = "⚠️ Likely scam"
            chipScam.text = "Scam"
            chipScam.chipBackgroundColor = getColorStateList(R.color.danger)
            chipScam.setTextColor(getColor(android.R.color.white))
        } else {
            tvStatus.text = "✓ No strong scam signal"
            chipScam.text = "Low risk"
            chipScam.chipBackgroundColor = getColorStateList(R.color.success)
            chipScam.setTextColor(getColor(android.R.color.white))
        }
        chipScam.visibility = View.VISIBLE

        val pct = (analysis.confidence * 100).toInt()
        tvConfidence.text = "Confidence: $pct%"
        tvConfidence.visibility = View.VISIBLE

        tvScamType.text = "Type: ${analysis.scamType}"
        tvScamType.visibility = View.VISIBLE

        tvReasoning.text = analysis.reasoning

        if (analysis.firedTriggers.isNotBlank()) {
            tvTriggers.text = "Triggers: ${analysis.firedTriggers}"
            tvTriggers.visibility = View.VISIBLE
        } else {
            tvTriggers.visibility = View.GONE
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete capture")
            .setMessage("This will permanently delete the screenshot and all associated analysis data. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteCapture() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteCapture() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@ManualAnalysisActivity)
            val capture = db.knowledgeBaseDao().getManualCapture(captureId)

            // Delete analysis first (FK-like cleanup)
            db.knowledgeBaseDao().deleteManualCaptureAnalysis(captureId)

            // If a session was created, archive it
            if (capture?.analysisSessionId != null) {
                db.knowledgeBaseDao().archiveSession(capture.analysisSessionId)
            }

            db.knowledgeBaseDao().deleteManualCapture(captureId)

            // Delete the image file
            if (capture?.imagePath?.isNotBlank() == true) {
                val deleted = File(capture.imagePath).delete()
                Log.d("ManualAnalysisActivity", "Image deleted: $deleted")
            }

            if (captureId == prefs.unviewedCaptureId) {
                prefs.unviewedCaptureId = null
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManualAnalysisActivity, "Capture deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_CAPTURE_ID = "extra_capture_id"
        private const val TAG = "ManualAnalysisActivity"

        fun buildIntent(context: Context, captureId: String): Intent =
            Intent(context, ManualAnalysisActivity::class.java).apply {
                putExtra(EXTRA_CAPTURE_ID, captureId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

package com.example.myapplication.manualcapture

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.ui.EvidenceActivity
import com.example.myapplication.ui.SystemBars
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings screen for the Cross-app scanning feature.
 *
 * Provides a clean overview of scan overlay accessibility status with direct
 * navigation to Android Accessibility Settings, clear steps to disable/enable,
 * and access to saved captures management.
 */
class CrossAppSettingsActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnManageCaptures: MaterialButton
    private lateinit var btnClearCaptures: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crossapp_settings)
        SystemBars.apply(this, findViewById(android.R.id.content))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnManageCaptures = findViewById(R.id.btnManageCaptures)
        btnClearCaptures = findViewById(R.id.btnClearCaptures)

        // Redirect to Accessibility Settings on click
        btnOpenAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "Tap 'Downloaded apps' \u2794 'Honeypot' to configure",
                Toast.LENGTH_LONG
            ).show()
            startActivity(HoneypotAccessibilityService.buildSettingsIntent(this))
        }

        // Manage saved captures (view all captured evidence in EvidenceActivity)
        btnManageCaptures.setOnClickListener {
            startActivity(Intent(this, EvidenceActivity::class.java))
        }

        // Clear all manual-capture evidence
        btnClearCaptures.setOnClickListener {
            confirmClearAll()
        }

        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val active = HoneypotAccessibilityService.isServiceRunning
        if (active) {
            tvAccessibilityStatus.text = "Status: Active — Honeypot overlay service is enabled"
            btnOpenAccessibility.text = "Open Accessibility Settings (Active)"
        } else {
            tvAccessibilityStatus.text = "Status: Disabled — Accessibility permission required for overlay"
            btnOpenAccessibility.text = "Open Accessibility Settings"
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all capture evidence?")
            .setMessage("This will permanently delete all screenshots and associated analysis data. This cannot be undone.")
            .setPositiveButton("Clear all") { _, _ -> clearAllCaptures() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllCaptures() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = KnowledgeBaseDatabase.getInstance(this@CrossAppSettingsActivity)
            val captures = db.knowledgeBaseDao().getAllManualCaptures()
            for (c in captures) {
                db.knowledgeBaseDao().deleteManualCaptureAnalysis(c.id)
                if (c.imagePath.isNotBlank()) File(c.imagePath).delete()
                db.knowledgeBaseDao().deleteManualCapture(c.id)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@CrossAppSettingsActivity,
                    "${captures.size} capture(s) deleted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

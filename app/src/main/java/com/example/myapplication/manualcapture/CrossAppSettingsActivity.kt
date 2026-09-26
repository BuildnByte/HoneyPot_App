package com.example.myapplication.manualcapture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
 * one-tap addition/removal of the Quick Settings tile, and access to saved captures management.
 */
class CrossAppSettingsActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var tvTileStatus: TextView
    private lateinit var btnAddTile: MaterialButton
    private lateinit var btnRemoveTile: MaterialButton
    private lateinit var btnManageCaptures: MaterialButton
    private lateinit var btnClearCaptures: MaterialButton

    private val tileComponentName by lazy {
        ComponentName(this, ScanQuickSettingsTileService::class.java)
    }

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
        tvTileStatus = findViewById(R.id.tvTileStatus)
        btnAddTile = findViewById(R.id.btnAddTile)
        btnRemoveTile = findViewById(R.id.btnRemoveTile)
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

        // Add Quick Settings Tile directly without editing the shade
        btnAddTile.setOnClickListener {
            addQuickSettingsTile()
        }

        // Remove Quick Settings Tile
        btnRemoveTile.setOnClickListener {
            removeQuickSettingsTile()
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
        updateTileStatusUi()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateTileStatusUi()
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

    private fun updateTileStatusUi() {
        val state = packageManager.getComponentEnabledSetting(tileComponentName)
        val isEnabled = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (isEnabled) {
            tvTileStatus.text = "Tile is available. Tap 'Add tile' to place 'Scan message' directly in your Quick Settings."
            btnRemoveTile.isEnabled = true
        } else {
            tvTileStatus.text = "Tile is currently removed from Quick Settings. Tap 'Add tile' to restore it."
            btnRemoveTile.isEnabled = false
        }
    }

    private fun addQuickSettingsTile() {
        // 1. Ensure the tile component is enabled in PackageManager
        packageManager.setComponentEnabledSetting(
            tileComponentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        updateTileStatusUi()

        // 2. Request SystemUI to add the tile directly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager?.requestAddTileService(
                tileComponentName,
                "Scan message",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_scan),
                mainExecutor
            ) { result ->
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                        Toast.makeText(this, "Tile added to Quick Settings!", Toast.LENGTH_SHORT).show()
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                        Toast.makeText(this, "Tile is already in your Quick Settings!", Toast.LENGTH_SHORT).show()
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                        // User dismissed the system request
                    }
                }
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Add to Quick Settings")
                .setMessage(
                    "On your Android version, add the tile via the shade editor:\n\n" +
                    "1. Swipe down twice from the top of the screen.\n" +
                    "2. Tap the Pencil (Edit) icon.\n" +
                    "3. Find 'Scan message' and drag it into your active tiles."
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun removeQuickSettingsTile() {
        AlertDialog.Builder(this)
            .setTitle("Remove from Quick Settings?")
            .setMessage("This will disable and remove the 'Scan message' tile from your Quick Settings panel. You can add it back at any time.")
            .setPositiveButton("Remove") { _, _ ->
                // Turn off overlay if currently running
                val prefs = ManualCapturePrefs(this)
                prefs.overlayEnabled = false
                HoneypotAccessibilityService.hideOverlay()
                stopService(Intent(this, ScanOverlayService::class.java))
                try {
                    getSystemService(android.app.NotificationManager::class.java)?.cancel(9002)
                } catch (_: Exception) {}

                // Disable tile component so SystemUI removes it
                packageManager.setComponentEnabledSetting(
                    tileComponentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                updateTileStatusUi()
                Toast.makeText(this, "Tile removed from Quick Settings", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

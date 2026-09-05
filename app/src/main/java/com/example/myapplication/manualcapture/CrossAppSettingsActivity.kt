package com.example.myapplication.manualcapture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
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
 * Placed under the manualcapture package to keep the feature cohesive.
 * Linked from SettingsActivity.
 */
class CrossAppSettingsActivity : AppCompatActivity() {

    private val prefs by lazy { ManualCapturePrefs(this) }
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnToggleOverlay: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnToggleAccessibility: MaterialButton
    private lateinit var layoutGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crossapp_settings)
        SystemBars.apply(this, findViewById(android.R.id.content))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        layoutGroup = findViewById(R.id.settingsLayoutGroup)

        // Layout preference
        layoutGroup.check(
            if (prefs.senderLayout == ManualCapturePrefs.LAYOUT_LEFT_IS_MINE)
                R.id.settingsRadioLeftIsMine else R.id.settingsRadioRightIsMine
        )
        layoutGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.senderLayout = if (checkedId == R.id.settingsRadioLeftIsMine)
                ManualCapturePrefs.LAYOUT_LEFT_IS_MINE
            else ManualCapturePrefs.DEFAULT_LAYOUT
        }

        // Allowed apps preference
        val allowedAppsGroup = findViewById<RadioGroup>(R.id.settingsAllowedAppsGroup)
        val radioAllApps = findViewById<android.widget.RadioButton>(R.id.settingsRadioAllApps)
        val radioSelectedApps = findViewById<android.widget.RadioButton>(R.id.settingsRadioSelectedApps)
        val selectedAppsLayout = findViewById<android.widget.LinearLayout>(R.id.settingsSelectedAppsLayout)
        val cbWhatsApp = findViewById<android.widget.CheckBox>(R.id.settingsCbWhatsApp)
        val cbTelegram = findViewById<android.widget.CheckBox>(R.id.settingsCbTelegram)
        val cbInstagram = findViewById<android.widget.CheckBox>(R.id.settingsCbInstagram)

        val isAllApps = prefs.allowedAppsMode == ManualCapturePrefs.MODE_ALL_APPS
        if (isAllApps) {
            radioAllApps.isChecked = true
            selectedAppsLayout.visibility = android.view.View.GONE
        } else {
            radioSelectedApps.isChecked = true
            selectedAppsLayout.visibility = android.view.View.VISIBLE
        }

        val pkgs = prefs.allowedAppPackages
        cbWhatsApp.isChecked = pkgs.contains(ManualCapturePrefs.PKG_WHATSAPP)
        cbTelegram.isChecked = pkgs.contains(ManualCapturePrefs.PKG_TELEGRAM)
        cbInstagram.isChecked = pkgs.contains(ManualCapturePrefs.PKG_INSTAGRAM)

        fun saveSelectedPackages() {
            val updated = mutableSetOf<String>()
            if (cbWhatsApp.isChecked) updated.add(ManualCapturePrefs.PKG_WHATSAPP)
            if (cbTelegram.isChecked) updated.add(ManualCapturePrefs.PKG_TELEGRAM)
            if (cbInstagram.isChecked) updated.add(ManualCapturePrefs.PKG_INSTAGRAM)
            prefs.allowedAppPackages = updated
        }

        allowedAppsGroup.setOnCheckedChangeListener { _, checkedId ->
            val isAll = checkedId == R.id.settingsRadioAllApps
            selectedAppsLayout.visibility = if (isAll) android.view.View.GONE else android.view.View.VISIBLE
            prefs.allowedAppsMode = if (isAll) ManualCapturePrefs.MODE_ALL_APPS else ManualCapturePrefs.MODE_SELECTED_APPS
            if (!isAll) saveSelectedPackages()
        }

        val checkChangeListener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            saveSelectedPackages()
        }
        cbWhatsApp.setOnCheckedChangeListener(checkChangeListener)
        cbTelegram.setOnCheckedChangeListener(checkChangeListener)
        cbInstagram.setOnCheckedChangeListener(checkChangeListener)

        // Add Quick Settings tile
        val btnAddTile = findViewById<MaterialButton>(R.id.btnAddTile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btnAddTile.setOnClickListener {
                val statusBarManager = getSystemService(StatusBarManager::class.java)
                statusBarManager?.requestAddTileService(
                    ComponentName(this, ScanQuickSettingsTileService::class.java),
                    "Scan message",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_scan),
                    mainExecutor
                ) { _ -> /* callback */ }
            }
        } else {
            btnAddTile.isEnabled = false
            btnAddTile.text = "Add tile manually from Quick Settings"
        }

        // Toggle overlay
        btnToggleOverlay.setOnClickListener { toggleOverlay() }

        // Manage captures
        val btnManage = findViewById<MaterialButton>(R.id.btnManageCaptures)
        btnManage.setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.ui.EvidenceActivity::class.java))
        }

        // Clear all captures
        val btnClear = findViewById<MaterialButton>(R.id.btnClearCaptures)
        btnClear.setOnClickListener { confirmClearAll() }

        // Accessibility status & settings
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnToggleAccessibility = findViewById(R.id.btnToggleAccessibility)
        btnToggleAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "Find 'Honeypot' under Installed Apps/Services and turn it ON.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        updateOverlayStatus()
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val active = HoneypotAccessibilityService.isServiceRunning
        tvAccessibilityStatus.text = if (active) {
            "Active — silent Google Lens capture enabled (zero popups)"
        } else {
            "Disabled — turn on to avoid 'recording or casting' popups"
        }
        btnToggleAccessibility.text = if (active) {
            "Accessibility Settings (Active)"
        } else {
            "Enable in Accessibility Settings"
        }
    }

    private fun updateOverlayStatus() {
        val hasPermission = Settings.canDrawOverlays(this)
        val running = prefs.overlayEnabled && hasPermission
        tvOverlayStatus.text = when {
            !hasPermission -> "Overlay permission not granted"
            running -> "Overlay is enabled"
            else -> "Overlay is disabled"
        }
        btnToggleOverlay.text = if (running) "Disable overlay" else "Enable overlay"
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            // Deep-link to overlay permission settings
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val nowEnabled = !prefs.overlayEnabled
        prefs.overlayEnabled = nowEnabled
        if (nowEnabled) {
            val intent = Intent(this, ScanOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } else {
            stopService(Intent(this, ScanOverlayService::class.java))
        }
        ScanQuickSettingsTileService.requestTileUpdate(this)
        updateOverlayStatus()
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
                Toast.makeText(this@CrossAppSettingsActivity,
                    "${captures.size} capture(s) deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

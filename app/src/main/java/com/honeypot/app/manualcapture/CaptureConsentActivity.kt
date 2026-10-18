package com.honeypot.app.manualcapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.honeypot.app.R
import com.honeypot.app.ui.SystemBars

/**
 * Trampoline activity for initializing the manual capture session and
 * configuring app-selection preferences.
 *
 * Responsibilities:
 * 1. Allow user to specify which apps Honeypot can scan ("All apps" vs "Selected apps").
 * 2. Remember this choice if selected.
 * 3. Request Android's MediaProjection consent once for the active overlay session.
 * 4. Pass the resulting token to [ScanOverlayService], which holds it for on-demand captures.
 */
class CaptureConsentActivity : AppCompatActivity() {

    private val prefs by lazy { ManualCapturePrefs(this) }

    private lateinit var rgAppSelection: RadioGroup
    private lateinit var radioAllApps: RadioButton
    private lateinit var radioSelectedApps: RadioButton
    private lateinit var layoutSelectedApps: LinearLayout
    private lateinit var cbWhatsApp: CheckBox
    private lateinit var cbTelegram: CheckBox
    private lateinit var cbInstagram: CheckBox
    private lateinit var cbRemember: CheckBox

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val startIntent = ScanOverlayService.buildProjectionIntent(
                    this,
                    result.resultCode,
                    result.data!!
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent)
                } else {
                    startService(startIntent)
                }
            }
            // Always finish — once consent is handled, activity closes.
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_consent)
        SystemBars.apply(this, findViewById(android.R.id.content))

        val sourceApp = intent.getStringExtra(EXTRA_SOURCE_APP) ?: run {
            val detectedPkg = AppForegroundDetector.getForegroundPackage(this)
            AppForegroundDetector.getAppName(this, detectedPkg)
        }

        findViewById<TextView>(R.id.sourceAppLabel).text =
            getString(R.string.capture_consent_source, sourceApp)

        rgAppSelection = findViewById(R.id.rgConsentAppSelection)
        radioAllApps = findViewById(R.id.radioConsentAllApps)
        radioSelectedApps = findViewById(R.id.radioConsentSelectedApps)
        layoutSelectedApps = findViewById(R.id.layoutConsentSelectedApps)
        cbWhatsApp = findViewById(R.id.cbConsentWhatsApp)
        cbTelegram = findViewById(R.id.cbConsentTelegram)
        cbInstagram = findViewById(R.id.cbConsentInstagram)
        cbRemember = findViewById(R.id.cbConsentRemember)

        // Initialize state from existing preferences
        val isAllApps = prefs.allowedAppsMode == ManualCapturePrefs.MODE_ALL_APPS
        if (isAllApps) {
            radioAllApps.isChecked = true
            layoutSelectedApps.visibility = View.GONE
        } else {
            radioSelectedApps.isChecked = true
            layoutSelectedApps.visibility = View.VISIBLE
        }

        val allowedPkgs = prefs.allowedAppPackages
        cbWhatsApp.isChecked = allowedPkgs.contains(ManualCapturePrefs.PKG_WHATSAPP)
        cbTelegram.isChecked = allowedPkgs.contains(ManualCapturePrefs.PKG_TELEGRAM)
        cbInstagram.isChecked = allowedPkgs.contains(ManualCapturePrefs.PKG_INSTAGRAM)

        rgAppSelection.setOnCheckedChangeListener { _, checkedId ->
            layoutSelectedApps.visibility = if (checkedId == R.id.radioConsentSelectedApps) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            Toast.makeText(this, "Tap 'Downloaded apps' \u2794 'Honeypot' to enable", Toast.LENGTH_LONG).show()
            startActivity(HoneypotAccessibilityService.buildSettingsIntent(this))
        }

        findViewById<Button>(R.id.btnProceed).setOnClickListener {
            val isAll = radioAllApps.isChecked
            val mode = if (isAll) ManualCapturePrefs.MODE_ALL_APPS else ManualCapturePrefs.MODE_SELECTED_APPS
            val selected = mutableSetOf<String>()

            if (!isAll) {
                if (cbWhatsApp.isChecked) selected.add(ManualCapturePrefs.PKG_WHATSAPP)
                if (cbTelegram.isChecked) selected.add(ManualCapturePrefs.PKG_TELEGRAM)
                if (cbInstagram.isChecked) selected.add(ManualCapturePrefs.PKG_INSTAGRAM)

                if (selected.isEmpty()) {
                    Toast.makeText(this, "Please select at least one app to scan", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            if (cbRemember.isChecked) {
                prefs.allowedAppsMode = mode
                if (!isAll) {
                    prefs.allowedAppPackages = selected
                }
                prefs.hasConfiguredAppSelection = true
            }

            launchMediaProjectionConsent()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // If Honeypot is enabled, notify user and close this screen so user can return to their chat app
        if (HoneypotAccessibilityService.isServiceRunning) {
            Toast.makeText(this, "Honeypot is ready! Tap the floating button over any chat to scan.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun launchMediaProjectionConsent() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermissionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_SOURCE_APP = "extra_source_app"

        fun buildIntent(context: Context, sourceApp: String): Intent =
            Intent(context, CaptureConsentActivity::class.java).apply {
                putExtra(EXTRA_SOURCE_APP, sourceApp)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

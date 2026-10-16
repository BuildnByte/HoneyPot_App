package com.example.myapplication.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.myapplication.R
import com.example.myapplication.filter.FilterPreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var filterPrefs: FilterPreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.apply(this, findViewById(android.R.id.content))

        filterPrefs = FilterPreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // ── Theme ──────────────────────────────────────────────────────────
        val themePrefs = getSharedPreferences("theme_prefs", android.content.Context.MODE_PRIVATE)
        val currentMode = themePrefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        val sysBtn = findViewById<MaterialButton>(R.id.systemThemeButton)
        val lightBtn = findViewById<MaterialButton>(R.id.lightThemeButton)
        val darkBtn = findViewById<MaterialButton>(R.id.darkThemeButton)

        fun updateThemeButtons(selectedMode: Int) {
            val buttons = listOf(sysBtn to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, 
                                 lightBtn to AppCompatDelegate.MODE_NIGHT_NO, 
                                 darkBtn to AppCompatDelegate.MODE_NIGHT_YES)
            buttons.forEach { (btn, mode) ->
                val isActive = mode == selectedMode
                btn.backgroundTintList = getColorStateList(if (isActive) R.color.accent_blue else android.R.color.transparent)
                btn.setTextColor(getColor(if (isActive) R.color.text_on_accent else R.color.text_secondary))
            }
        }

        updateThemeButtons(currentMode)

        fun setMode(mode: Int) {
            themePrefs.edit().putInt("night_mode", mode).apply()
            updateThemeButtons(mode)
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        sysBtn.setOnClickListener { setMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        lightBtn.setOnClickListener { setMode(AppCompatDelegate.MODE_NIGHT_NO) }
        darkBtn.setOnClickListener { setMode(AppCompatDelegate.MODE_NIGHT_YES) }

        // ── Developer tools ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.openDeveloperToolsButton).setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.MainActivity::class.java).apply {
                putExtra("SHOW_ADVANCED_TOOLS", true)
            })
        }
        findViewById<android.view.View>(R.id.openCrossAppScanningButton).setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.manualcapture.CrossAppSettingsActivity::class.java))
        }

        // ── SMS Pre-Filter switches ────────────────────────────────────────
        val switchDlt          = findViewById<MaterialSwitch>(R.id.switchFilterDlt)
        val switchOtp          = findViewById<MaterialSwitch>(R.id.switchFilterOtp)
        val switchBank         = findViewById<MaterialSwitch>(R.id.switchFilterBank)
        val switchSafeCache    = findViewById<MaterialSwitch>(R.id.switchFilterSafeCache)
        val switchCloudReason  = findViewById<MaterialSwitch>(R.id.switchCloudReasoning)

        // Restore saved state
        switchDlt.isChecked         = filterPrefs.filterDltInstitutional
        switchOtp.isChecked         = filterPrefs.filterOtp
        switchBank.isChecked        = filterPrefs.filterBankTransaction
        switchSafeCache.isChecked   = filterPrefs.filterSafeCache
        switchCloudReason.isChecked = filterPrefs.useCloudReasoning

        // Persist on change
        switchDlt.setOnCheckedChangeListener        { _, v -> filterPrefs.filterDltInstitutional = v }
        switchOtp.setOnCheckedChangeListener        { _, v -> filterPrefs.filterOtp = v }
        switchBank.setOnCheckedChangeListener       { _, v -> filterPrefs.filterBankTransaction = v }
        switchSafeCache.setOnCheckedChangeListener  { _, v -> filterPrefs.filterSafeCache = v }
        switchCloudReason.setOnCheckedChangeListener { _, v -> filterPrefs.useCloudReasoning = v }

        // ── On-device AI Model Download Section ─────────────────────────────
        setupModelDownloadSection()
    }

    private fun setupModelDownloadSection() {
        val badge = findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardModelStatusBadge)
        val icon = findViewById<android.widget.ImageView>(R.id.ivModelStatusIcon)
        val header = findViewById<android.widget.TextView>(R.id.tvModelStatusHeader)
        val subtext = findViewById<android.widget.TextView>(R.id.tvModelStatusSubtext)
        val progressLayout = findViewById<android.view.View>(R.id.layoutDownloadProgress)
        val progressBar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressModelDownload)
        val progressDetail = findViewById<android.widget.TextView>(R.id.tvDownloadProgressDetail)
        val btnDownload = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadModels)
        val btnDelete = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeleteModels)

        var downloadJob: kotlinx.coroutines.Job? = null
        var isDownloading = false

        fun resetDownloadButton() {
            isDownloading = false
            btnDownload.text = "Download"
            btnDownload.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.badge_blue_bg))
            btnDownload.setTextColor(getColor(R.color.accent_blue))
            btnDownload.isEnabled = true
        }

        fun setCancelDownloadButton() {
            isDownloading = true
            btnDownload.text = "Cancel"
            btnDownload.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.badge_red_bg))
            btnDownload.setTextColor(getColor(R.color.accent_red))
            btnDownload.isEnabled = true
        }

        fun refreshStatus() {
            if (isDownloading) return
            val isReady = com.example.myapplication.download.ModelDownloadManager.areModelsReady(this)
            val sizeMB = com.example.myapplication.download.ModelDownloadManager.getDownloadedModelsSizeMB(this)

            header.text = "On-Device Protection"
            if (isReady) {
                badge.setCardBackgroundColor(getColor(R.color.badge_green_bg))
                icon.setColorFilter(getColor(R.color.accent_green))
                subtext.text = if (sizeMB > 0) "Offline protection active (${String.format(java.util.Locale.US, "%.1f", sizeMB)} MB)" else "Offline protection active"
                btnDownload.text = "Re-download"
                btnDownload.visibility = android.view.View.GONE
                btnDelete.visibility = if (sizeMB > 0) android.view.View.VISIBLE else android.view.View.GONE
            } else {
                badge.setCardBackgroundColor(getColor(R.color.badge_amber_bg))
                icon.setColorFilter(getColor(R.color.accent_amber))
                subtext.text = "Offline package missing (~234 MB)"
                resetDownloadButton()
                btnDownload.visibility = android.view.View.VISIBLE
                btnDelete.visibility = android.view.View.GONE
            }
        }

        fun startDownloadProcess() {
            progressLayout.visibility = android.view.View.VISIBLE
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressDetail.text = "Connecting…"
            setCancelDownloadButton()
            btnDelete.visibility = android.view.View.GONE

            downloadJob = lifecycleScope.launch {
                com.example.myapplication.download.ModelDownloadManager.downloadMissingModels(this@SettingsActivity).collect { progress ->
                    when (progress) {
                        is com.example.myapplication.download.ModelDownloadManager.DownloadProgress.Downloading -> {
                            progressBar.isIndeterminate = false
                            progressBar.progress = progress.fileProgressPercent
                            progressDetail.text = "Downloading (${progress.currentFileIndex}/${progress.totalFiles}) • ${progress.fileProgressPercent}%\n" +
                                    "${String.format(java.util.Locale.US, "%.1f", progress.totalDownloadedMB)} / ${String.format(java.util.Locale.US, "%.1f", progress.totalSizeMB)} MB"
                        }
                        is com.example.myapplication.download.ModelDownloadManager.DownloadProgress.Completed -> {
                            progressLayout.visibility = android.view.View.GONE
                            resetDownloadButton()
                            refreshStatus()
                            android.widget.Toast.makeText(this@SettingsActivity, "On-device protection ready!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        is com.example.myapplication.download.ModelDownloadManager.DownloadProgress.Failed -> {
                            progressLayout.visibility = android.view.View.GONE
                            resetDownloadButton()
                            refreshStatus()
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("Download Failed")
                                .setMessage(progress.error)
                                .setPositiveButton("Retry") { _, _ ->
                                    startDownloadProcess()
                                }
                                .setNegativeButton("OK", null)
                                .show()
                        }
                        else -> Unit
                    }
                }
            }
        }

        refreshStatus()

        btnDownload.setOnClickListener {
            if (isDownloading) {
                downloadJob?.cancel()
                com.example.myapplication.download.ModelDownloadManager.cancelDownload()
                progressLayout.visibility = android.view.View.GONE
                resetDownloadButton()
                refreshStatus()
                android.widget.Toast.makeText(this@SettingsActivity, "Download cancelled", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Download On-Device Protection")
                .setMessage(
                    "Download the offline detection package to analyze messages and chat screenshots locally on your phone without sending contents to the cloud.\n\n" +
                    "• Package Size: ~234 MB\n" +
                    "• Free Space Needed: 250+ MB\n\n" +
                    "⚠️ Warning: Downloading over mobile data may consume your data plan. Wi-Fi connection is strongly recommended."
                )
                .setPositiveButton("Download") { _, _ ->
                    startDownloadProcess()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDelete.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Remove On-Device Protection?")
                .setMessage("This will remove the downloaded offline AI package (~234 MB) from device storage. The app will use cloud fallback until re-downloaded.")
                .setPositiveButton("Remove") { _, _ ->
                    com.example.myapplication.download.ModelDownloadManager.deleteDownloadedModels(this)
                    refreshStatus()
                    android.widget.Toast.makeText(this, "Offline package removed", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.myapplication.download.ModelDownloadManager.cancelDownload()
    }
}

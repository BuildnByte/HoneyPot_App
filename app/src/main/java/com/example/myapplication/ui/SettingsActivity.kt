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
        findViewById<MaterialButton>(R.id.systemThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        findViewById<MaterialButton>(R.id.lightThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        findViewById<MaterialButton>(R.id.darkThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        // ── Developer tools ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.openDeveloperToolsButton).setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.MainActivity::class.java).apply {
                putExtra("SHOW_ADVANCED_TOOLS", true)
            })
        }
        findViewById<MaterialButton>(R.id.openCrossAppScanningButton).setOnClickListener {
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
    }
}

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
    }
}

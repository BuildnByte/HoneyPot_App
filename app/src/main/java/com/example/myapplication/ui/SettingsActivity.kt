package com.example.myapplication.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.myapplication.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.apply(this, findViewById(android.R.id.content))

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.systemThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        findViewById<MaterialButton>(R.id.lightThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        findViewById<MaterialButton>(R.id.darkThemeButton).setOnClickListener {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        findViewById<MaterialButton>(R.id.openDeveloperToolsButton).setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.MainActivity::class.java).apply {
                putExtra("SHOW_ADVANCED_TOOLS", true)
            })
        }
        findViewById<MaterialButton>(R.id.openCrossAppScanningButton).setOnClickListener {
            startActivity(Intent(this, com.example.myapplication.manualcapture.CrossAppSettingsActivity::class.java))
        }
    }
}

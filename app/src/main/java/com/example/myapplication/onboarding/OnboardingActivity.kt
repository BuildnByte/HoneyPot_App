package com.example.myapplication.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.ui.SystemBars
import com.google.android.material.button.MaterialButton

class OnboardingActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "onboarding_prefs"
        const val KEY_COMPLETED = "onboarding_completed"
        private const val PERM_REQUEST_CODE = 200

        /** All pages in order. Referenced by OnboardingPageFragment. */
        val PAGES = listOf(
            OnboardingPage(
                illustrationRes = R.drawable.img_onboard_home,
                chipLabel = "Real-Time Protection",
                title = "Live SMS Defense\n& Status Feed",
                body = "HoneyPot runs silently in the background, automatically scanning incoming messages with on-device AI to catch scams before you interact.",
                isScreenshot = true
            ),
            OnboardingPage(
                illustrationRes = R.drawable.img_onboard_messages,
                chipLabel = "Scam Baiting",
                title = "Autonomous AI\nHoneypot Baiting",
                body = "When fraudsters attack, HoneyPot engages them automatically with realistic personas, extracting UPI IDs, links, and tactics safely.",
                isScreenshot = true
            ),
            OnboardingPage(
                illustrationRes = R.drawable.img_onboard_overlay,
                chipLabel = "Screen Scanner",
                title = "Analyze WhatsApp &\nOther Apps On-Screen",
                body = "Tap the floating scan button over any app to inspect suspicious messages, verify scam risk, and copy protective safe replies.",
                isScreenshot = true
            ),
            OnboardingPage(
                illustrationRes = R.drawable.img_onboard_history,
                chipLabel = "Evidence Dossier",
                title = "Tamper-Proof Vault\n& PDF Reports",
                body = "Every detected threat is saved with cryptographic evidence logs. Filter sessions by risk level and export court-ready PDF dossiers.",
                isScreenshot = true
            ),
            OnboardingPage(
                illustrationRes = R.drawable.ic_memory,
                chipLabel = "Offline Intelligence",
                title = "On-Device AI\nProtection",
                body = "Detect scams locally with zero latency and full privacy. Download our on-device AI package (~234 MB) now, or set it up later in Settings.",
                isScreenshot = false
            ),
            OnboardingPage(
                illustrationRes = R.drawable.ic_scan,
                chipLabel = "Cross-App Scanner",
                title = "Set Up Cross-App\nScanning",
                body = "Detect scams inside WhatsApp, Telegram, and other apps. Enable accessibility and overlay permissions to activate on-screen scanning.",
                isScreenshot = false
            ),
            OnboardingPage(
                illustrationRes = R.drawable.ic_onboard_ready,
                chipLabel = "Let's Go",
                title = "You're Ready To\nCatch Scammers",
                body = "Grant SMS and notification permissions so HoneyPot can monitor threats in the background and alert you the moment a scam arrives.",
                isScreenshot = false
            )
        )
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: MaterialButton
    private lateinit var btnBack: TextView
    private lateinit var btnSkip: TextView
    private lateinit var dots: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── First-launch check ──────────────────────────────────────────────
        val isForceShow = intent.getBooleanExtra("FORCE_SHOW", false)
        val onboardingPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!isForceShow && onboardingPrefs.getBoolean(KEY_COMPLETED, false)) {
            // Already completed — jump straight to MainActivity
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        // ────────────────────────────────────────────────────────────────────

        val themePrefs = getSharedPreferences("theme_prefs", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            themePrefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        setContentView(R.layout.activity_onboarding)
        SystemBars.apply(this, findViewById(android.R.id.content))

        viewPager = findViewById(R.id.viewPager)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)
        btnSkip = findViewById(R.id.btnSkip)

        dots = listOf(
            findViewById(R.id.dot0),
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4),
            findViewById(R.id.dot5),
            findViewById(R.id.dot6)
        )

        val adapter = OnboardingPagerAdapter(this)
        viewPager.adapter = adapter

        // Smooth page transition with depth effect
        viewPager.setPageTransformer(DepthPageTransformer())

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtons(position)
            }
        })

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current == PAGES.lastIndex) {
                finishOnboarding()
            } else {
                viewPager.currentItem = current + 1
            }
        }

        btnBack.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) viewPager.currentItem = current - 1
        }

        btnSkip.setOnClickListener { finishOnboarding() }

        updateDots(0)
        updateButtons(0)
    }

    private fun updateDots(position: Int) {
        dots.forEachIndexed { index, dot ->
            val isActive = index == position
            dot.isSelected = isActive
            // Animate dot width for active vs inactive
            val targetWidth = if (isActive) dpToPx(24) else dpToPx(8)
            val params = dot.layoutParams
            params.width = targetWidth
            dot.animate()
                .scaleX(if (isActive) 1f else 0.85f)
                .setDuration(200)
                .start()
            dot.layoutParams = params
        }
    }

    private fun updateButtons(position: Int) {
        val isLast = position == PAGES.lastIndex
        btnNext.text = if (isLast) "Grant Permissions & Start" else "Next"
        btnBack.visibility = if (position > 0) View.VISIBLE else View.GONE
        btnSkip.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
    }

    private fun finishOnboarding() {
        // Request permissions before going to MainActivity
        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissionsNeeded.toTypedArray(), PERM_REQUEST_CODE
            )
        } else {
            completeAndLaunchMain()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST_CODE) {
            completeAndLaunchMain()
        }
    }

    private fun completeAndLaunchMain() {
        // Mark onboarding as completed synchronously so it never shows again on next app opens
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .commit()

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}

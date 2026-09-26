package com.example.myapplication.manualcapture

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * Quick Settings tile — "Scan message".
 *
 * Behaviour:
 * - Inactive state: overlay is disabled.
 * - Active state: overlay is running.
 * - On first tap when permission is missing → opens overlay permission settings.
 * - Toggle:
 *   - ON: starts [ScanOverlayService] after verifying overlay permission.
 *   - OFF: stops [ScanOverlayService].
 *
 * The tile NEVER captures a screenshot, inspects content, or starts analysis
 * merely by being toggled. It only controls the overlay service lifecycle.
 */
class ScanQuickSettingsTileService : TileService() {

    private val prefs by lazy { ManualCapturePrefs(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // 1. If overlay is currently enabled, user tapped to REMOVE / TURN OFF the overlay!
        if (prefs.overlayEnabled) {
            prefs.overlayEnabled = false
            HoneypotAccessibilityService.hideOverlay()
            stopOverlayService()
            updateTileState()

            // Automatically collapse the notification / Quick Settings shade
            Handler(Looper.getMainLooper()).postDelayed({
                HoneypotAccessibilityService.dismissNotificationShade()
            }, 100)
            return
        }

        // 2. User tapped to ENABLE the overlay:
        val overlayGranted = Settings.canDrawOverlays(this)
        if (!overlayGranted) {
            // Open overlay permission settings — user must grant before we enable
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            launchSettings(overlayIntent)
            return
        }

        val isAccessRunning = HoneypotAccessibilityService.isServiceRunning ||
                HoneypotAccessibilityService.isAccessibilityEnabledInSettings(this)

        if (!isAccessRunning) {
            Toast.makeText(
                this,
                "Tap 'Downloaded apps' \u2794 'Honeypot' to enable",
                Toast.LENGTH_LONG
            ).show()
            val accessIntent = HoneypotAccessibilityService.buildSettingsIntent(this)
            launchSettings(accessIntent)
            return
        }

        prefs.overlayEnabled = true
        if (HoneypotAccessibilityService.isServiceRunning) {
            HoneypotAccessibilityService.showOverlay()
            stopOverlayService()
        } else {
            startOverlayService()
        }

        updateTileState()

        // Automatically collapse the notification / Quick Settings shade
        Handler(Looper.getMainLooper()).postDelayed({
            HoneypotAccessibilityService.dismissNotificationShade()
        }, 100)
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val overlayGranted = Settings.canDrawOverlays(this)
        val serviceRunning = prefs.overlayEnabled && overlayGranted

        tile.state = if (serviceRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Scan message"
        tile.contentDescription = if (serviceRunning)
            "Scan overlay is active — tap to disable"
        else
            "Scan overlay is disabled — tap to enable"
        tile.updateTile()
    }

    private fun startOverlayService() {
        val intent = Intent(this, ScanOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOverlayService() {
        stopService(Intent(this, ScanOverlayService::class.java))
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(9002)
        } catch (_: Exception) {}
    }

    private fun launchSettings(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    options
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startActivityAndCollapse failed, falling back to direct startActivity", e)
            try {
                startActivity(intent)
            } catch (e2: Throwable) {
                Log.e(TAG, "Direct startActivity failed", e2)
            }
        }
    }

    companion object {
        private const val TAG = "ScanQSTile"

        /**
         * Request a tile refresh. Called from [ScanOverlayService] when overlay state changes.
         */
        fun requestTileUpdate(context: Context) {
            try {
                requestListeningState(context, android.content.ComponentName(context, ScanQuickSettingsTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not request tile update", e)
            }
        }
    }
}

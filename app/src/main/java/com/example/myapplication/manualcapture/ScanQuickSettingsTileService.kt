package com.example.myapplication.manualcapture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

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
        val overlayGranted = Settings.canDrawOverlays(this)

        if (!overlayGranted) {
            // Open overlay permission settings — user must grant before we enable
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(
                    this, 0, overlayIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                ))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(overlayIntent)
            }
            return
        }

        val nowEnabled = !prefs.overlayEnabled
        prefs.overlayEnabled = nowEnabled

        if (nowEnabled) {
            startOverlayService()
        } else {
            stopOverlayService()
        }

        updateTileState()
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

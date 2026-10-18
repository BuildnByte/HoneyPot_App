package com.honeypot.app.manualcapture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Utility to identify the current foreground application and verify if it is
 * allowed by the user's manual capture preferences.
 */
object AppForegroundDetector {

    private const val TAG = "AppForegroundDetector"

    /**
     * Attempts to find the package name of the app currently in the foreground.
     * Uses [UsageStatsManager] if permission is available.
     */
    fun getForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 15_000 // Last 15 seconds

        return try {
            val events = usm.queryEvents(startTime, endTime) ?: return null
            val event = UsageEvents.Event()
            var lastPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.packageName != context.packageName) {
                        lastPackage = event.packageName
                    }
                }
            }
            lastPackage
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query usage events", e)
            null
        }
    }

    /**
     * Human-friendly name for a given package name.
     */
    fun getAppName(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Unknown"
        when (packageName) {
            ManualCapturePrefs.PKG_WHATSAPP -> return "WhatsApp"
            ManualCapturePrefs.PKG_TELEGRAM -> return "Telegram"
            ManualCapturePrefs.PKG_INSTAGRAM -> return "Instagram"
        }
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    /**
     * Returns true if the given app package is permitted according to user preferences.
     */
    fun isAppAllowed(prefs: ManualCapturePrefs, packageName: String?): Boolean {
        if (prefs.allowedAppsMode == ManualCapturePrefs.MODE_ALL_APPS) {
            return true
        }
        if (packageName == null) {
            // Cannot reliably detect foreground app (e.g. usage stats permission not granted);
            // allow capture so we don't block the user unnecessarily.
            return true
        }
        return prefs.allowedAppPackages.contains(packageName)
    }

    /**
     * Checks if PACKAGE_USAGE_STATS permission is granted to this app.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

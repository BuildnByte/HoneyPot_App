package com.example.myapplication.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manages tracking of messages scanned by Honeypot.
 *
 * Scanned count accounts for:
 * 1. Device SMS Inbox baseline (when READ_SMS permission is granted)
 * 2. Real-time incoming SMS processed by SmsProcessingService / SmsReceiver
 * 3. Fallback persistent count across app lifecycle
 */
object AppStatsManager {
    private const val TAG = "AppStatsManager"
    private const val PREFS_NAME = "honeypot_stats_prefs"
    private const val KEY_SCANNED_SMS_COUNT = "key_scanned_sms_count"

    /**
     * Increments the persistent count of SMS scanned by the app.
     */
    fun incrementScannedSms(context: Context, delta: Int = 1) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(KEY_SCANNED_SMS_COUNT, 0)
            prefs.edit().putInt(KEY_SCANNED_SMS_COUNT, current + delta).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to increment scanned SMS count", e)
        }
    }

    /**
     * Returns the persistent count of SMS scanned by the app.
     */
    fun getScannedSmsCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_SCANNED_SMS_COUNT, 0)
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Records the inbox baseline count if [count] is greater than the current stored count.
     * This ensures the counter never regresses even if permissions change.
     */
    fun recordInboxBaseline(context: Context, count: Int) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getInt(KEY_SCANNED_SMS_COUNT, 0)
            if (count > current) {
                prefs.edit().putInt(KEY_SCANNED_SMS_COUNT, count).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record inbox baseline", e)
        }
    }

    /**
     * Reads current count of messages in device SMS inbox (Telephony.Sms.CONTENT_URI).
     * Returns 0 if permission is not granted or query fails.
     */
    fun getDeviceInboxSmsCount(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return 0
        }
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.count
            } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query SMS content provider", e)
            0
        }
    }
}

package com.honeypot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.honeypot.app.engine.ScamDetectionEngineManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class HoneypotApp : Application() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Pre-warm the scam detection engine as soon as the process starts.
        // The ONNX session (~227 MB) and vocab (~197k tokens) take several seconds
        // to load on first use. By loading here, the engine is ready before any
        // SMS arrives, so notifications fire immediately instead of after a 15-20s wait.
        GlobalScope.launch {
            try {
                ScamDetectionEngineManager.getEngine(this@HoneypotApp)
                Log.d("HoneypotApp", "Engine pre-warm complete")
            } catch (e: Exception) {
                Log.e("HoneypotApp", "Engine pre-warm failed (will retry on first SMS)", e)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for scam alerts
            val scamAlertChannel = NotificationChannel(
                CHANNEL_SCAM_ALERT,
                "Scam Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a scam is detected"
            }

            // Channel for warnings
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING,
                "Scam Warnings",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for suspicious messages"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(scamAlertChannel)
            manager.createNotificationChannel(warningChannel)

            // Channel for background service
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while analyzing messages"
            }
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_SCAM_ALERT = "scam_alert_channel"
        const val CHANNEL_WARNING = "scam_warning_channel"
        const val CHANNEL_SERVICE = "sms_analysis_service_channel"
    }
}
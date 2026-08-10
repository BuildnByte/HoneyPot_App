package com.example.myapplication

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class HoneypotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
        }
    }

    companion object {
        const val CHANNEL_SCAM_ALERT = "scam_alert"
        const val CHANNEL_WARNING = "scam_warning"
    }
}
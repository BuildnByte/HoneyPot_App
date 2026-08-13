package com.example.myapplication.service

import android.app.Notification
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.HoneypotApp
import com.example.myapplication.R
import com.example.myapplication.engine.ScamDetectionEngine
import com.example.myapplication.engine.ScamDetectionEngineManager
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.ScamResult
import com.example.myapplication.trigger.TriggerEngine
import kotlinx.coroutines.launch

class SmsProcessingService : LifecycleService() {

    // In-memory conversation store: senderNumber → list of messages
    private val conversationStore = mutableMapOf<String, MutableList<String>>()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createServiceNotification())
        // Engine will be initialized on first demand or here
        lifecycleScope.launch {
            try {
                ScamDetectionEngineManager.getEngine(this@SmsProcessingService)
            } catch (e: Exception) {
                android.util.Log.e("SmsProcessingService", "Engine init failed", e)
            }
        }
    }

    private fun createServiceNotification(): Notification {
        return NotificationCompat.Builder(this, HoneypotApp.CHANNEL_SERVICE)
            .setContentTitle("SMS Analysis Active")
            .setContentText("Monitoring for potential scams...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val incomingSms = intent?.getParcelableExtra<IncomingSms>("sms")
            ?: return START_NOT_STICKY

        lifecycleScope.launch {
            val history = conversationStore.getOrPut(incomingSms.sender) { mutableListOf() }
            
            val activeEngine = ScamDetectionEngineManager.getEngine(this@SmsProcessingService)
            val result = activeEngine.processMessage(incomingSms, history)
            
            history.add("[${incomingSms.sender}]: ${incomingSms.body}")
            if (history.size > 10) history.removeAt(0)

            actOnResult(result)
            
            // Stop service if no more work (if it was started for a specific SMS)
            // But usually we keep it running as long as it's a foreground service processing stuff.
            // For now, let's keep it simple.
        }

        return START_NOT_STICKY
    }

    private fun actOnResult(result: ScamDetectionEngine.DetectionResult) {
        val scamResult = result.scamResult ?: return
        val sms = result.sms

        when {
            scamResult.isScam && scamResult.confidence >= 0.85f -> {
                showScamAlert(sms, scamResult)
            }
            scamResult.isScam && scamResult.confidence >= 0.60f -> {
                showWarningNotification(sms, scamResult)
            }
            else -> {
                logForMonitoring(sms, scamResult, result.triggerResult)
            }
        }
    }

    private fun showScamAlert(sms: IncomingSms, result: ScamResult) {
        val notification = NotificationCompat.Builder(this, HoneypotApp.CHANNEL_SCAM_ALERT)
            .setContentTitle("Scam Detected!")
            .setContentText(result.reasoning)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(sms.sender.hashCode(), notification)
    }

    private fun showWarningNotification(sms: IncomingSms, result: ScamResult) {
        val notification = NotificationCompat.Builder(this, HoneypotApp.CHANNEL_WARNING)
            .setContentTitle("Suspicious Message")
            .setContentText("This message might be a scam. Exercise caution.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(sms.sender.hashCode(), notification)
    }

    private fun logForMonitoring(sms: IncomingSms, result: ScamResult,
                                 trigger: TriggerEngine.TriggerResult?) {
        // Write to a monitoring log table in Room
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not close global engine here unless app is being terminated
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}

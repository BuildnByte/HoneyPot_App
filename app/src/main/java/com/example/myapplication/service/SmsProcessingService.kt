package com.example.myapplication.service

import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.engine.ScamDetectionEngine
import com.example.myapplication.model.IncomingSms
import com.example.myapplication.model.ScamResult
import com.example.myapplication.trigger.TriggerEngine
import kotlinx.coroutines.launch

class SmsProcessingService : LifecycleService() {

    private lateinit var engine: ScamDetectionEngine

    // In-memory conversation store: senderNumber → list of messages
    private val conversationStore = mutableMapOf<String, MutableList<String>>()

    override fun onCreate() {
        super.onCreate()
        engine = ScamDetectionEngine(this)
        lifecycleScope.launch {
            try {
                engine.initialize()
            } catch (e: Exception) {
                android.util.Log.e("SmsProcessingService", "Engine init failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val incomingSms = intent?.getParcelableExtra<IncomingSms>("sms")
            ?: return START_NOT_STICKY

        lifecycleScope.launch {
            val history = conversationStore.getOrPut(incomingSms.sender) { mutableListOf() }
            
            val result = engine.processMessage(incomingSms, history)
            
            // Update history AFTER processing (so the current message is included for next time, 
            // but engine already handled adding it to history for current run via history parameter logic if needed?
            // Actually engine.processMessage uses the history list passed to it.
            // Wait, looking at engine logic: Stage 3: conversationText = history.joinToString("\n")
            // It doesn't add the current message to history. Let's fix that.
            
            history.add("[${incomingSms.sender}]: ${incomingSms.body}")
            if (history.size > 10) history.removeAt(0)

            actOnResult(result)
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
        // Fire high-priority notification
    }

    private fun showWarningNotification(sms: IncomingSms, result: ScamResult) {
        // Fire warning notification
    }

    private fun logForMonitoring(sms: IncomingSms, result: ScamResult,
                                 trigger: TriggerEngine.TriggerResult?) {
        // Write to a monitoring log table in Room
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.close()
    }
}

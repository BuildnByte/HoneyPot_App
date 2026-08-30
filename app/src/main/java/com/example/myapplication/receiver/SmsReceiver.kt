package com.example.myapplication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SmsReceiver", "onReceive triggered with action: ${intent.action}")
        
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d("SmsReceiver", "Ignoring action: ${intent.action}")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.d("SmsReceiver", "Messages array is null or empty")
            return
        }

        val sender = messages[0].originatingAddress
        if (sender == null) {
            Log.d("SmsReceiver", "Sender is null")
            return
        }
        val body = messages.joinToString("") { it.messageBody }

        Log.d("SmsReceiver", "SMS from: $sender | body: $body")

        val serviceIntent = Intent(context, com.example.myapplication.service.SmsProcessingService::class.java).apply {
            putExtra("sms", com.example.myapplication.model.IncomingSms(sender, body))
        }
        
        try {
            Log.d("SmsReceiver", "Starting foreground service: ${serviceIntent.component?.shortClassName}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("SmsReceiver", "Service start requested successfully.")
        } catch (e: Exception) {
            Log.e("SmsReceiver", "CRITICAL: Failed to start service: ${e.message}", e)
        }
    }
}
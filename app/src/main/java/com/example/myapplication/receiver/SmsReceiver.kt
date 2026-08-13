package com.example.myapplication.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody }

        Log.d("SmsReceiver", "SMS from: $sender | body: $body")

        val serviceIntent = Intent(context, com.example.myapplication.service.SmsProcessingService::class.java).apply {
            putExtra("sms", com.example.myapplication.model.IncomingSms(sender, body))
        }
        
        context.startForegroundService(serviceIntent)
    }
}
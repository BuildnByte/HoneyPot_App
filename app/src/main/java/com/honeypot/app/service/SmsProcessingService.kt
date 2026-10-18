package com.honeypot.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.honeypot.app.R
import com.honeypot.app.filter.FilterPreferenceManager
import com.honeypot.app.filter.FilterResult
import com.honeypot.app.filter.SmsPreFilter
import com.honeypot.app.llm.CloudReplyGenerator
import com.honeypot.app.llm.ConsentPreferenceManager
import com.honeypot.app.model.AiAnalysisLog
import com.honeypot.app.model.IncomingSms
import com.honeypot.app.model.SimulatedMessage
import com.honeypot.app.model.SimulationSession
import com.honeypot.app.knowledge.KnowledgeBaseDatabase
import com.honeypot.app.preprocessor.SmsPreprocessor
import com.honeypot.app.trigger.TriggerEngine
import com.honeypot.app.engine.ScamDetectionEngineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SmsProcessingService : LifecycleService() {

    /**
     * Serialises SMS processing so only ONE message runs through the heavy
     * LLM/embedding pipeline at a time. This prevents RAM exhaustion when the
     * phone receives a burst of messages (e.g. 5 spam SMSes in quick succession)
     * which would otherwise start 5 simultaneous Gemma inference jobs.
     */
    private val processingMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - starting foreground")
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand - intent action: ${intent?.action}")

        // Safe parcelable extraction for both old and new Android APIs
        val incomingSms: IncomingSms? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("sms", IncomingSms::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("sms")
        }

        if (incomingSms == null) {
            Log.w(TAG, "Received start command with no SMS payload — ignoring.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        Log.d(TAG, "SMS received from: [${incomingSms.sender}] body: ${incomingSms.body.take(50)}")

        lifecycleScope.launch {
            try {
                // Serialise: wait for any currently-running SMS processing to finish
                // before starting the next one. This prevents concurrent LLM inference.
                processingMutex.withLock {
                    processSms(incomingSms)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS", e)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processSms(sms: IncomingSms) {
        com.honeypot.app.util.AppStatsManager.incrementScannedSms(this@SmsProcessingService)
        val filterPrefs = FilterPreferenceManager(this@SmsProcessingService)

        // STAGE 0: Check Contacts (if "Trust safe senders" is on and permission granted)
        if (filterPrefs.filterSafeCache && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (isPhoneNumberInContacts(sms.sender)) {
                Log.d(TAG, "Sender ${sms.sender} is a saved contact. Skipping processing.")
                return
            }
        }

        // STAGE 1: Lightweight pre-filter (pass user prefs so the Settings toggles are respected)
        val filterResult = SmsPreFilter.shouldIgnore(sms.sender, sms.body, filterPrefs)
        Log.d(TAG, "Filter result for [${sms.sender}]: $filterResult")
        // Drop both clean institutional messages AND suspicious-but-institutional ones.
        // The user enabling "Ignore institutional senders" means ALL DLT traffic is unwanted.
        if (filterResult == FilterResult.IGNORE || filterResult == FilterResult.SUSPICIOUS_INSTITUTIONAL) return

        // STAGE 2: Preprocess
        val preprocessed = withContext(Dispatchers.Default) {
            SmsPreprocessor.process(sms)
        }
        Log.d(TAG, "Preprocessed. Sender type: ${preprocessed.senderType}")

        // STAGE 3: Build conversation history from DB (survives service restarts)
        // We look up any prior live session for this sender and load the last 10 messages.
        // This replaces the old volatile in-memory conversationStore which was wiped on every
        // service kill — a common occurrence on MIUI/OxygenOS devices.
        val db = KnowledgeBaseDatabase.getInstance(this@SmsProcessingService)
        val history: List<String> = withContext(Dispatchers.IO) {
            val existingSession = db.knowledgeBaseDao().getLiveSessionByAttackerPhone(sms.sender)
            if (existingSession != null) {
                db.knowledgeBaseDao().getSimulationHistory(existingSession.id)
                    .takeLast(10)
                    .map { "[${it.senderType}]: ${it.content}" }
            } else {
                emptyList()
            }
        }

        // STAGE 4: Trigger engine - fast heuristic check
        val triggerResult = TriggerEngine.evaluate(preprocessed, history)
        Log.d(TAG, "Trigger result: shouldProcess=${triggerResult.shouldProcess}, triggers=${triggerResult.firedTriggers}")

        // Check DB for existing live session for this sender (db already initialised in Stage 3)
        val liveSession = withContext(Dispatchers.IO) {
            db.knowledgeBaseDao().getLiveSessionByAttackerPhone(sms.sender)
        }

        if (liveSession != null) {
            // ── Ongoing live honeypot: auto-analyse and generate reply ────────
            Log.d(TAG, "Ongoing live session found: ${liveSession.id} — auto-analysing")

            // 1. Save the incoming message
            val savedMsgId = withContext(Dispatchers.IO) {
                db.knowledgeBaseDao().insertSimulatedMessage(
                    SimulatedMessage(
                        sessionId = liveSession.id,
                        senderType = "A",
                        content = sms.body,
                        timestamp = sms.timestamp
                    )
                )
            }
            val messageId = savedMsgId.toInt()

            // 2. Show an immediate "message received" notification so the user
            //    knows instantly — BEFORE the engine does its heavy lifting.
            //    The AI-reply notification will replace this once analysis finishes.
            showLiveHoneypotNotification(liveSession.id, sms.sender, suggestedReply = null)

            // 3. Run the engine using the prior scam context (fast path)
            try {
                val engine = ScamDetectionEngineManager.getEngine(this@SmsProcessingService)
                val historyFromDb = withContext(Dispatchers.IO) {
                    db.knowledgeBaseDao().getSimulationHistory(liveSession.id)
                }
                val engineHistory = historyFromDb
                    .filter { it.id != messageId }
                    .map { "[${it.senderType}]: ${it.content}" }

                val priorScamLog = withContext(Dispatchers.IO) {
                    db.knowledgeBaseDao().getFirstScamLogForSession(liveSession.id)
                }

                val result = withContext(Dispatchers.Default) {
                    if (priorScamLog != null) {
                        engine.processConfirmedScamFollowUp(
                            sms = sms,
                            history = engineHistory,
                            originalScamType = priorScamLog.scamType,
                            originalConfidence = priorScamLog.confidence,
                            explicitSessionId = liveSession.id
                        )
                    } else {
                        engine.processMessage(sms, engineHistory, explicitSessionId = liveSession.id)
                    }
                }

                // 4. Generate suggested reply using existing consent (no dialog needed)
                val consentManager = ConsentPreferenceManager(this@SmsProcessingService)
                val cloudGranted = consentManager.getConsent(sms.sender) == true
                val scamType = result.scamResult?.scamType ?: priorScamLog?.scamType ?: "unknown"

                val suggestedReply = if (cloudGranted) {
                    val cloudHistory = engineHistory + "[A]: ${sms.body}"
                    CloudReplyGenerator.generateReply(cloudHistory, scamType)
                } else {
                    result.suggestedReply.takeIf { it.isNotBlank() }
                        ?: "Could you share more details before I proceed?"
                }

                // 5. Save analysis log with reply
                val scamResult = result.scamResult
                if (scamResult != null) {
                    val log = AiAnalysisLog(
                        messageId = messageId,
                        confidence = scamResult.confidence,
                        isScam = scamResult.isScam,
                        scamType = scamResult.scamType,
                        reason = scamResult.reasoning.takeIf { it.isNotBlank() } ?: "Follow-up message in confirmed scam session.",
                        firedTriggers = result.triggerResult?.firedTriggers?.joinToString(",") ?: "",
                        kbMatches = "",
                        evidenceFlagsJson = "[]",
                        suggestedReply = suggestedReply,
                        suggestedReplySource = if (cloudGranted) "cloud" else "local",
                        timestamp = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) { db.knowledgeBaseDao().insertAiAnalysisLog(log) }
                }

                // 6. Update notification with the AI-generated reply snippet
                showLiveHoneypotNotification(liveSession.id, sms.sender, suggestedReply)

            } catch (e: Exception) {
                Log.e(TAG, "Auto-analysis failed for follow-up", e)
                // Preliminary notification already shown — no further action needed
            }
            return
        }

        val shouldProceed = triggerResult.shouldProcess || filterResult == FilterResult.SUSPICIOUS_OTP_SPOOF
        if (!shouldProceed) {
            Log.d(TAG, "Trigger engine decided not to process and no suspicious filter matched.")
            return
        }

        // STAGE 5: Triggers fired — create session immediately, then run LLM
        if (triggerResult.firedTriggers.isNotEmpty() || filterResult == FilterResult.SUSPICIOUS_OTP_SPOOF) {
            val triggersToReport = triggerResult.firedTriggers.toMutableList()
            if (filterResult == FilterResult.SUSPICIOUS_OTP_SPOOF && "otp_spoof" !in triggersToReport) {
                triggersToReport.add("otp_spoof")
            }
            Log.d(TAG, "Suspicious triggers fired: $triggersToReport. Creating session and running engine...")

            // Create a live honeypot session immediately — don't wait for LLM
            // This ensures the session always appears in "View Live Honeypots"
            val newSession = SimulationSession(
                name = "[LIVE] Honeypot - ${sms.sender}",
                attackerPhone = sms.sender
            )
            val savedMsgId = withContext(Dispatchers.IO) {
                db.knowledgeBaseDao().insertSession(newSession)
                Log.d(TAG, "Live session created: ${newSession.id} for ${sms.sender}")
                db.knowledgeBaseDao().insertSimulatedMessage(
                    SimulatedMessage(
                        sessionId = newSession.id,
                        senderType = "A",
                        content = sms.body,
                        timestamp = sms.timestamp
                    )
                )
            }

            // Show the actionable "Let AI Handle It" notification right away
            showScamAlert(sms, "Suspicious message detected. Triggers: ${triggersToReport.joinToString()}", newSession.id)

            // Now run the heavy LLM engine in the background for deeper analysis
            // If it refutes the scam classification, we can archive the session
            try {
                val activeEngine = ScamDetectionEngineManager.getEngine(this@SmsProcessingService)
                val engineHistory = history.toList()
                val result = withContext(Dispatchers.Default) {
                    activeEngine.processMessage(sms, engineHistory, explicitSessionId = newSession.id)
                }

                val scamResult = result.scamResult
                Log.d(TAG, "Engine result: isScam=${scamResult?.isScam}, confidence=${scamResult?.confidence}")

                if (scamResult != null && !scamResult.isScam) {
                    // LLM says not a scam — archive the session so it doesn't clutter the live list
                    Log.d(TAG, "LLM disagrees: not a scam. Archiving session ${newSession.id}.")
                    withContext(Dispatchers.IO) {
                        db.knowledgeBaseDao().archiveSession(newSession.id)
                    }
                }
            } catch (e: Exception) {
                // Engine failed — keep the session, better safe than sorry
                Log.e(TAG, "Engine error (session kept): ${e.message}", e)
            }
        }
    }


    // ─── Notification helpers ─────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        val channelId = com.honeypot.app.HoneypotApp.CHANNEL_SERVICE
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SMS Honeypot Active")
            .setContentText("Monitoring incoming messages for scams...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        Log.d(TAG, "startForeground called with channel: $channelId")
    }

    private fun showScamAlert(sms: IncomingSms, reasoning: String, sessionId: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = com.honeypot.app.HoneypotApp.CHANNEL_SCAM_ALERT

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, sessionId.hashCode(),
            Intent(this, com.honeypot.app.ui.HoneypotConversationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("EXTRA_SESSION_ID", sessionId)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🚨 Scam Detected: ${sms.sender}")
            .setContentText(reasoning)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .addAction(0, "Let AI Handle It", pendingIntent)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(sms.sender.hashCode(), notif)
        Log.d(TAG, "Scam alert notification shown on channel: $channelId")
    }

    private fun showLiveHoneypotNotification(sessionId: String, sender: String, suggestedReply: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = com.honeypot.app.HoneypotApp.CHANNEL_SCAM_ALERT

        val pendingIntent = android.app.PendingIntent.getActivity(
            this, sessionId.hashCode(),
            Intent(this, com.honeypot.app.ui.HoneypotConversationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("EXTRA_SESSION_ID", sessionId)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = if (!suggestedReply.isNullOrBlank())
            "AI Reply ready: \"${suggestedReply.take(80)}…\""
        else
            "Tap to view conversation and respond."

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("📩 Scammer replied: $sender")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(sender.hashCode(), notif)
        Log.d(TAG, "Live honeypot notification shown. Reply ready: ${!suggestedReply.isNullOrBlank()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - removing foreground notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun isPhoneNumberInContacts(phoneNumber: String): Boolean {
        try {
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(phoneNumber))
            val projection = arrayOf(android.provider.ContactsContract.PhoneLookup._ID)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking contacts", e)
        }
        return false
    }

    companion object {
        private const val TAG = "SmsProcessingService"
        private const val NOTIFICATION_ID = 1001
    }
}

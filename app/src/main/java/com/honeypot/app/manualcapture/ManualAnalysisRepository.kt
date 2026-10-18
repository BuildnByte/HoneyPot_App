package com.honeypot.app.manualcapture

import android.content.Context
import android.util.Log
import com.honeypot.app.engine.ScamDetectionEngineManager
import com.honeypot.app.knowledge.KnowledgeBaseDatabase
import com.honeypot.app.manualcapture.model.ManualCaptureAnalysis
import com.honeypot.app.model.AiAnalysisLog
import com.honeypot.app.model.IncomingSms
import com.honeypot.app.model.SimulatedMessage
import com.honeypot.app.model.SimulationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates the full analysis pipeline for a confirmed manual screen capture.
 *
 * Flow:
 * 1. Atomically transition capture status REVIEWED → ANALYSING.
 * 2. Create a synthetic [SimulationSession] named "[CAPTURE] <sourceApp> <datetime>".
 * 3. Insert a [SimulatedMessage] with the selected text (senderType = "A").
 * 4. Build a synthetic [IncomingSms] and call [ScamDetectionEngineManager.getEngine].
 * 5. Persist [AiAnalysisLog] and [ManualCaptureAnalysis].
 * 6. Mark capture COMPLETE and set unviewedCaptureId in [ManualCapturePrefs].
 * 7. On failure → mark FAILED with a non-sensitive error.
 *
 * Privacy: cloud reply generation is explicitly not called for manual captures.
 */
class ManualAnalysisRepository(private val context: Context) {

    private val db by lazy { KnowledgeBaseDatabase.getInstance(context) }
    private val dao get() = db.knowledgeBaseDao()
    private val prefs by lazy { ManualCapturePrefs(context) }

    /**
     * Analyse a confirmed capture by ID.
     * Must be called on a background coroutine (Dispatchers.IO / Default).
     */
    suspend fun analyseCapture(captureId: String): Result<ManualCaptureAnalysis> =
        withContext(Dispatchers.IO) {
            try {
                val capture = dao.getManualCapture(captureId)
                    ?: return@withContext Result.failure(IllegalStateException("Capture not found: $captureId"))

                val selectedText = capture.selectedText.trim()
                if (selectedText.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Selected text is empty"))
                }

                // 1. Transition REVIEWED → ANALYSING
                dao.updateManualCaptureStatus(captureId, "ANALYSING")
                prefs.isAnalysing = true

                // 2. Grouping logic to find or create session
                var session: SimulationSession? = null
                
                // Step 1: Try to extract a phone number for grouping
                val cleanText = selectedText.replace(Regex("""[\s\-\(\)]"""), "")
                val phoneMatch = Regex("""\+?\d{10,15}""").find(cleanText)
                val extractedPhone = phoneMatch?.value

                if (extractedPhone != null) {
                    val existingSessions = dao.getOverlayCaptureSessions()
                    session = existingSessions.firstOrNull { it.attackerPhone == extractedPhone }
                }

                // Step 2 & 3: New session if no match found
                if (session == null) {
                    val dateLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(capture.capturedAt))
                    val sessionName = "[CAPTURE] ${capture.sourceApp} $dateLabel"
                    session = SimulationSession(
                        name = sessionName,
                        attackerPhone = extractedPhone ?: "MANUAL_CAPTURE",
                        attackerSenderId = "MANUAL_CAPTURE",
                        subscriptionId = -1,
                        serviceCenterAddress = "manual:${capture.sourceApp}",
                        notes = "Manual screenshot capture from ${capture.sourceApp}. SHA-256: ${capture.imageSha256}",
                        createdAt = capture.capturedAt
                    )
                    dao.insertSession(session)
                }

                // 3. Insert synthetic attacker message
                val message = SimulatedMessage(
                    senderType = "A",
                    content = selectedText,
                    timestamp = capture.capturedAt,
                    sessionId = session.id
                )
                val messageId = dao.insertSimulatedMessage(message).toInt()

                // 4. Update capture with sessionId
                dao.updateManualCaptureSessionId(captureId, session.id)

                // 5. Build IncomingSms and run through engine
                val sms = IncomingSms(
                    sender = "MANUAL_CAPTURE",
                    body = selectedText,
                    timestamp = capture.capturedAt,
                    subscriptionId = -1,
                    serviceCenterAddress = "manual:${capture.sourceApp}"
                )

                val engine = withContext(Dispatchers.Default) {
                    ScamDetectionEngineManager.getEngine(context)
                }
                val detectionResult = engine.processMessage(
                    sms = sms,
                    history = emptyList(),
                    explicitSessionId = session.id,
                    skipHoneypotReply = true
                )

                val scamResult = detectionResult.scamResult
                val isScam = scamResult?.isScam ?: false
                val confidence = scamResult?.confidence ?: 0f
                val scamType = scamResult?.scamType ?: "unknown"
                val reasoning = scamResult?.reasoning ?: "No strong scam indicators detected."
                val firedTriggers = detectionResult.triggerResult?.firedTriggers
                    ?.joinToString(", ") { it } ?: ""

                // 6. Generate contextual Safe Reply using Gemini API (or robust local fallback)
                val safeReply = if (isScam) {
                    com.honeypot.app.llm.CloudReplyGenerator.generateSafeReply(
                        messageText = selectedText,
                        scamType = scamType,
                        reasoning = reasoning
                    )
                } else {
                    "No suspicious patterns detected. Safe to continue normal conversation."
                }

                // 7. Persist AiAnalysisLog with safe reply
                val log = AiAnalysisLog(
                    messageId = messageId,
                    confidence = confidence,
                    isScam = isScam,
                    scamType = scamType,
                    reason = reasoning,
                    firedTriggers = firedTriggers,
                    kbMatches = detectionResult.kbResults.joinToString(", ") { it.entry.scamType },
                    evidenceFlagsJson = "[]",
                    suggestedReply = safeReply,
                    suggestedReplySource = "cloud_safe",
                    timestamp = System.currentTimeMillis()
                )
                dao.insertAiAnalysisLog(log)

                // 8. Persist ManualCaptureAnalysis
                val analysis = ManualCaptureAnalysis(
                    captureId = captureId,
                    isScam = isScam,
                    confidence = confidence,
                    scamType = scamType,
                    reasoning = reasoning,
                    firedTriggers = firedTriggers,
                    safeReply = safeReply,
                    completedAt = System.currentTimeMillis()
                )
                dao.insertManualCaptureAnalysis(analysis)

                // 9. Conditional Evidence Screenshot Storage:
                // Scams require evidence screenshots for proof & reporting.
                // Non-scams are discarded immediately to protect user privacy.
                if (isScam && capture.imagePath.isNotBlank()) {
                    try {
                        val cachedFile = java.io.File(capture.imagePath)
                        if (cachedFile.exists()) {
                            val permDir = java.io.File(context.filesDir, "evidence_screenshots").also { it.mkdirs() }
                            val permFile = java.io.File(permDir, "${capture.id}.png")
                            cachedFile.copyTo(permFile, overwrite = true)
                            if (permFile.exists()) {
                                cachedFile.delete()
                                dao.updateManualCaptureImagePath(captureId, permFile.absolutePath)
                                Log.d(TAG, "Scam detected: Persisted evidence screenshot to ${permFile.absolutePath}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist evidence screenshot", e)
                    }
                } else if (!isScam && capture.imagePath.isNotBlank()) {
                    try {
                        val cachedFile = java.io.File(capture.imagePath)
                        if (cachedFile.exists()) {
                            cachedFile.delete()
                            Log.d(TAG, "No scam detected: Discarded screenshot from cache")
                        }
                        dao.updateManualCaptureImagePath(captureId, "")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to discard cached screenshot", e)
                    }
                }

                // 10. Mark COMPLETE, update prefs
                dao.updateManualCaptureStatus(captureId, "COMPLETE")
                prefs.isAnalysing = false
                prefs.unviewedCaptureId = captureId

                Result.success(analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed for capture $captureId", e)
                try {
                    val msg = e.message?.take(200) ?: "Analysis failed"
                    dao.updateManualCaptureStatusWithError(captureId, "FAILED", msg)
                    val cap = dao.getManualCapture(captureId)
                    if (cap != null && cap.imagePath.isNotBlank()) {
                        val f = java.io.File(cap.imagePath)
                        if (f.exists() && f.parentFile?.name == "scan_cache") {
                            f.delete()
                        }
                    }
                } catch (_: Exception) {}
                prefs.isAnalysing = false
                Result.failure(e)
            }
        }

    companion object {
        private const val TAG = "ManualAnalysisRepository"
    }
}

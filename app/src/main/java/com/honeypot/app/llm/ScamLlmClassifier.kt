package com.honeypot.app.llm

import android.util.Log
import com.honeypot.app.model.ScamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy shim — Gemma has been removed.
 *
 * Classification is now handled by [com.honeypot.app.engine.OnnxScamClassifier]
 * (MuRIL BERT ONNX, on-device).
 *
 * Reply generation is now handled by [CloudReplyGenerator] + [ReplyProgressionEngine].
 *
 * This class is kept only to avoid any stale call-sites from crashing at runtime.
 * It will be fully deleted once all call-sites are confirmed updated.
 */
class ScamLlmClassifier(private val context: android.content.Context) {

    companion object {
        private const val TAG = "ScamLlmClassifier"
    }

    /** No-op — ONNX classifier is initialized via ScamDetectionEngine. */
    suspend fun initialize() {
        Log.d(TAG, "ScamLlmClassifier.initialize() is a no-op — using OnnxScamClassifier instead.")
    }

    /**
     * Deprecated — always returns a safe fallback.
     * Classification is done by OnnxScamClassifier.
     */
    suspend fun classify(smsBody: String): ScamResult = withContext(Dispatchers.Default) {
        Log.w(TAG, "ScamLlmClassifier.classify() called — this should not happen. " +
                   "OnnxScamClassifier should be used instead.")
        ScamResult(
            isScam    = false,
            scamType  = "unknown",
            confidence = 0f,
            reasoning = "Classification delegated to on-device BERT model."
        )
    }

    /**
     * Deprecated — reply generation now goes through CloudReplyGenerator.
     * Kept for any stale call-sites.
     */
    suspend fun generateReplyHint(
        conversationHistory: List<String>,
        scamType: String
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "generateReplyHint() → delegating to CloudReplyGenerator")
        CloudReplyGenerator.generateReply(conversationHistory, scamType)
    }

    /** No-op — no Gemma model to release. */
    fun close() {
        Log.d(TAG, "ScamLlmClassifier.close() — no resources to release (Gemma removed)")
    }
}

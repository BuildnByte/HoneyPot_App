package com.honeypot.app.llm

import android.util.Log
import com.honeypot.app.BuildConfig
import com.honeypot.app.model.ExtractedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud reasoning service: calls the Gemini API ONLY for the first detected
 * scam message in a session.
 *
 * Returns:
 *   1. reasoning   — 2-3 sentence human-readable explanation
 *   2. entities    — additional named entities extracted by the LLM
 *                    (supplements on-device ML Kit + rule-based NER)
 *
 * If the API is unavailable or the key is missing, a template-based fallback
 * is returned so the app always produces a [ReasoningResult].
 */
object CloudReasoningService {

    private const val TAG = "CloudReasoningService"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    data class ReasoningResult(
        val reasoning: String,
        val additionalEntities: List<ExtractedEntity> = emptyList()
    )

    /**
     * Analyzes the first scam message of a session.
     *
     * @param smsBody        Raw SMS text
     * @param scamType       Scam type resolved on-device (e.g. "KYC Phishing")
     * @param firedTriggers  Trigger group names that fired (from TriggerEngine)
     * @param kbMatchNames   Pattern descriptions from KB vector search
     */
    suspend fun analyzeFirstMessage(
        smsBody: String,
        scamType: String,
        firedTriggers: List<String>,
        kbMatchNames: List<String>
    ): ReasoningResult = withContext(Dispatchers.IO) {

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Log.w(TAG, "API key not configured — using template reasoning")
            return@withContext templateFallback(scamType, firedTriggers, kbMatchNames)
        }

        Log.i(TAG, "🤖 [GEMINI-REASONING] Requesting analysis for scam: '$scamType' | Message: \"${smsBody.take(80)}\"")
        try {
            val result = callGemini(apiKey, smsBody, scamType, firedTriggers, kbMatchNames)
            if (result != null) {
                Log.i(TAG, "✅ [GEMINI-REASONING] Output generated: \"${result.reasoning}\" (Entities found: ${result.additionalEntities.size})")
                result
            } else {
                Log.w(TAG, "⚠️ [GEMINI-REASONING] Null response from Gemini — falling back to template")
                templateFallback(scamType, firedTriggers, kbMatchNames)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [GEMINI-REASONING] Call failed — falling back to template", e)
            templateFallback(scamType, firedTriggers, kbMatchNames)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun callGemini(
        apiKey: String,
        smsBody: String,
        scamType: String,
        firedTriggers: List<String>,
        kbMatchNames: List<String>
    ): ReasoningResult? {

        val triggerSummary = firedTriggers.ifEmpty { listOf("none") }.joinToString(", ")
        val kbSummary = kbMatchNames.ifEmpty { listOf("none") }.joinToString(", ")

        val prompt = """
You are a scam analysis expert. Analyze this SMS message and respond ONLY with a JSON object.

SMS: "$smsBody"

Context from on-device analysis:
- Classified scam type: $scamType
- Triggered detection rules: $triggerSummary
- Similar known patterns: $kbSummary

Respond ONLY with this exact JSON format (no markdown fences, no extra text):
{
  "reasoning": "2-3 sentence plain English explanation of why this message is likely a scam. Cite specific red flags from the SMS text.",
  "entities": [
    {"type": "PERSON_NAME", "value": "full name exactly as in SMS"},
    {"type": "ORGANIZATION", "value": "org name exactly as in SMS"},
    {"type": "ROLE", "value": "role/title exactly as in SMS"},
    {"type": "LOCATION", "value": "location exactly as in SMS"},
    {"type": "ACCOUNT_NUMBER", "value": "account number exactly as in SMS"}
  ]
}

Only include entity types that appear EXPLICITLY in the SMS text. Omit entities array if none found.
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 300)
            })
        }

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MT))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            Log.i(TAG, "🤖 [GEMINI-REASONING] HTTP ${response.code} received (${body.length} bytes)")
            if (!response.isSuccessful) {
                Log.e(TAG, "❌ [GEMINI-REASONING] API error HTTP ${response.code}: $body")
                return null
            }

            return try {
                val json = JSONObject(body)
                val candidate = json.getJSONArray("candidates").getJSONObject(0)
                if (candidate.optString("finishReason") == "SAFETY") return null

                val text = candidate.getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text").trim()

                val first = text.indexOf('{')
                val last  = text.lastIndexOf('}')
                if (first == -1 || last <= first) return null
                val parsed = JSONObject(text.substring(first, last + 1))

                val reasoning = parsed.optString("reasoning", "").trim().ifBlank { return null }

                val entities = mutableListOf<ExtractedEntity>()
                parsed.optJSONArray("entities")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val type  = e.optString("type", "").trim()
                        val value = e.optString("value", "").trim()
                        if (type.isNotBlank() && value.isNotBlank()) {
                            entities.add(ExtractedEntity(type, value))
                        }
                    }
                }

                ReasoningResult(reasoning, entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Gemini reasoning response", e)
                null
            }
        }
    }

    private fun templateFallback(
        scamType: String,
        firedTriggers: List<String>,
        kbMatchNames: List<String>
    ): ReasoningResult {
        val sb = StringBuilder("This message was flagged as $scamType by the on-device AI (MuRIL BERT, 99.9% accuracy). ")
        if (firedTriggers.isNotEmpty()) {
            sb.append("Detection signals: ${firedTriggers.joinToString(", ")}. ")
        }
        if (kbMatchNames.isNotEmpty()) {
            sb.append("Similar to known scam patterns: ${kbMatchNames.joinToString(", ")}.")
        }
        return ReasoningResult(sb.toString())
    }
}

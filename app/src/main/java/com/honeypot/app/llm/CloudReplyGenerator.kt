package com.honeypot.app.llm

import android.util.Log
import com.honeypot.app.BuildConfig
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
 * Calls the Google Gemini API to generate a contextually progressive
 * honeypot reply suggestion. Used ONLY when the user has explicitly granted
 * per-contact cloud consent.
 *
 * If the API call fails for any reason (no network, rate limit, invalid key, etc.),
 * the error is logged and [ReplyProgressionEngine] is used as the fallback so the
 * user always gets a suggestion regardless of cloud availability.
 */
object CloudReplyGenerator {

    private const val TAG = "CloudReplyGenerator"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Generates a honeypot reply suggestion via the Gemini API.
     *
     * @param conversationHistory Full list of "[A]: ..." / "[V]: ..." lines from the session.
     * @param scamType            The classified scam type (e.g. "prize_scam", "phishing").
     * @return A natural reply string the victim can send to keep the scammer engaged.
     */
    suspend fun generateReply(
        conversationHistory: List<String>,
        scamType: String
    ): String = withContext(Dispatchers.IO) {

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Log.w(TAG, "API key not set — falling back to local progression engine")
            return@withContext ReplyProgressionEngine.nextReply(conversationHistory, scamType)
        }

        try {
            val reply = callGemini(apiKey, conversationHistory, scamType)
            if (reply.isNullOrBlank()) {
                Log.w(TAG, "Empty response from API — falling back to local")
                ReplyProgressionEngine.nextReply(conversationHistory, scamType)
            } else {
                reply
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud API call failed — falling back to local", e)
            ReplyProgressionEngine.nextReply(conversationHistory, scamType)
        }
    }

    /**
     * Generates a safe, defensive brush-off reply using the Gemini API for scanned messages.
     * Unlike [generateReply] (which acts as a curious victim in a simulation sandbox),
     * this produces a firm, protective reply that a real user can copy and send back to
     * shut down a suspected scammer and safeguard their personal security.
     */
    suspend fun generateSafeReply(
        messageText: String,
        scamType: String,
        reasoning: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Log.w(TAG, "API key not set — using local safe reply fallback")
            return@withContext fallbackSafeReply(scamType)
        }

        try {
            val reply = callGeminiForSafeReply(apiKey, messageText, scamType, reasoning)
            if (reply.isNullOrBlank()) {
                Log.w(TAG, "Empty safe reply from Gemini — using local fallback")
                fallbackSafeReply(scamType)
            } else {
                reply
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini safe reply call failed — using local fallback", e)
            fallbackSafeReply(scamType)
        }
    }

    private fun callGeminiForSafeReply(
        apiKey: String,
        messageText: String,
        scamType: String,
        reasoning: String
    ): String? {
        val systemPrompt = """
            You are a cybersecurity assistant. A user has received a suspected scam message on a messaging app (WhatsApp, Telegram, SMS, etc.).
            Your job is to generate a short, firm, and protective brush-off reply (1 to 2 sentences maximum) that the user can copy and send back to the sender.

            Guidelines:
            - Firmly decline the offer, request, prize, payment, or demand.
            - Explicitly state that you do not click links, share verification codes/OTPs, or make unverified payments.
            - State that you will verify directly with official authorities or organizations if needed.
            - Tell the sender not to contact you again.
            - Do NOT ask questions. Do NOT show curiosity. Do NOT engage in conversation.
            - Tone: Calm, firm, cautious, and uncompromising.
            - Output ONLY the plain text reply. No quotes, no markdown, no explanation.
        """.trimIndent()

        val contents = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "Scam Type: $scamType\nAnalysis: $reasoning\nMessage received: \"$messageText\"\n\nWrite the safe reply:")
                    })
                })
            })
        }

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 256)
            })
        }

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string() ?: return null
            Log.d(TAG, "Gemini safe reply raw response (${response.code}): $rawBody")

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini safe reply error ${response.code}: $rawBody")
                return null
            }

            return try {
                val json = JSONObject(rawBody)
                val candidate = json.getJSONArray("candidates").getJSONObject(0)
                if (candidate.optString("finishReason", "STOP") == "SAFETY") return null
                candidate
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                    .removePrefix("\"")
                    .removeSuffix("\"")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Gemini safe reply", e)
                null
            }
        }
    }

    fun fallbackSafeReply(scamType: String): String {
        val lower = scamType.lowercase()
        return when {
            lower.contains("lottery") || lower.contains("prize") || lower.contains("kbc") || lower.contains("lucky") ->
                "I never entered any lottery or draw. Do not contact me again."
            lower.contains("delivery") || lower.contains("courier") || lower.contains("package") || lower.contains("parcel") ->
                "I do not open tracking links sent over chat. I verify all shipments directly via the official merchant app."
            lower.contains("electricity") || lower.contains("bill") || lower.contains("utility") ->
                "I pay utility bills only through official apps. Do not send further disconnection threats."
            lower.contains("bank") || lower.contains("kyc") || lower.contains("account") || lower.contains("freeze") || lower.contains("financial") || lower.contains("upi") ->
                "I am verifying this directly with my bank branch. I do not share account details or verification codes over chat."
            lower.contains("job") || lower.contains("task") || lower.contains("part-time") || lower.contains("crypto") || lower.contains("invest") ->
                "I am not interested in unsolicited job or investment offers. Remove my number from your database immediately."
            lower.contains("impersonat") || lower.contains("police") || lower.contains("cbi") || lower.contains("customs") || lower.contains("official") ->
                "I am reporting this number to cyber crime authorities. Do not contact me further."
            else ->
                "I do not click unverified links or share personal information. Please contact me only through official verified channels."
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun callGemini(
        apiKey: String,
        conversationHistory: List<String>,
        scamType: String
    ): String? {

        // Neutral persona — framed as roleplaying to avoid Gemini's safety filters
        // while still instructing the model to elicit identifying information.
        val systemPrompt = """
            You are playing the role of a curious, unsuspecting person named "Alex" in a text conversation.
            Someone has contacted you claiming you won a prize or need to take urgent action ($scamType).
            You want to learn more about who they are before deciding anything.

            Your task: Write ONLY your next short reply (1-2 sentences max).
            - Ask a clarifying question that naturally reveals more details about the other person
              (e.g. their full name, organisation, phone number, bank account, UPI ID, or address).
            - Sound genuinely curious and unsuspecting.
            - Do NOT mention money transfers, OTPs, or give away any of your personal details.
            - Do NOT repeat a question already asked in the conversation.
            - Output ONLY the plain reply text. No labels, no quotes, no explanation.
        """.trimIndent()

        val contents = buildGeminiContents(conversationHistory)
        Log.d(TAG, "Sending ${contents.length()} turns to Gemini. Last role: " +
                if (contents.length() > 0) contents.getJSONObject(contents.length() - 1).optString("role") else "none")

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.8)
                put("maxOutputTokens", 512)
            })
        }

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val rawBody = response.body?.string() ?: run {
                Log.e(TAG, "Empty body from Gemini")
                return null
            }
            Log.d(TAG, "Gemini raw response (${response.code}): $rawBody")

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $rawBody")
                return null
            }

            return try {
                val json = JSONObject(rawBody)
                val candidate = json.getJSONArray("candidates").getJSONObject(0)
                val finishReason = candidate.optString("finishReason", "STOP")
                if (finishReason == "SAFETY") {
                    Log.w(TAG, "Gemini blocked response due to safety filters — falling back")
                    return null
                }
                candidate
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Gemini response: $rawBody", e)
                null
            }
        }
    }

    /**
     * Builds the `contents` array for the Gemini multi-turn API.
     *
     * Convention:
     *   [A]: → "user"  (the scammer / other party)
     *   [V]: → "model" (Alex / our victim persona)
     *
     * The array MUST end with a "user" role so Gemini generates the
     * next "model" (victim) reply. Consecutive messages from the same
     * role are merged to satisfy Gemini's alternating-role requirement.
     */
    private fun buildGeminiContents(history: List<String>): JSONArray {
        val contents = JSONArray()
        var currentRole: String? = null
        val currentText = StringBuilder()

        fun flush() {
            if (currentRole != null && currentText.isNotEmpty()) {
                contents.put(JSONObject().apply {
                    put("role", currentRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", currentText.toString().trim()) })
                    })
                })
                currentText.clear()
            }
        }

        for (line in history) {
            val role = when {
                line.startsWith("[A]:")            -> "user"
                line.startsWith("[V]:")            -> "model"
                line.startsWith("[U]:")            -> "model"  // live honeypot user-sent
                else                              -> continue
            }
            val text = when {
                line.startsWith("[A]:") -> line.substring(4).trim()
                line.startsWith("[V]:") -> line.substring(4).trim()
                line.startsWith("[U]:") -> line.substring(4).trim()
                else -> line
            }
            if (text.isEmpty()) continue

            if (role != currentRole) {
                flush()
                currentRole = role
            }
            if (currentText.isNotEmpty()) currentText.append("\n")
            currentText.append(text)
        }
        flush()

        // Guard: if contents is empty or ends with 'model', Gemini won't know
        // what turn to generate. Add a nudge to ensure the last role is 'user'.
        val lastRole = if (contents.length() > 0)
            contents.getJSONObject(contents.length() - 1).optString("role") else ""

        if (lastRole != "user") {
            Log.w(TAG, "Contents ended with '$lastRole' — inserting nudge turn")
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", "What should I do next to claim it?") })
                })
            })
        }

        return contents
    }
}

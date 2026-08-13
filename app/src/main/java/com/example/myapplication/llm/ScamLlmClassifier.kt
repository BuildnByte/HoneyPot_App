package com.example.myapplication.llm

import android.content.Context
import android.util.Log
import com.example.myapplication.model.ScamResult
import com.example.myapplication.model.ExtractedEntity
import com.example.myapplication.preprocessor.SmsPreprocessor
import com.example.myapplication.verification.FactCheckResult
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

data class ReasoningContext(
    val conversationText: String,
    val firedTriggers: List<String>,
    val triggerPriority: String,               // from TriggerEngine.Priority
    val patternMatches: List<Pair<String, Float>>,   // (patternDescription, similarity) from KB vector search
    val factCheckResults: List<FactCheckResult>,
    val extractedEntities: SmsPreprocessor.Entities
)

class ScamLlmClassifier(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val MODEL_NAME = "gemma3-1B-it-int4.task"

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext

        val modelFile = File(context.filesDir, MODEL_NAME)
        
        // 1. Check if we need to copy (or re-copy if corrupt)
        val assetSize = getAssetSize(MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() != assetSize) {
            val freeSpace = context.filesDir.usableSpace
            if (assetSize > freeSpace) {
                val errorMsg = "Insufficient disk space. Need ${assetSize / (1024 * 1024)}MB but only ${freeSpace / (1024 * 1024)}MB available."
                Log.e("ScamLlmClassifier", errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            Log.d("ScamLlmClassifier", "Model missing or size mismatch (${modelFile.length()} vs $assetSize). Copying...")
            modelFile.delete() // Clean up potentially corrupt file
            copyAssetToFilesDir(MODEL_NAME)
        }

        Log.d("ScamLlmClassifier", "Initializing LlmInference from: ${modelFile.absolutePath} (${modelFile.length() / (1024 * 1024)} MB)")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .build()

        try {
            llmInference = LlmInference.createFromOptions(context, options)
            Log.d("ScamLlmClassifier", "LlmInference ready.")
        } catch (e: Exception) {
            Log.e("ScamLlmClassifier", "Engine rejected model format. Ensure this is a MediaPipe-converted model file.", e)
            throw e
        }
    }

    private fun getAssetSize(fileName: String): Long {
        return try {
            context.assets.openFd(fileName).use { it.length }
        } catch (e: Exception) {
            -1L // Compressed or missing
        }
    }

    private fun copyAssetToFilesDir(fileName: String) {
        val tempFile = File(context.filesDir, "${fileName}.tmp")
        context.assets.open(fileName).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile.renameTo(File(context.filesDir, fileName))
    }

    suspend fun classify(
        context: ReasoningContext
    ): ScamResult = withContext(Dispatchers.Default) {
        val currentLlm = llmInference ?: throw IllegalStateException("LlmInference not initialized.")

        val prompt = buildStructuredPrompt(context)
        val rawResponse = currentLlm.generateResponse(prompt)
        
        parseResponse(rawResponse)
    }

    private fun buildStructuredPrompt(context: ReasoningContext): String {
        val behaviorSignals = if (context.firedTriggers.isEmpty()) "No behavioral signals detected."
            else "Detected: ${context.firedTriggers.joinToString(", ")}. Priority: ${context.triggerPriority}"

        val patternMatches = if (context.patternMatches.isEmpty()) "None matched."
            else context.patternMatches.joinToString("\n") { "- ${it.first} (similarity: ${String.format(java.util.Locale.getDefault(), "%.0f%%", it.second * 100)})" }

        val factCheckResults = if (context.factCheckResults.isEmpty()) "No verifiable indicators found in message."
            else context.factCheckResults.joinToString("\n") { 
                "- ${it.indicatorType} '${it.indicatorValue}': ${it.verdict} (confidence: ${it.confidence}, source: ${it.source})" 
            }

        return """You are a scam detection AI. Analyze this SMS:

CONVERSATION:
${context.conversationText}

BEHAVIORAL SIGNALS:
$behaviorSignals

KNOWN SCAM PATTERNS MATCHED:
$patternMatches

EXTERNAL FACT-CHECK RESULTS:
$factCheckResults

Weigh external fact-check results and prior evidence matches more heavily than pattern similarity alone. A MALICIOUS verdict from fact-check should strongly increase confidence; conflicting signals should be explained in reasoning.
Extract any mentioned Names, Roles (e.g. Manager), Companies, or Account Numbers into the extractedEntities list.

Your output must be ONLY the JSON object, with no other text.
Do not include "JSON SCHEMA:" or markdown fences.
Required JSON format:
{"isScam": true, "scamType": "phishing", "confidence": 0.9, "reasoning": "Detailed explanation here.", "extractedEntities": [{"type": "NAME", "value": "John"}, {"type": "ROLE", "value": "Manager"}], "evidenceFlags": []}
"""
    }

    private fun parseResponse(raw: String): ScamResult {
        Log.d("ScamLlmClassifier", "RAW LLM OUTPUT >>> $raw <<<")

        // Strategy 1: strip fences + extract JSON block + lenient decode
        tryStrategy1(raw)?.let { return it }

        // Strategy 2: aggressive sanitization of common Gemma JSON defects
        tryStrategy2(raw)?.let { return it }

        // Strategy 3: regex field extraction — last resort, always produces a result
        return extractFieldsWithRegex(raw)
    }

    /** Strategy 1: basic fence stripping + JSON-block extraction. */
    private fun tryStrategy1(raw: String): ScamResult? = runCatching {
        var cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(cleaned)?.value
            ?: return null
        json.decodeFromString<ScamResult>(jsonStr)
    }.getOrNull()

    /** Strategy 2: aggressive sanitization to repair common Gemma defects. */
    private fun tryStrategy2(raw: String): ScamResult? = runCatching {
        var cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(cleaned)?.value
            ?: return null
        val sanitized = sanitizeJson(jsonStr)
        Log.d("ScamLlmClassifier", "Strategy-2 sanitized JSON >>> $sanitized <<<")
        json.decodeFromString<ScamResult>(sanitized)
    }.getOrNull()

    /**
     * Repairs the most common defects emitted by Gemma 3 1B:
     *  - Literal (unescaped) newlines / tabs inside string values
     *  - Consecutive string tokens without a separating comma  ("a" "b" → "a", "b")
     *  - Missing comma between two JSON values ( } { , ] [ , }: {...} )
     *  - Trailing commas before ] or }
     *  - Truncated / unterminated strings at end of input
     */
    private fun sanitizeJson(input: String): String {
        var s = input

        // 1. Replace literal newlines / tabs inside string values with a space
        //    We do this globally – it's safe because JSON strings must escape them anyway.
        s = s.replace("\r\n", " ").replace("\r", " ").replace("\n", " ").replace("\t", " ")

        // 2. Collapse multiple consecutive spaces inside the JSON (keeps it readable)
        s = s.replace(Regex("  +"), " ")

        // 3. Fix missing comma between two adjacent quoted strings: "foo" "bar" → "foo", "bar"
        s = s.replace(Regex("""(")\s+(\")"""), "$1, $2")

        // 4. Fix missing comma between a value/closing bracket and the next key/value:
        //    e.g.  0.9 "reasoning"  →  0.9, "reasoning"
        //          true "scamType"  →  true, "scamType"
        //          ] "evidenceFlags" → ], "evidenceFlags"
        //          } "key" → }, "key"   (shouldn't normally occur but Gemma does it)
        s = s.replace(Regex("""([}\]"0-9])\s+(\")""")) { m ->
            "${m.groupValues[1]}, ${m.groupValues[2]}"
        }

        // 5. Remove trailing commas before ] or }
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")

        // 6. If the string ends abruptly in the middle of a string value, close it
        if (s.count { it == '"' } % 2 != 0) s += "\""

        // 7. Close any unclosed arrays or objects (truncated output)
        val opens = s.count { it == '{' } - s.count { it == '}' }
        val openArr = s.count { it == '[' } - s.count { it == ']' }
        repeat(openArr.coerceAtLeast(0)) { s += "]" }
        repeat(opens.coerceAtLeast(0)) { s += "}" }

        return s.trim()
    }

    /**
     * Strategy 3 – last-resort regex extraction.
     * Pulls individual scalar fields directly from the raw text; never throws.
     */
    private fun extractFieldsWithRegex(raw: String): ScamResult {
        Log.w("ScamLlmClassifier", "Falling back to regex field extraction")

        fun findBool(key: String): Boolean? =
            Regex(""""$key"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.get(1)?.trim()?.equals("true", ignoreCase = true)

        fun findString(key: String): String? =
            Regex(""""$key"\s*:\s*"([^"]*?)"""")
                .find(raw)?.groupValues?.get(1)?.trim()

        fun findFloat(key: String): Float? =
            Regex(""""$key"\s*:\s*([0-9]*\.?[0-9]+)""")
                .find(raw)?.groupValues?.get(1)?.toFloatOrNull()

        fun findEntityList(key: String): List<ExtractedEntity> {
            val listBlock = Regex(""""$key"\s*:\s*\[([^\]]*)]""").find(raw)?.groupValues?.get(1) ?: return emptyList()
            return Regex("""\{\s*"type"\s*:\s*"([^"]+)"\s*,\s*"value"\s*:\s*"([^"]+)"\s*}""")
                .findAll(listBlock)
                .map { m -> ExtractedEntity(m.groupValues[1], m.groupValues[2]) }
                .toList()
        }

        val isScam     = findBool("isScam") ?: false
        val scamType   = findString("scamType") ?: "unknown"
        val confidence = findFloat("confidence") ?: if (isScam) 0.5f else 0.0f
        val reasoning  = findString("reasoning")
            ?: raw.take(300).replace(Regex("[\"{}\\[\\]]"), "").trim()
                .ifBlank { "Could not parse model response." }
        val entities   = findEntityList("extractedEntities")

        Log.d("ScamLlmClassifier", "Regex extraction → isScam=$isScam type=$scamType conf=$confidence")
        return ScamResult(isScam, scamType, confidence, reasoning, entities, emptyList())
    }

    /**
     * Generates a suggested honeypot reply for the user to send to the suspected scammer.
     *
     * ## Strategy (hybrid)
     *
     * **First reply** (no prior victim messages in the session):
     *   → Use the Gemma LLM. The first message has no context-tracking problem.
     *
     * **Follow-up replies** (victim has already sent at least one reply):
     *   → Use [ReplyProgressionEngine] instead of the LLM.
     *   Gemma 1B cannot reliably track what was already asked and produce a meaningfully
     *   different follow-up — this is a hard model-size limitation, not fixable with prompting.
     *   The progression engine detects what entities the attacker has revealed and what
     *   topics the victim already asked about, then deterministically selects the next
     *   best question from a curated script.
     *
     * @param conversationHistory Full list of "[A]: ..." and "[V]: ..." lines from the session.
     * @param scamType            The classified scam type (e.g. "phishing", "prize_scam").
     */
    suspend fun generateReplyHint(
        conversationHistory: List<String>,
        scamType: String
    ): String = withContext(Dispatchers.Default) {

        val recentHistory = conversationHistory.takeLast(15)

        // Determine if the victim has already sent any replies in this session.
        val victimHasReplied = recentHistory.any { it.startsWith("[V]:") }

        // ── Follow-up: use the deterministic progression engine ───────────────
        if (victimHasReplied) {
            Log.d("ScamLlmClassifier", "Reply hint → using ReplyProgressionEngine (turn 2+)")
            return@withContext ReplyProgressionEngine.nextReply(recentHistory, scamType)
        }

        // ── First reply: use the LLM ──────────────────────────────────────────
        val currentLlm = llmInference
            ?: return@withContext fallbackReply(scamType)

        val lastAttackerMessage = recentHistory
            .lastOrNull { it.startsWith("[A]:") }
            ?.removePrefix("[A]:")
            ?.trim()
            ?: return@withContext fallbackReply(scamType)

        val prompt = buildString {
            appendLine("Someone sent you this suspicious message:")
            appendLine("\"$lastAttackerMessage\"")
            appendLine()
            appendLine("Write one short, curious reply (1-2 sentences) asking for more details.")
            appendLine("Do not mention your name. Do not share personal info.")
            append("Ask only about their details (who they are, their contact, how to verify).")
        }

        Log.d("ScamLlmClassifier", "Reply hint → LLM first reply, msg: $lastAttackerMessage")

        return@withContext try {
            val raw = currentLlm.generateResponse(prompt).trim()
            val cleaned = raw
                .removePrefix("\"").removeSuffix("\"")
                .removePrefix("'").removeSuffix("'")
                .lines().firstOrNull { line ->
                    line.isNotBlank() &&
                    !line.startsWith("SCAMMER:") &&
                    !line.startsWith("[A]:") &&
                    !line.startsWith("[V]:")
                }
            if (cleaned.isNullOrBlank()) fallbackReply(scamType) else cleaned
        } catch (e: Exception) {
            Log.e("ScamLlmClassifier", "Failed to generate reply hint", e)
            fallbackReply(scamType)
        }
    }


    /**
     * Returns a contextually relevant fallback reply when the model fails or produces no output.
     * Keyed on scamType so it's at least loosely appropriate.
     */
    private fun fallbackReply(scamType: String): String = when {
        scamType.contains("prize", ignoreCase = true) ||
        scamType.contains("lottery", ignoreCase = true) ||
        scamType.contains("kbc",     ignoreCase = true) ->
            "That sounds great! Could you tell me your full name and the exact process to claim it?"
        scamType.contains("kyc",      ignoreCase = true) ||
        scamType.contains("bank",     ignoreCase = true) ||
        scamType.contains("account",  ignoreCase = true) ->
            "Sure, which bank branch should I visit and what documents do I need to bring?"
        scamType.contains("phishing", ignoreCase = true) ->
            "Can you resend the link? It's not opening. Also, who should I contact if I have questions?"
        else ->
            "Could you share more details? I want to make sure I have everything correct before proceeding."
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}

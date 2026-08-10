package com.example.myapplication.llm

import android.content.Context
import android.util.Log
import com.example.myapplication.model.ScamResult
import com.example.myapplication.vectorsearch.VectorSearchEngine
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ScamLlmClassifier(context: Context) {

    private val llmInference: LlmInference
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val modelPath = context.filesDir.absolutePath + "/gemma3n_1b_int4.task"

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(512)
            .build()

        llmInference = LlmInference.createFromOptions(context, options)
    }

    suspend fun classify(
        conversationText: String,
        retrievedKbEntries: List<VectorSearchEngine.SearchResult>,
        firedTriggers: List<String>
    ): ScamResult = withContext(Dispatchers.Default) {

        val prompt = buildPrompt(conversationText, retrievedKbEntries, firedTriggers)

        val rawResponse = llmInference.generateResponse(prompt)

        parseResponse(rawResponse)
    }

    private fun buildPrompt(
        conversation: String,
        kbEntries: List<VectorSearchEngine.SearchResult>,
        triggers: List<String>
    ): String {
        val kbContext = if (kbEntries.isEmpty()) {
            "No matching patterns found."
        } else {
            kbEntries.joinToString("\n") { result ->
                "Pattern: ${result.entry.patternDescription}\n" +
                        "Severity: ${result.entry.severity}/5\n" +
                        "Example: ${result.entry.exampleTriggerText}\n" +
                        "Strategy: ${result.entry.responseStrategy}\n" +
                        "Similarity: ${"%.2f".format(result.similarity)}"
            }
        }

        val triggerList = triggers.ifEmpty { listOf("none") }.joinToString(", ")

        return """You are a scam detection AI. Analyse the following SMS conversation.

KNOWLEDGE BASE MATCHES:
$kbContext

TRIGGERED SIGNALS: $triggerList

CONVERSATION:
$conversation

Reply ONLY with this JSON, no other text:
{"isScam":true/false,"scamType":"kyc_fraud|impersonation|lottery|phishing|job_scam|safe","confidence":0.0,"reasoning":"one sentence","extractedEntities":[]}"""
    }

    private fun parseResponse(raw: String): ScamResult {
        return try {
            val jsonRegex = Regex("""\{[\s\S]*?\}""")
            val jsonStr = jsonRegex.find(raw)?.value
                ?: throw IllegalStateException("No JSON found in response")
            json.decodeFromString<ScamResult>(jsonStr)
        } catch (e: Exception) {
            Log.e("ScamLlmClassifier", "Failed to parse LLM response: ${e.message}")
            ScamResult(
                isScam = false,
                scamType = "parse_error",
                confidence = 0f,
                reasoning = "LLM response parse failed",
                extractedEntities = emptyList()
            )
        }
    }

    fun close() {
        llmInference.close()
    }
}
package com.example.myapplication.llm

import android.content.Context
import android.util.Log
import com.example.myapplication.model.ScamResult
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

Your output must be ONLY the JSON object, with no other text.
Do not include "JSON SCHEMA:" or markdown fences.
Required JSON format:
{"isScam": true, "scamType": "phishing", "confidence": 0.9, "reasoning": "Detailed explanation here.", "extractedEntities": [], "evidenceFlags": []}
"""
    }

    private fun parseResponse(raw: String): ScamResult {
        Log.d("ScamLlmClassifier", "RAW LLM OUTPUT >>> $raw <<<")
        return try {
            // Strip markdown code fences if present
            var cleaned = raw.trim()
            cleaned = cleaned.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            // Extract the JSON object, allowing '.' to match newlines
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(cleaned)?.value
                ?: throw Exception("No JSON object found in response")

            // Sanitize control characters (raw newlines/tabs) that break JSON string values,
            // since small models sometimes emit literal newlines inside string fields
            // instead of escaped \n
            val sanitized = jsonStr
                .replace(Regex("(?<=\"[^\"]{0,300})\\n(?=[^\"]{0,300}\")"), " ")

            json.decodeFromString<ScamResult>(sanitized)
        } catch (e: Exception) {
            Log.e("ScamLlmClassifier", "Failed to parse LLM response", e)
            ScamResult(false, "error", 0f, "Parse failed", emptyList(), emptyList())
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}

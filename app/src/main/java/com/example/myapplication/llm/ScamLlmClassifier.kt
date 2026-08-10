package com.example.myapplication.llm

import android.content.Context
import android.util.Log
import com.example.myapplication.model.ScamResult
import com.example.myapplication.vectorsearch.VectorSearchEngine
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class ScamLlmClassifier(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val MODEL_NAME = "gemma-2b-it-cpu-int4.bin"

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (llmInference != null) return@withContext

        val modelFile = File(context.filesDir, MODEL_NAME)
        
        // 1. Check if we need to copy (or re-copy if corrupt)
        val assetSize = getAssetSize(MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() != assetSize) {
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
            Log.e("ScamLlmClassifier", "Engine rejected model format. Ensure this is a MediaPipe-converted .bin file.", e)
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
        conversationText: String,
        retrievedKbEntries: List<VectorSearchEngine.SearchResult>,
        firedTriggers: List<String>
    ): ScamResult = withContext(Dispatchers.Default) {
        val currentLlm = llmInference ?: throw IllegalStateException("LlmInference not initialized.")

        val prompt = buildPrompt(conversationText, retrievedKbEntries, firedTriggers)
        val rawResponse = currentLlm.generateResponse(prompt)
        
        parseResponse(rawResponse)
    }

    private fun buildPrompt(
        conversation: String,
        kbEntries: List<VectorSearchEngine.SearchResult>,
        triggers: List<String>
    ): String {
        val kbContext = if (kbEntries.isEmpty()) "No matching patterns." else 
            kbEntries.joinToString("\n") { "Pattern: ${it.entry.patternDescription}" }

        return """You are a scam detection AI. Analyze this SMS:
KNOWLEDGE: $kbContext
SIGNALS: ${triggers.joinToString(", ")}
CONVERSATION: $conversation
Reply ONLY with JSON: {"isScam":true/false,"scamType":"...","confidence":0.0,"reasoning":"...","extractedEntities":[]}"""
    }

    private fun parseResponse(raw: String): ScamResult {
        return try {
            val jsonStr = Regex("""\{.*\}""").find(raw)?.value ?: throw Exception()
            json.decodeFromString<ScamResult>(jsonStr)
        } catch (e: Exception) {
            ScamResult(false, "error", 0f, "Parse failed", emptyList())
        }
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}

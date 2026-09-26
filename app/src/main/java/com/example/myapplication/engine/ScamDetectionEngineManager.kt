package com.example.myapplication.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton manager to ensure only one instance of the heavy ScamDetectionEngine
 * (and its 1.5GB LLM model) exists at any time.
 */
object ScamDetectionEngineManager {

    private val mutex = Mutex()
    private var engine: ScamDetectionEngine? = null

    suspend fun getEngine(context: Context): ScamDetectionEngine = mutex.withLock {
        engine?.let { return@withLock it }

        Log.d("EngineManager", "Initializing ScamDetectionEngine singleton...")
        val newEngine = ScamDetectionEngine(context.applicationContext)
        newEngine.initialize()
        engine = newEngine
        newEngine
    }

    fun closeEngine() {
        engine?.close()
        engine = null
    }
}

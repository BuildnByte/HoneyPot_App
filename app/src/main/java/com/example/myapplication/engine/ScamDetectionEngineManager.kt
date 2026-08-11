package com.example.myapplication.engine

import android.content.Context
import android.util.Log

/**
 * Singleton manager to ensure only one instance of the heavy ScamDetectionEngine
 * (and its 1.5GB LLM model) exists at any time.
 */
object ScamDetectionEngineManager {

    private var engine: ScamDetectionEngine? = null
    private var isInitializing = false

    suspend fun getEngine(context: Context): ScamDetectionEngine {
        synchronized(this) {
            engine?.let { return it }
            
            if (isInitializing) {
                // Wait for existing initialization to finish (simplified for now)
                // In a real app, use a Mutex or a Flow/Deferred
                Log.w("EngineManager", "Engine is already initializing...")
            }
            
            isInitializing = true
        }

        return try {
            val newEngine = ScamDetectionEngine(context.applicationContext)
            newEngine.initialize()
            synchronized(this) {
                engine = newEngine
                isInitializing = false
            }
            newEngine
        } catch (e: Exception) {
            synchronized(this) { isInitializing = false }
            throw e
        }
    }

    fun closeEngine() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }
}

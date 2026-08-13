package com.example.myapplication.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_analysis_logs")
data class AiAnalysisLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val messageId: Int, // Refers to SimulatedMessage.id
    val confidence: Float,
    val isScam: Boolean,
    val scamType: String = "unknown",     // LLM-classified scam category
    val reason: String,
    val firedTriggers: String, // Comma separated list
    val kbMatches: String, // JSON or formatted summary of KB hits
    val evidenceFlagsJson: String = "[]", // JSON-serialized EvidenceFlag list
    val suggestedReply: String = "",    // Honeypot reply suggestion (only set when isScam=true)
    val timestamp: Long
)

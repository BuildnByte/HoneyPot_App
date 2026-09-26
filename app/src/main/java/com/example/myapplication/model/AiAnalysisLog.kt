package com.example.myapplication.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_analysis_logs")
data class AiAnalysisLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val messageId: Int,
    val confidence: Float,
    val isScam: Boolean,
    val scamType: String = "unknown",
    val reason: String,
    val firedTriggers: String,
    val kbMatches: String,
    val evidenceFlagsJson: String = "[]",
    val suggestedReply: String = "",
    /** "local" = on-device engine/progression, "cloud" = OpenAI GPT-4o-mini */
    val suggestedReplySource: String = "local",
    val timestamp: Long
)

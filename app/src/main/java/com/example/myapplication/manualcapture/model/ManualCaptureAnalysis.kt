package com.example.myapplication.manualcapture.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable user-facing analysis summary for a completed manual capture.
 *
 * Stored even when [isScam] is false so the result screen can show a
 * clear safe/low-risk explanation rather than silently disappearing.
 */
@Entity(tableName = "manual_capture_analyses")
data class ManualCaptureAnalysis(
    @PrimaryKey val captureId: String,
    val isScam: Boolean,
    val confidence: Float,
    val scamType: String,
    val reasoning: String,
    /** Comma-separated list of trigger names that fired */
    val firedTriggers: String,
    val completedAt: Long
)

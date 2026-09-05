package com.example.myapplication.manualcapture.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents one user-initiated manual screenshot capture.
 *
 * Status lifecycle: CAPTURED → REVIEWED → ANALYSING → COMPLETE | FAILED
 * A review that is cancelled or discarded transitions to DISCARDED.
 */
@Entity(tableName = "manual_captures")
data class ManualCapture(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** Source app name, e.g. "WhatsApp", "Telegram", "Instagram", "Other" */
    val sourceApp: String,
    /** Absolute path inside app-private filesDir/evidence_captures/<uuid>.png */
    val imagePath: String,
    /** Hex-encoded SHA-256 digest of the PNG file for integrity verification */
    val imageSha256: String,
    /** Epoch millis when the screen was captured */
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    /**
     * State machine value. One of:
     * CAPTURED, REVIEWED, ANALYSING, COMPLETE, FAILED, DISCARDED
     */
    val status: String,
    /** Text the user confirmed as incoming messages for analysis */
    val selectedText: String = "",
    /**
     * Layout hint used by [MessageSideClassifier].
     * "RIGHT_IS_MINE" (default) or "LEFT_IS_MINE" for RTL / reversed layouts.
     */
    val senderLayout: String = "RIGHT_IS_MINE",
    /** ID of the SimulationSession created for this capture (set after ANALYSING starts) */
    val analysisSessionId: String? = null,
    /** Non-sensitive error message stored when status = FAILED */
    val errorMessage: String? = null,
    /** Epoch millis when the user opened ManualAnalysisActivity for this result */
    val viewedAt: Long? = null
)

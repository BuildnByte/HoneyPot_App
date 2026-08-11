package com.example.myapplication.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scam_evidence")
data class ScamEvidence(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val sourceSender: String,
    val entityType: String, // "UPI_ID", "PHONE", "ACCOUNT", "URL", "AMOUNT"
    val entityValue: String,
    val sourceMessageText: String,
    val analysisLogId: Int?,
    val timestamp: Long
)

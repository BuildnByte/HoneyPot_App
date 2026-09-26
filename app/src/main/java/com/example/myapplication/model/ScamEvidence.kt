package com.example.myapplication.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scam_evidence",
    // DB-level guard: the same entity value from the same session can only be stored once.
    // Combined with OnConflictStrategy.IGNORE in the DAO, duplicate inserts are silently dropped.
    indices = [Index(value = ["sessionId", "entityType", "entityValue"], unique = true)]
)
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

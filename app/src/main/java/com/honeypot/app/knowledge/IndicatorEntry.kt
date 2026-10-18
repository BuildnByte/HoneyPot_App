package com.honeypot.app.knowledge

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "indicator_kb")
data class IndicatorEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val indicatorType: String, // "DOMAIN", "UPI_ID", "PHONE", "ACCOUNT"
    val indicatorValue: String,
    val verdict: String, // "KNOWN_MALICIOUS", "KNOWN_SAFE", "UNVERIFIED"
    val source: String,
    val confidence: Float,
    val notes: String,
    val lastCheckedTimestamp: Long
)

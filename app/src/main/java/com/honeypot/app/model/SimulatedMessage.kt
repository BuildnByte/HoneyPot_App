package com.honeypot.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "simulated_messages")
data class SimulatedMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderType: String, // "A" for Attacker, "V" for Victim
    val content: String,
    val timestamp: Long, // Epoch millis
    val sessionId: String
)

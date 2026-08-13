package com.example.myapplication.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "simulation_sessions")
data class SimulationSession(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val attackerPhone: String,
    val attackerSenderId: String = "",
    val subscriptionId: Int = -1,
    val serviceCenterAddress: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)

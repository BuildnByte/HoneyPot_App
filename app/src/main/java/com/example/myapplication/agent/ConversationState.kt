package com.example.myapplication.agent

data class ConversationState(
    val sender: String,
    val messages: MutableList<String> = mutableListOf(),

    var scamType: String? = null,
    var confidence: Float = 0f,

    var currentStage: ScamStage = ScamStage.INITIAL_CONTACT,

    val extractedIntelligence: MutableMap<String, String> = mutableMapOf(),

    val completedObjectives: MutableSet<AgentObjective> = mutableSetOf(),

    val pendingObjectives: MutableSet<AgentObjective> = mutableSetOf(),

    val actionsTaken: MutableList<AgentAction> = mutableListOf(),

    var isActive: Boolean = true
)
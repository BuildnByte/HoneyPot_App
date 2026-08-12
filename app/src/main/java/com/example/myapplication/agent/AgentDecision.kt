package com.example.myapplication.agent

data class AgentDecision(
    val action: AgentAction,
    val reason: String,
    val objective: AgentObjective?,
    val stage: ScamStage
)
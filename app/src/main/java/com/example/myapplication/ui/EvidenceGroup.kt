package com.example.myapplication.ui

import com.example.myapplication.model.ScamEvidence

data class EvidenceGroup(
    val sourceSender: String,
    val sourceMessageText: String,
    val timestamp: Long,
    val items: List<ScamEvidence>
)

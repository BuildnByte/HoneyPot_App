package com.honeypot.app.ui

import com.honeypot.app.model.ScamEvidence

data class EvidenceGroup(
    val sourceSender: String,
    val sourceMessageText: String,
    val timestamp: Long,
    val items: List<ScamEvidence>
)

package com.honeypot.app.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class EvidenceFlag(
    val indicator: String = "",
    val verdict: String = "UNVERIFIED",          // "MALICIOUS", "SAFE", "UNVERIFIED", "PATTERN_MATCH"
    val confidenceLevel: String = "LOW",         // "HIGH", "MEDIUM", "LOW"
    val type: String = "",
    val confidence: String = ""
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ExtractedEntity(
    val type: String = "", // e.g. "NAME", "ROLE", "COMPANY", "ACCOUNT_NUMBER"
    val value: String = ""
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ScamResult(
    val isScam: Boolean = false,
    val scamType: String = "unknown",
    val confidence: Float = 0f,
    val reasoning: String = "",
    val extractedEntities: List<ExtractedEntity> = emptyList(),
    val evidenceFlags: List<EvidenceFlag> = emptyList()
)

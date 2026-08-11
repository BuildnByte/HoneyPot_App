package com.example.myapplication.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class EvidenceFlag(
    val indicator: String,
    val verdict: String,          // "MALICIOUS", "SAFE", "UNVERIFIED", "PATTERN_MATCH"
    val confidenceLevel: String   // "HIGH", "MEDIUM", "LOW"
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ScamResult(
    val isScam: Boolean,
    val scamType: String,
    val confidence: Float,
    val reasoning: String,
    val extractedEntities: List<String>,
    val evidenceFlags: List<EvidenceFlag> = emptyList()
)

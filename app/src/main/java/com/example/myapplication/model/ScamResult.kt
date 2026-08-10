package com.example.myapplication.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class ScamResult(
    val isScam: Boolean,
    val scamType: String,
    val confidence: Float,
    val reasoning: String,
    val extractedEntities: List<String>
)
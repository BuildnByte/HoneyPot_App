package com.honeypot.app.engine

import com.honeypot.app.trigger.TriggerEngine
import com.honeypot.app.vectorsearch.VectorSearchEngine

/**
 * Derives a human-readable scam subtype from available on-device signals
 * (TriggerEngine groups + KnowledgeBase vector-search matches).
 *
 * Priority order:
 *   1. KB top match with similarity ≥ 0.65  → use its stored scamType
 *   2. Compound trigger rules               → more specific labels
 *   3. Highest-priority single trigger rule → generic label
 *   4. Fallback                             → "Scam"
 */
object ScamTypeResolver {

    // Single trigger → label (evaluated after compound rules)
    private val TRIGGER_TYPE_MAP = linkedMapOf(
        "credential_request"      to "OTP / Credential Theft",
        "authority_impersonation" to "Authority Impersonation",
        "account_threat"          to "Account / KYC Threat",
        "financial_lure"          to "Lottery / Prize Scam",
        "link_lure"               to "Phishing",
        "upi_id_present"          to "UPI Fraud",
        "urgency_pressure"        to "Pressure Scam",
        "conversation_escalation" to "Escalation Scam",
        "international_sender"    to "International Fraud"
    )

    // Compound trigger combinations → specific label (checked before single-trigger map)
    private val COMPOUND_RULES: List<Pair<Set<String>, String>> = listOf(
        setOf("authority_impersonation", "account_threat")    to "KYC / Account Freeze Scam",
        setOf("authority_impersonation", "credential_request") to "Authority OTP Theft",
        setOf("financial_lure", "upi_id_present")             to "UPI Lottery Fraud",
        setOf("link_lure", "account_threat")                  to "Phishing / Account Takeover",
        setOf("financial_lure", "urgency_pressure")           to "Advance Fee / Prize Scam",
        setOf("authority_impersonation", "urgency_pressure")  to "Government Impersonation Scam"
    )

    fun resolve(
        triggerResult: TriggerEngine.TriggerResult,
        kbResults: List<VectorSearchEngine.SearchResult>
    ): String {
        // 1. KB top match (high similarity preferred)
        val topKb = kbResults.firstOrNull { it.similarity >= 0.65f }
        if (topKb != null && topKb.entry.scamType.isNotBlank()) {
            return topKb.entry.scamType
        }

        val fired = triggerResult.firedTriggers.toSet()
        if (fired.isEmpty()) return "Scam"

        // 2. Compound rules — most specific first
        for ((required, label) in COMPOUND_RULES) {
            if (fired.containsAll(required)) return label
        }

        // 3. Single trigger — priority order defined by insertion order of linkedMapOf
        for ((trigger, label) in TRIGGER_TYPE_MAP) {
            if (trigger in fired) return label
        }

        // 4. Fallback — KB low-similarity match name if any
        return kbResults.firstOrNull()?.entry?.scamType?.ifBlank { "Scam" } ?: "Scam"
    }
}

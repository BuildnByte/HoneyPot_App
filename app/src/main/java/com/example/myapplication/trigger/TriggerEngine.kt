package com.example.myapplication.trigger

import com.example.myapplication.preprocessor.SmsPreprocessor

object TriggerEngine {

    data class TriggerResult(
        val shouldProcess: Boolean,
        val firedTriggers: List<String>,
        val priority: Priority
    )

    enum class Priority { LOW, MEDIUM, HIGH, CRITICAL }

    private val TRIGGER_GROUPS = mapOf(
        "authority_impersonation" to listOf(
            "rbi", "cbi", "trai", "income tax", "cybercrime cell",
            "enforcement directorate", "ed ", "police", "court notice",
            "legal notice", "arrest warrant", "fir"
        ),
        "account_threat" to listOf(
            "account suspended", "account blocked", "account will be",
            "kyc expired", "kyc pending", "update kyc", "verify kyc",
            "account freeze", "deactivated", "service suspended"
        ),
        "credential_request" to listOf(
            "share otp", "enter otp", "provide otp", "send otp",
            "cvv", "card number", "net banking password", "atm pin",
            "share your pin", "confirm your password"
        ),
        "financial_lure" to listOf(
            "you have won", "congratulations", "selected for",
            "prize money", "lottery winner", "cash reward",
            "refund of rs", "tax refund", "get rs", "earn daily"
        ),
        "urgency_pressure" to listOf(
            "within 24 hours", "within 2 hours", "immediately",
            "urgent action", "last chance", "expire today",
            "failing which", "action required", "respond now"
        ),
        "link_lure" to listOf(
            "click here", "click the link", "visit:", "log in at",
            "verify at", "confirm at", "bit.ly", "tinyurl", "goo.gl"
        )
    )

    private val CONVERSATION_ESCALATION_PATTERNS = listOf(
        Pair("otp", "bank|account|verify|confirm"),
        Pair("won|prize|reward", "fee|charge|process|tax|deposit"),
        Pair("rbi|police|government|official", "send|transfer|share|click")
    )

    fun evaluate(
        preprocessed: SmsPreprocessor.PreprocessedSms,
        conversationHistory: List<String> = emptyList()
    ): TriggerResult {

        val text = preprocessed.normalizedText
        val firedTriggers = mutableListOf<String>()

        for ((groupName, signals) in TRIGGER_GROUPS) {
            if (signals.any { text.contains(it) }) {
                firedTriggers.add(groupName)
            }
        }

        if (preprocessed.extractedEntities.urls.isNotEmpty()) {
            firedTriggers.add("external_url")
        }
        if (preprocessed.extractedEntities.upiIds.isNotEmpty()) {
            firedTriggers.add("upi_id_present")
        }
        // Flag international senders but exclude Indian numbers (+91) which are domestic
        if (preprocessed.senderType == SmsPreprocessor.SenderType.INTERNATIONAL &&
            !preprocessed.originalSender.startsWith("+91")) {
            firedTriggers.add("international_sender")
        }

        if (conversationHistory.isNotEmpty()) {
            val fullConversation = conversationHistory.joinToString(" ").lowercase()
            for ((pattern1, pattern2) in CONVERSATION_ESCALATION_PATTERNS) {
                if (Regex(pattern1).containsMatchIn(fullConversation) &&
                    Regex(pattern2).containsMatchIn(text)) {
                    // Guard: only add once even if multiple escalation patterns match
                    if ("conversation_escalation" !in firedTriggers) {
                        firedTriggers.add("conversation_escalation")
                    }
                    break
                }
            }
        }

        val shouldProcess = firedTriggers.isNotEmpty()

        val priority = when {
            firedTriggers.contains("credential_request") -> Priority.CRITICAL
            firedTriggers.contains("authority_impersonation") &&
                    firedTriggers.contains("account_threat") -> Priority.CRITICAL
            firedTriggers.size >= 3 -> Priority.HIGH
            firedTriggers.size == 2 -> Priority.MEDIUM
            else -> Priority.LOW
        }

        return TriggerResult(shouldProcess, firedTriggers, priority)
    }
}
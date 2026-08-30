package com.example.myapplication.filter

import android.util.Log

object SmsPreFilter {

    private val DLT_HEADER_REGEX = Regex("^[A-Z]{2}-[A-Z0-9]{6}(-[A-Z]+)?$")

    private val OTP_PATTERNS = listOf(
        Regex("\\b(OTP|one.?time.?password|verification.?code|auth.?code)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{4,8}\\s+is\\s+(your|the)", RegexOption.IGNORE_CASE),
        Regex("do not share (this|the) (otp|code)", RegexOption.IGNORE_CASE),
        Regex("valid for \\d+ (minute|second|hour)", RegexOption.IGNORE_CASE)
    )

    private val BANK_TRANSACTION_PATTERNS = listOf(
        Regex("(debited|credited|debit|credit).{0,20}(rs\\.?|₹|inr)\\s*[\\d,]+", RegexOption.IGNORE_CASE),
        Regex("(your|a/c|acct|account).{0,30}(debited|credited)", RegexOption.IGNORE_CASE),
        Regex("avl\\s*bal|available balance", RegexOption.IGNORE_CASE),
        Regex("txn\\s*id|transaction\\s*id|ref\\s*no", RegexOption.IGNORE_CASE)
    )

    private val safeSenderCache = mutableSetOf<String>()

    fun shouldIgnore(sender: String, body: String): FilterResult {
        Log.d("SmsPreFilter", "Evaluating sender: $sender")

        if (DLT_HEADER_REGEX.matches(sender)) {
            val result = if (hasScamSignals(body))
                FilterResult.SUSPICIOUS_INSTITUTIONAL
            else
                FilterResult.IGNORE
            Log.d("SmsPreFilter", "DLT Header detected. Result: $result")
            return result
        }

        if (sender in safeSenderCache) {
            Log.d("SmsPreFilter", "Sender is in safe cache.")
            return FilterResult.IGNORE
        }

        if (OTP_PATTERNS.any { it.containsMatchIn(body) } &&
            !DLT_HEADER_REGEX.matches(sender)) {
            Log.d("SmsPreFilter", "Suspicious OTP spoof detected.")
            return FilterResult.SUSPICIOUS_OTP_SPOOF
        }

        if (BANK_TRANSACTION_PATTERNS.any { it.containsMatchIn(body) } &&
            DLT_HEADER_REGEX.matches(sender)) {
            Log.d("SmsPreFilter", "Legitimate bank transaction.")
            return FilterResult.IGNORE
        }

        Log.d("SmsPreFilter", "Defaulting to PROCESS.")
        return FilterResult.PROCESS
    }

    private fun hasScamSignals(body: String): Boolean {
        val scamKeywords = listOf(
            "click here", "http", "https", "verify your account",
            "your account will be blocked", "KYC", "arrested", "CBI", "RBI notice"
        )
        return scamKeywords.any { body.contains(it, ignoreCase = true) }
    }

    fun addSafeSender(sender: String) {
        safeSenderCache.add(sender)
    }
}

enum class FilterResult {
    IGNORE,
    PROCESS,
    SUSPICIOUS_OTP_SPOOF,
    SUSPICIOUS_INSTITUTIONAL
}
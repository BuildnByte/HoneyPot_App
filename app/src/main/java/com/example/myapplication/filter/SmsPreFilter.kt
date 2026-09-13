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

    /**
     * Evaluates whether an SMS should be ignored or processed further.
     *
     * @param sender  The raw sender ID from the SMS (e.g. "+919876543210", "AD-HDFCBK")
     * @param body    The SMS body text
     * @param prefs   Optional filter preference manager. If null, all filters are enabled (default).
     */
    fun shouldIgnore(sender: String, body: String, prefs: FilterPreferenceManager? = null): FilterResult {
        Log.d("SmsPreFilter", "Evaluating sender: $sender")

        // ── Filter 1: DLT 6-char institutional header (e.g. "AD-HDFCBK") ──────
        if (DLT_HEADER_REGEX.matches(sender)) {
            if (prefs == null || prefs.filterDltInstitutional) {
                val result = if (hasScamSignals(body))
                    FilterResult.SUSPICIOUS_INSTITUTIONAL
                else
                    FilterResult.IGNORE
                Log.d("SmsPreFilter", "DLT Header detected. Result: $result")
                return result
            }
            // Filter disabled — fall through to full processing
            Log.d("SmsPreFilter", "DLT filter disabled by user — processing institutional sender")
        }

        // ── Filter 2: Safe sender cache ────────────────────────────────────────
        if ((prefs == null || prefs.filterSafeCache) && sender in safeSenderCache) {
            Log.d("SmsPreFilter", "Sender is in safe cache.")
            return FilterResult.IGNORE
        }

        // ── Filter 3: OTP / verification code patterns ─────────────────────────
        if ((prefs == null || prefs.filterOtp) &&
            OTP_PATTERNS.any { it.containsMatchIn(body) } &&
            !DLT_HEADER_REGEX.matches(sender)
        ) {
            Log.d("SmsPreFilter", "Suspicious OTP spoof detected.")
            return FilterResult.SUSPICIOUS_OTP_SPOOF
        }

        // ── Filter 4: Legitimate bank transaction from DLT sender ──────────────
        if ((prefs == null || prefs.filterBankTransaction) &&
            BANK_TRANSACTION_PATTERNS.any { it.containsMatchIn(body) } &&
            DLT_HEADER_REGEX.matches(sender)
        ) {
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
package com.honeypot.app.filter

import android.util.Log

object SmsPreFilter {

    /**
     * Matches the TRAI-mandated DLT sender header used by all institutional SMS in India.
     * Format: <2-letter telco prefix>-<1-7 alphanumeric brand chars>-<1+ alphanumeric suffix>
     * Examples: JD-SBLIFE-S  JM-JioPay-S  JX-BATAIn-P  JX-620020-P  AD-ADHAAR-S
     *
     * Case-insensitive: carriers occasionally deliver IDs with mixed case.
     */
    private val DLT_HEADER_REGEX = Regex(
        """^[A-Z]{2}-[A-Z0-9]{1,7}-[A-Z0-9]+$""",
        RegexOption.IGNORE_CASE
    )

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

        // ── Filter 1: DLT institutional sender header ────────────────────────
        // When the filter is enabled (or prefs not supplied), return IGNORE for ANY
        // DLT-format sender — including those whose body contains URLs / scam keywords
        // (e.g. SBI Life sends legitimate policy links that trigger hasScamSignals).
        // Users who enable "Ignore institutional senders" want ALL DLT traffic dropped.
        if (DLT_HEADER_REGEX.matches(sender.trim())) {
            if (prefs == null || prefs.filterDltInstitutional) {
                Log.d("SmsPreFilter", "DLT institutional sender ignored: $sender")
                return FilterResult.IGNORE
            }
            // Filter disabled by user — fall through to full processing
            Log.d("SmsPreFilter", "DLT filter disabled — processing institutional sender: $sender")
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
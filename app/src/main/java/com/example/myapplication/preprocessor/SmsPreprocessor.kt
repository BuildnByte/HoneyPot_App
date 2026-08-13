package com.example.myapplication.preprocessor

import com.example.myapplication.filter.SmsPreFilter
import com.example.myapplication.model.IncomingSms

object SmsPreprocessor {

    private val URL_REGEX = Regex("""https?://[^\s]+|www\.[^\s]+""")
    private val UPI_REGEX = Regex("""[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}""")
    private val PHONE_REGEX = Regex("""(\+91[\-\s]?)?[6-9]\d{9}""")
    // Exclude sequences that start with a phone prefix (+91 / 91 / 6-9 leading digit)
    // to prevent a 10-digit mobile number from also matching as an account number.
    private val ACCOUNT_REGEX = Regex("""(?<![+\d])\d{9,18}(?!\d)""")
    private val AMOUNT_REGEX = Regex("""(₹|rs\.?|inr)\s*[\d,]+(\.\d{2})?""", RegexOption.IGNORE_CASE)
    private val OTP_NUMBER_REGEX = Regex("""\b\d{4,8}\b""")
    private val REPEATED_CHARS = Regex("""(.)\1{3,}""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    data class PreprocessedSms(
        val originalSender: String,
        val originalBody: String,
        val normalizedText: String,
        val extractedEntities: Entities,
        val featureVector: FloatArray,
        val senderType: SenderType
    )

    data class Entities(
        val urls: List<String>,
        val upiIds: List<String>,
        val phoneNumbers: List<String>,
        val accountNumbers: List<String>,
        val amounts: List<String>
    )

    enum class SenderType {
        DLT_INSTITUTIONAL, MOBILE_NUMBER, INTERNATIONAL, SHORT_CODE, UNKNOWN
    }

    fun process(sms: IncomingSms): PreprocessedSms {
        val body = sms.body

        // 1. Extract entities BEFORE normalisation.
        //    Use distinct() on each list so a value that appears multiple times in the
        //    same message (e.g. a phone number repeated for emphasis) is stored once.
        val urls     = URL_REGEX.findAll(body).map { it.value }.distinct().toList()
        val upiIds   = UPI_REGEX.findAll(body).map { it.value }.distinct().toList()
        val phones   = PHONE_REGEX.findAll(body).map { it.value }.distinct().toList()

        // Strip any phone digits already captured so ACCOUNT_REGEX doesn't re-match them
        val bodyWithPhonesRedacted = PHONE_REGEX.replace(body, "[PHONE]")
        val accounts = ACCOUNT_REGEX.findAll(bodyWithPhonesRedacted).map { it.value }.distinct().toList()
        val amounts  = AMOUNT_REGEX.findAll(body).map { it.value }.distinct().toList()

        // 2. Text normalisation
        var text = body
        text = URL_REGEX.replace(text, " [URL] ")
        text = UPI_REGEX.replace(text, " [UPI] ")
        text = PHONE_REGEX.replace(text, " [PHONE] ")
        text = AMOUNT_REGEX.replace(text, " [AMOUNT] ")
        text = OTP_NUMBER_REGEX.replace(text, " [NUMBER] ")
        text = REPEATED_CHARS.replace(text) { it.groupValues[1] }
        text = text.lowercase()
        text = WHITESPACE_REGEX.replace(text, " ").trim()

        // 3. Feature vector
        val features = buildFeatureVector(body, sms.sender, urls, upiIds, phones)

        // 4. Sender type
        val senderType = classifySender(sms.sender)

        return PreprocessedSms(
            originalSender = sms.sender,
            originalBody = body,
            normalizedText = text,
            extractedEntities = Entities(urls, upiIds, phones, accounts, amounts),
            featureVector = features,
            senderType = senderType
        )
    }

    private fun buildFeatureVector(
        body: String,
        sender: String,
        urls: List<String>,
        upiIds: List<String>,
        phones: List<String>
    ): FloatArray {
        val lower = body.lowercase()
        return floatArrayOf(
            if (lower.containsAny("urgent", "immediately", "abhi", "turant", "last chance")) 1f else 0f,
            if (lower.containsAny("expire", "suspend", "block", "disconnect", "terminate")) 1f else 0f,
            if (lower.containsAny("arrest", "legal notice", "warrant", "court", "fir", "police")) 1f else 0f,
            if (lower.containsAny("rbi", "cbi", "income tax", "trai", "cybercrime", "enforcement")) 1f else 0f,
            if (lower.containsAny("government", "ministry", "department", "official")) 1f else 0f,
            if (lower.containsAny("otp", "cvv", "pin", "password")) 1f else 0f,
            if (lower.containsAny("kyc", "verify account", "update details", "confirm details")) 1f else 0f,
            if (lower.containsAny("send money", "transfer", "neft", "rtgs", "imps")) 1f else 0f,
            if (urls.isNotEmpty()) 1f else 0f,
            if (upiIds.isNotEmpty()) 1f else 0f,
            if (phones.isNotEmpty() && phones.size > 1) 1f else 0f,
            urls.size.toFloat().coerceAtMost(5f) / 5f,
            if (classifySender(sender) == SenderType.MOBILE_NUMBER) 1f else 0f,
            if (classifySender(sender) == SenderType.INTERNATIONAL) 1f else 0f,
            body.length.toFloat() / 500f,
            if (body.count { it.isUpperCase() }.toFloat() / body.length.coerceAtLeast(1) > 0.3f) 1f else 0f
        )
    }

    private fun classifySender(sender: String): SenderType {
        val DLT_REGEX = Regex("^[A-Z]{2}-[A-Z]{6}$")
        val MOBILE_IN = Regex("^(\\+91|91)?[6-9]\\d{9}$")
        val INTERNATIONAL = Regex("^\\+(?!91)\\d{7,15}$")
        val SHORT_CODE = Regex("^\\d{3,6}$")
        val cleaned = sender.replace("[\\s\\-]".toRegex(), "")
        return when {
            DLT_REGEX.matches(sender) -> SenderType.DLT_INSTITUTIONAL
            MOBILE_IN.matches(cleaned) -> SenderType.MOBILE_NUMBER
            INTERNATIONAL.matches(sender) -> SenderType.INTERNATIONAL
            SHORT_CODE.matches(sender) -> SenderType.SHORT_CODE
            else -> SenderType.UNKNOWN
        }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }
}
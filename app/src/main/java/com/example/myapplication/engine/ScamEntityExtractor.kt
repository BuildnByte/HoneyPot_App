package com.example.myapplication.engine

import android.util.Log
import com.example.myapplication.model.ExtractedEntity

/**
 * On-device named entity extractor using rule-based NER.
 *
 * Covers all entity types relevant to Indian SMS scams:
 *   - Person names (via honorific + name patterns)
 *   - Authority roles (Branch Manager, CBI Officer, etc.)
 *   - Government / banking organizations
 *   - Payment card patterns (card number fragments)
 *   - IBAN / account number hints
 *   - Date / time references
 *   - Email addresses
 *   - UPI / transaction reference numbers
 *
 * NOTE: SmsPreprocessor already extracts structured entities (phone, UPI IDs,
 * URLs, amounts, account numbers) via regex. This class only handles
 * UNSTRUCTURED named entities that regex alone cannot capture.
 *
 * For any entities missed here, the CloudReasoningService call on the first
 * message of each session extracts further named entities via Gemini.
 */
object ScamEntityExtractor {

    private const val TAG = "ScamEntityExtractor"

    // ── Patterns ──────────────────────────────────────────────────────────────

    /** Honorific + 1-2 capitalized name words */
    private val NAME_PATTERNS = listOf(
        Regex("(?:Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Sri|Shri|Smt\\.?)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?)"),
        Regex("(?:Officer|Inspector|Director)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?)"),
        Regex("(?:name is|contact|speak to)\\s+([A-Z][a-zA-Z]{2,}(?:\\s+[A-Z][a-zA-Z]{2,})?)"),
        Regex("([A-Z][a-z]+ [A-Z][a-z]+)\\s+(?:Sir|Madam|Ji)")
    )

    /** Email address */
    private val EMAIL_PATTERN = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

    /** Date/time references */
    private val DATE_PATTERNS = listOf(
        Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b"),
        Regex("\\b(?:today|tomorrow|tonight|this evening|by \\d{1,2}(?:am|pm))\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwithin\\s+\\d+\\s+(?:hours?|minutes?|days?)\\b", RegexOption.IGNORE_CASE)
    )

    /** Partial card number (4 digit groups separated by space/hyphen) */
    private val CARD_PATTERN = Regex("\\b(?:\\d{4}[\\s\\-]){3}\\d{4}\\b")

    /** IFSC code (Indian bank branch code used in transfers) */
    private val IFSC_PATTERN = Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b")

    /** Transaction reference number hints */
    private val TXN_REF_PATTERN = Regex("(?:Ref\\.?\\s*(?:No\\.?|ID)?|Txn\\.?\\s*ID|Transaction\\s*(?:No|ID)\\.?)\\s*[:#]?\\s*([A-Z0-9]{8,20})", RegexOption.IGNORE_CASE)

    // ── Authority dictionaries ─────────────────────────────────────────────────

    private val AUTHORITY_ROLES = listOf(
        "Branch Manager", "CBI Officer", "RBI Official", "Police Officer",
        "Enforcement Officer", "Income Tax Officer", "TRAI Official",
        "Nodal Officer", "Senior Officer", "Team Leader", "Executive",
        "Inspector General", "Sub Inspector", "Superintendent", "Director",
        "Assistant Commissioner", "Deputy Commissioner", "Station Officer",
        "Cyber Cell Officer", "Fraud Officer", "Verification Officer",
        "Customer Care Officer", "Help Desk Officer"
    )

    private val AUTHORITY_ORGS = listOf(
        "RBI", "CBI", "TRAI", "Income Tax Department", "Cybercrime Cell",
        "Enforcement Directorate", "Central Bureau of Investigation",
        "Reserve Bank of India", "Ministry of Finance",
        "Ministry of Home Affairs", "Telecom Regulatory Authority of India",
        "Electricity Board", "BSNL", "Airtel", "Jio", "Vi", "Vodafone Idea",
        "HDFC Bank", "SBI", "State Bank of India", "ICICI Bank",
        "Axis Bank", "Kotak Mahindra Bank", "Punjab National Bank", "PNB",
        "Bank of Baroda", "Canara Bank", "Union Bank", "IndusInd Bank",
        "Yes Bank", "Federal Bank", "IDBI Bank", "Paytm", "PhonePe",
        "Google Pay", "Amazon Pay", "NPCI", "UIDAI", "Aadhaar",
        "Ministry of Telecommunications", "DoT", "Department of Telecom"
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Extracts named entities from [text] using rule-based patterns.
     * Returns deduplicated list of [ExtractedEntity].
     */
    fun extract(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        // 1. Person names
        for (pattern in NAME_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                val name = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (name.length >= 4) entities.add(ExtractedEntity("PERSON_NAME", name))
            }
        }

        // 2. Authority roles
        for (role in AUTHORITY_ROLES) {
            if (text.contains(role, ignoreCase = true)) {
                entities.add(ExtractedEntity("ROLE", role))
            }
        }

        // 3. Organizations
        for (org in AUTHORITY_ORGS) {
            if (text.contains(org, ignoreCase = true)) {
                entities.add(ExtractedEntity("ORGANIZATION", org))
            }
        }

        // 4. Email addresses
        EMAIL_PATTERN.findAll(text).forEach {
            entities.add(ExtractedEntity("EMAIL", it.value))
        }

        // 5. Payment card numbers
        CARD_PATTERN.findAll(text).forEach {
            entities.add(ExtractedEntity("CARD_NUMBER", it.value))
        }

        // 6. IFSC codes (bank branch identifiers in transfer scams)
        IFSC_PATTERN.findAll(text).forEach {
            entities.add(ExtractedEntity("IFSC_CODE", it.value))
        }

        // 7. Transaction reference numbers
        TXN_REF_PATTERN.findAll(text).forEach { match ->
            val ref = match.groupValues.getOrNull(1)?.trim() ?: match.value
            if (ref.isNotBlank()) entities.add(ExtractedEntity("TXN_REF", ref))
        }

        // 8. Date / time urgency markers
        for (datePattern in DATE_PATTERNS) {
            datePattern.findAll(text).forEach {
                entities.add(ExtractedEntity("DATETIME", it.value.trim()))
            }
        }

        val result = entities.distinctBy { "${it.type}:${it.value.trim().lowercase()}" }
        Log.d(TAG, "Extracted ${result.size} entities from message (${text.length} chars)")
        return result
    }
}

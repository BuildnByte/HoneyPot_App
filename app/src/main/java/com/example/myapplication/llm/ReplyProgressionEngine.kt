package com.example.myapplication.llm

/**
 * Evidence-driven reply progression engine for honeypot conversations.
 *
 * ## Why this exists
 * Gemma 3 1B cannot reliably track what was already asked in a conversation and generate
 * a meaningfully different follow-up question. This is a hard limitation of the model size —
 * not something prompt engineering alone can fix for an on-device, offline-only use case.
 *
 * ## How it works
 * Instead of asking the LLM to generate progressive replies (which it fails at),
 * this engine:
 *   1. Parses the conversation history to understand what entities have been REVEALED
 *      by the attacker (name, phone, URL, amount, UPI, account number).
 *   2. Parses the victim's prior replies to understand what was ALREADY ASKED.
 *   3. Selects the next most useful question from a curated, scam-type-aware script
 *      that is guaranteed to not repeat a previously asked topic.
 *
 * The result is a reply that always advances the conversation toward extracting
 * more evidence, regardless of how many turns have already happened.
 */
object ReplyProgressionEngine {

    // ── Entity flags ──────────────────────────────────────────────────────────

    data class RevealedEntities(
        val hasName: Boolean      = false,
        val hasPhone: Boolean     = false,
        val hasUrl: Boolean       = false,
        val hasAmount: Boolean    = false,
        val hasUpi: Boolean       = false,
        val hasAccount: Boolean   = false,
        val hasLocation: Boolean  = false,
        val hasId: Boolean        = false      // employee/reference/ticket ID
    )

    // ── Keyword signals ───────────────────────────────────────────────────────

    private val NAME_SIGNALS    = listOf("my name is", "i am ", "i'm ", "this is ", "name:")
    private val PHONE_SIGNALS   = listOf("contact", "whatsapp", "call", "number", "mobile", "reach")
    private val URL_SIGNALS     = listOf("http", "www.", ".com", ".net", ".in", "website", "link", "site")
    private val AMOUNT_SIGNALS  = listOf("₹", "rs.", "inr", "fee", "charge", "pay", "payment", "deposit")
    private val UPI_SIGNALS     = listOf("@", "upi", "gpay", "phonepe", "paytm", "bhim")
    private val ACCOUNT_SIGNALS = listOf("account", "bank", "ifsc", "neft", "transfer")
    private val LOCATION_SIGNALS= listOf("office", "branch", "visit", "address", "location", "city")
    private val ID_SIGNALS      = listOf("reference", "ticket", "id:", "employee", "agent id", "case")

    private val ALREADY_ASKED_SIGNALS = mapOf(
        "name"     to listOf("your name", "who are you", "confirm your name", "introduce yourself"),
        "phone"    to listOf("your number", "contact number", "callback", "reach you", "call you"),
        "url"      to listOf("website", "link", "the site", "url", "resend"),
        "amount"   to listOf("fee", "how much", "the charge", "the payment", "pay for"),
        "upi"      to listOf("upi", "gpay", "your upi", "send to"),
        "account"  to listOf("account number", "bank account", "ifsc", "your bank"),
        "location" to listOf("branch", "office", "address", "where", "visit"),
        "id"       to listOf("reference", "ticket number", "your id", "employee id", "case number"),
        "verify"   to listOf("verify", "proof", "official", "legitimate", "confirm")
    )

    // ── Question banks per scam type ──────────────────────────────────────────

    /**
     * Each entry is (requiredEntityMissing, questionText).
     * The engine picks the first question whose entity is NOT yet revealed AND
     * whose topic was NOT already asked by the victim.
     */
    private val PRIZE_LOTTERY_QUESTIONS = listOf(
        "name"     to "That's great news! Could you tell me your full name and employee ID so I know who I'm dealing with?",
        "verify"   to "Is there an official hotline or registered number I can call to verify this prize?",
        "id"       to "Can you share a reference or ticket number for my prize claim?",
        "url"      to "Is there an official website where I can check my winning status?",
        "amount"   to "What exactly is the processing fee and how is it calculated?",
        "upi"      to "Which UPI ID or bank account should I send the fee to?",
        "account"  to "Can you give me the full account details — bank name, account number and IFSC — for the transfer?",
        "location" to "Is there a physical office I can visit to collect this in person?",
        "phone"    to "What's the direct number for Mr. Sharma so I can WhatsApp him directly?",
        "name"     to "Can you spell out your full name exactly as it appears on your ID?"
    )

    private val PHISHING_QUESTIONS = listOf(
        "url"      to "The link isn't opening for me — could you resend it or give me the full URL?",
        "name"     to "Who exactly am I speaking with? Could you share your full name and department?",
        "phone"    to "Is there a direct helpline number I can call if I get stuck on the form?",
        "id"       to "What's your employee or agent ID so I can mention it when I call?",
        "account"  to "If I need to update my bank details, which bank should I go to?",
        "verify"   to "How can I verify this request is genuinely from the organisation?",
        "location" to "Is there a branch office I can walk into to do this in person?"
    )

    private val KYC_QUESTIONS = listOf(
        "name"     to "Could you confirm your name and which department you're calling from?",
        "id"       to "What's your employee ID so I can note it for my records?",
        "location" to "Which branch should I visit to complete the KYC update?",
        "phone"    to "Is there an official number I can call back on to confirm this request?",
        "verify"   to "How do I verify this is a genuine request and not a scam?",
        "account"  to "Will I need to bring my existing account details when I visit?",
        "url"      to "Is there an online portal I can use instead of visiting a branch?"
    )

    private val ELECTRICITY_QUESTIONS = listOf(
        "name"     to "Could you share your name and your employee number from the utility company?",
        "id"       to "What's the consumer ID or complaint reference number for this disconnection notice?",
        "phone"    to "Is there an official board helpline where I can verify this complaint?",
        "amount"   to "What is the exact outstanding amount, and when is the last date to pay?",
        "account"  to "Which account or UPI should I pay to?",
        "verify"   to "How can I verify you are genuinely from the electricity board?"
    )

    private val GENERIC_QUESTIONS = listOf(
        "name"     to "Could you share your full name and the organisation you're from?",
        "verify"   to "How can I verify this is a legitimate request?",
        "id"       to "Is there a reference or case number I can use to check this independently?",
        "phone"    to "What is your direct contact number in case I need to follow up?",
        "amount"   to "What exactly is the amount involved and how should it be paid?",
        "account"  to "Can you provide the full bank or UPI details for any transfer needed?",
        "url"      to "Is there an official website or portal I can use to verify this?",
        "location" to "Is there a physical office I can visit to resolve this in person?"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the next best honeypot reply to send given the conversation history.
     *
     * @param conversationHistory Full list of "[A]: ..." and "[V]: ..." lines.
     * @param scamType            The classified scam type from the detection engine.
     */
    fun nextReply(conversationHistory: List<String>, scamType: String): String {
        val attackerLines = conversationHistory
            .filter { it.startsWith("[A]:") }
            .joinToString(" ") { it.removePrefix("[A]:") }
            .lowercase()

        val victimLines = conversationHistory
            .filter { it.startsWith("[V]:") }
            .joinToString(" ") { it.removePrefix("[V]:") }
            .lowercase()

        val revealed  = detectRevealed(attackerLines)
        val alreadyAsked = detectAlreadyAsked(victimLines)

        val bank = questionBank(scamType)

        // Pick the first question whose topic hasn't been asked yet.
        // Skip topics that are already revealed AND already asked about —
        // there's no point asking again for something the attacker already gave.
        for ((topic, question) in bank) {
            if (topic in alreadyAsked) continue          // already asked this
            if (isRevealed(topic, revealed) && topic in alreadyAsked) continue
            return question
        }

        // All curated questions exhausted → ask for independent verification
        return "I'd like to verify all this with someone independently before proceeding — " +
               "could you give me an official government or bank contact I can call to confirm?"
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun detectRevealed(attackerText: String): RevealedEntities = RevealedEntities(
        hasName     = NAME_SIGNALS.any    { attackerText.contains(it) },
        hasPhone    = PHONE_SIGNALS.any   { attackerText.contains(it) } || attackerText.contains(Regex("""\d{10}""")),
        hasUrl      = URL_SIGNALS.any     { attackerText.contains(it) },
        hasAmount   = AMOUNT_SIGNALS.any  { attackerText.contains(it) },
        hasUpi      = UPI_SIGNALS.any     { attackerText.contains(it) },
        hasAccount  = ACCOUNT_SIGNALS.any { attackerText.contains(it) },
        hasLocation = LOCATION_SIGNALS.any{ attackerText.contains(it) },
        hasId       = ID_SIGNALS.any      { attackerText.contains(it) }
    )

    private fun detectAlreadyAsked(victimText: String): Set<String> {
        val asked = mutableSetOf<String>()
        for ((topic, signals) in ALREADY_ASKED_SIGNALS) {
            if (signals.any { victimText.contains(it) }) asked.add(topic)
        }
        return asked
    }

    private fun isRevealed(topic: String, r: RevealedEntities): Boolean = when (topic) {
        "name"     -> r.hasName
        "phone"    -> r.hasPhone
        "url"      -> r.hasUrl
        "amount"   -> r.hasAmount
        "upi"      -> r.hasUpi
        "account"  -> r.hasAccount
        "location" -> r.hasLocation
        "id"       -> r.hasId
        else       -> false
    }

    private fun questionBank(scamType: String): List<Pair<String, String>> {
        val lower = scamType.lowercase()
        return when {
            lower.containsAny("prize", "lottery", "kbc", "lucky draw", "winner") -> PRIZE_LOTTERY_QUESTIONS
            lower.containsAny("phishing", "otp", "credential")                   -> PHISHING_QUESTIONS
            lower.containsAny("kyc", "account update", "bank", "verify account") -> KYC_QUESTIONS
            lower.containsAny("electricity", "bill", "disconnect", "utility")    -> ELECTRICITY_QUESTIONS
            else                                                                   -> GENERIC_QUESTIONS
        }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }
}

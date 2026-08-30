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
     * Each entry is (requiredEntityMissing, listOfVariantQuestions).
     * The engine picks the first topic whose entity is NOT yet revealed AND
     * whose topic was NOT already asked by the victim, then returns a random variant.
     */
    private val PRIZE_LOTTERY_QUESTIONS = listOf(
        "name"     to listOf("That's great news! Could you tell me your full name and employee ID so I know who I'm dealing with?", "Who exactly am I speaking with? Need your full name to proceed.", "Please confirm your full name and designation first."),
        "verify"   to listOf("Is there an official hotline or registered number I can call to verify this prize?", "How can I verify this lottery result independently?", "Do you have an official government or company website where this is listed?"),
        "id"       to listOf("Can you share a reference or ticket number for my prize claim?", "What is my winning ticket or reference ID?", "I need a case or reference number for this."),
        "url"      to listOf("Is there an official website where I can check my winning status?", "Please send the official link where my name is listed.", "Where can I read the terms and conditions online?"),
        "amount"   to listOf("What exactly is the processing fee and how is it calculated?", "How much do I have to pay upfront?", "Are there any hidden charges or fees for this?"),
        "upi"      to listOf("Which UPI ID or bank account should I send the fee to?", "Please provide the exact UPI handle for the payment.", "Where should I transfer this amount?"),
        "account"  to listOf("Can you give me the full account details — bank name, account number and IFSC — for the transfer?", "I prefer bank transfer, what are the account details?", "Please share the account number and IFSC code."),
        "location" to listOf("Is there a physical office I can visit to collect this in person?", "Where is your main branch located?", "Can I just come to your office to process this?"),
        "phone"    to listOf("What's the direct number for Mr. Sharma so I can WhatsApp him directly?", "Is there a direct phone number for the manager?", "Please share a contact number for your supervisor.")
    )

    private val PHISHING_QUESTIONS = listOf(
        "url"      to listOf("The link isn't opening for me — could you resend it or give me the full URL?", "Could you send the direct link again? It timed out.", "I can't access that page, is there an alternative link?"),
        "name"     to listOf("Who exactly am I speaking with? Could you share your full name and department?", "Please state your full name and position.", "Who is the authorized person I am dealing with?"),
        "phone"    to listOf("Is there a direct helpline number I can call if I get stuck on the form?", "What number should I call if I face technical issues?", "Please provide a customer care number."),
        "id"       to listOf("What's your employee or agent ID so I can mention it when I call?", "Do you have an employee code?", "I need your staff ID for verification."),
        "account"  to listOf("If I need to update my bank details, which bank should I go to?", "Which bank account is this linked to?", "Please confirm the bank name associated with this."),
        "verify"   to listOf("How can I verify this request is genuinely from the organisation?", "Why is this not showing on the official app?", "How do I know this isn't a scam?"),
        "location" to listOf("Is there a branch office I can walk into to do this in person?", "Can I visit the nearest branch to resolve this?", "Where is the head office?")
    )

    private val KYC_QUESTIONS = listOf(
        "name"     to listOf("Could you confirm your name and which department you're calling from?", "Who am I talking to? Need your name.", "Please tell me your full name and branch."),
        "id"       to listOf("What's your employee ID so I can note it for my records?", "Do you have an agent ID?", "What is your official employee number?"),
        "location" to listOf("Which branch should I visit to complete the KYC update?", "Can I do this at my home branch?", "Where is your office located?"),
        "phone"    to listOf("Is there an official number I can call back on to confirm this request?", "What is the official helpline number?", "Can you give me a landline number to call?"),
        "verify"   to listOf("How do I verify this is a genuine request and not a scam?", "I got an SMS from the bank saying never to do KYC over text. Explain this.", "Is this process approved by RBI?"),
        "account"  to listOf("Will I need to bring my existing account details when I visit?", "Which account number is this regarding?", "Is this for my savings or current account?"),
        "url"      to listOf("Is there an online portal I can use instead of visiting a branch?", "Can I do this via the official banking app?", "Please send the official bank website link.")
    )

    private val ELECTRICITY_QUESTIONS = listOf(
        "name"     to listOf("Could you share your name and your employee number from the utility company?", "Who is the officer in charge? Need your name.", "Please confirm your full name."),
        "id"       to listOf("What's the consumer ID or complaint reference number for this disconnection notice?", "What is my CA number according to your records?", "Please provide the exact meter number this is about."),
        "phone"    to listOf("Is there an official board helpline where I can verify this complaint?", "Can I call the electricity board directly? Give me the number.", "What is the official customer care number?"),
        "amount"   to listOf("What is the exact outstanding amount, and when is the last date to pay?", "How much is pending exactly?", "What is the total bill amount?"),
        "account"  to listOf("Which account or UPI should I pay to?", "Please give me the official payment portal link or UPI.", "Where exactly do I make the payment?"),
        "verify"   to listOf("How can I verify you are genuinely from the electricity board?", "Why didn't I get a physical notice?", "Is there a way to check this on the official app?")
    )

    private val JOB_SCAM_QUESTIONS = listOf(
        "name"     to listOf("Who exactly is the HR manager I am speaking to? Need your full name.", "Could you confirm your name and designation?", "Who will be my reporting manager?"),
        "verify"   to listOf("Is this company registered? How can I verify it?", "Can you share your company's LinkedIn page or official registration?", "How do I know this is a genuine job offer?"),
        "url"      to listOf("Please share the official company website link.", "Where can I read more about the company online?", "Do you have a careers page link?"),
        "location" to listOf("Where is the physical office located for the interview?", "Can I visit your office in person?", "What is the full address of the company?"),
        "amount"   to listOf("Are there any registration or training fees required?", "Do I need to pay any security deposit?", "Is there any upfront payment for the equipment?"),
        "upi"      to listOf("If there's a fee, which UPI ID should it be sent to?", "Please provide the official company UPI ID.", "Where do I send the registration fee?"),
        "account"  to listOf("Can I get the official company bank account details instead?", "Please share the company's current account number.", "What are the account details for the salary deposit?"),
        "phone"    to listOf("Is there an official landline number I can reach you on?", "Can you provide a contact number for the HR department?", "What's the direct line for the office?")
    )

    private val INVESTMENT_SCAM_QUESTIONS = listOf(
        "verify"   to listOf("Is your trading platform SEBI registered? Can I see the certificate?", "How can I independently verify your firm's registration?", "Are these returns guaranteed legally?"),
        "name"     to listOf("Could you share your full name and your SEBI registration number?", "Who is the fund manager? Need your name.", "Who exactly am I dealing with?"),
        "url"      to listOf("What is the official website for the trading platform?", "Can you send the link to download the trading app?", "Where do I login to check my portfolio?"),
        "account"  to listOf("Which bank account should the investment be deposited into?", "Please provide the company's official bank account details.", "Can I have the IFSC and account number?"),
        "upi"      to listOf("Do you accept UPI? What is the official handle?", "Can I pay the initial amount via UPI? Give me the ID.", "What is the UPI ID for the deposit?"),
        "amount"   to listOf("What is the exact minimum investment amount required?", "How much do I need to start?", "Are there any withdrawal fees or taxes?"),
        "location" to listOf("Where is your brokerage firm's office located?", "Can I visit your office to sign the documents?", "What is your physical address?"),
        "phone"    to listOf("Is there a customer support number I can call?", "Can you give me the official helpline number?", "What number should I call for withdrawal issues?")
    )
    
    private val DIGITAL_ARREST_QUESTIONS = listOf(
        "name"     to listOf("Could you state your full name, rank, and police station?", "Who exactly is the investigating officer? Need your name.", "Please confirm your full name and badge number."),
        "id"       to listOf("What is my FIR number or case file number?", "Can you share the exact reference ID for this warrant?", "I need the official case number to verify this."),
        "verify"   to listOf("How can I verify this with my local police station?", "Why isn't this notice sent via post or official email?", "Can I speak to my lawyer first?"),
        "phone"    to listOf("What is the official landline number of the police station?", "Can you give me the number of the cyber crime cell?", "Where can I call to confirm this?"),
        "location" to listOf("Which police station or CBI office should I report to physically?", "Can I just come to the nearest station?", "What is the address of the branch handling this?"),
        "amount"   to listOf("Is there any penalty or bail amount I need to pay?", "What is the exact amount required to clear my name?", "Are you asking for a security deposit?"),
        "account"  to listOf("Which official government bank account should the penalty go to?", "Please provide the RBI or government account details.", "Where do I transfer the verification amount?"),
        "upi"      to listOf("Do you accept payment via UPI for the penalty? What is the ID?", "Can I pay the fine via UPI?", "What is the official UPI handle?")
    )

    private val PARCEL_SCAM_QUESTIONS = listOf(
        "id"       to listOf("What is the exact tracking number for this parcel?", "Can you share the AWB or consignment number?", "I need the exact tracking ID to check."),
        "name"     to listOf("Who is the customs officer or courier agent handling this? Need your name.", "Could you confirm your name and department?", "Who am I speaking with?"),
        "verify"   to listOf("How can I verify this hold with the official customs department?", "Why didn't I get an official email from the courier?", "Can I check this on the official India Post website?"),
        "url"      to listOf("Is there an official website where I can track this hold status?", "Please send the official tracking link.", "Where do I go online to clear customs?"),
        "amount"   to listOf("What is the exact customs duty or clearance fee?", "How much exactly do I need to pay to release it?", "Are there any other hidden charges?"),
        "account"  to listOf("Which official customs bank account should the fee go to?", "Please provide the bank details for the customs department.", "Where do I transfer the duty?"),
        "upi"      to listOf("Do you accept the customs fee via UPI? What is the handle?", "Can I pay the clearance fee via UPI?", "What is the official UPI ID for payment?"),
        "phone"    to listOf("Is there a toll-free number for the courier company?", "Can you give me the official helpline number?", "What number should I call if I have issues?"),
        "location" to listOf("Where is the parcel currently held? Which customs office?", "Can I visit the courier hub to collect it personally?", "What is the physical address of the holding facility?")
    )

    private val GENERIC_QUESTIONS = listOf(
        "name"     to listOf("Could you share your full name and the organisation you're from?", "Who exactly am I speaking with?", "Please confirm your name."),
        "verify"   to listOf("How can I verify this is a legitimate request?", "How do I know this isn't a scam?", "Can you provide some official proof?"),
        "id"       to listOf("Is there a reference or case number I can use to check this independently?", "Do you have a reference ID for this?", "I need a ticket number to proceed."),
        "phone"    to listOf("What is your direct contact number in case I need to follow up?", "Is there an official helpline I can call?", "Please share a landline number."),
        "amount"   to listOf("What exactly is the amount involved and how should it be paid?", "Are there any fees?", "How much do I need to pay?"),
        "upi"      to listOf("What is the exact UPI ID for the payment?", "Can I pay via UPI? Give me the handle.", "Where do I send the money?"),
        "account"  to listOf("Can you provide the full bank account and IFSC details?", "Please share the official bank account number.", "What are the account details for transfer?"),
        "url"      to listOf("Is there an official website link you can share?", "Where can I read more about this online?", "Please send the official portal link."),
        "location" to listOf("Where are you located? Can I visit the office?", "What is your branch address?", "Where is your head office?")
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the next best honeypot reply to send given the conversation history.
     *
     * @param conversationHistory Full list of "[A]: ...", "[V]: ...", and "[U]: ..." lines.
     * @param scamType            The classified scam type from the detection engine.
     */
    fun nextReply(conversationHistory: List<String>, scamType: String): String {
        val attackerLines = conversationHistory
            .filter { it.startsWith("[A]:") }
            .joinToString(" ") { it.removePrefix("[A]:") }
            .lowercase()

        // Treat both [V]: (simulation) and [U]: (live honeypot user-sent) as victim turns.
        // Without [U]:, the engine never detects what the user already asked in live sessions
        // and repeats the same first question on every turn.
        val victimLines = conversationHistory
            .filter { it.startsWith("[V]:") || it.startsWith("[U]:") }
            .joinToString(" ") {
                when {
                    it.startsWith("[V]:") -> it.removePrefix("[V]:")
                    it.startsWith("[U]:") -> it.removePrefix("[U]:")
                    else -> it
                }
            }
            .lowercase()

        val revealed  = detectRevealed(attackerLines)
        val alreadyAsked = detectAlreadyAsked(victimLines)

        val bank = questionBank(scamType)

        // Pick the first question whose topic hasn't been asked yet.
        // Skip topics that are already revealed AND already asked about —
        // there's no point asking again for something the attacker already gave.
        for ((topic, questions) in bank) {
            if (topic in alreadyAsked) continue          // already asked this
            if (isRevealed(topic, revealed) && topic in alreadyAsked) continue
            return questions.random() // return a random variant
        }

        // All curated questions exhausted → ask for independent verification
        return listOf(
            "I'd like to verify all this with someone independently before proceeding — could you give me an official government or bank contact I can call to confirm?",
            "Can you provide an official verifiable source for all of this information?",
            "Before I go ahead, how can I independently verify your identity and this request?"
        ).random()
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

    private fun questionBank(scamType: String): List<Pair<String, List<String>>> {
        val lower = scamType.lowercase()
        return when {
            lower.containsAny("prize", "lottery", "kbc", "lucky draw", "winner") -> PRIZE_LOTTERY_QUESTIONS
            lower.containsAny("job", "work from home", "task", "part time")      -> JOB_SCAM_QUESTIONS
            lower.containsAny("invest", "crypto", "trading", "profit")           -> INVESTMENT_SCAM_QUESTIONS
            lower.containsAny("arrest", "cbi", "police", "customs", "trai")      -> DIGITAL_ARREST_QUESTIONS
            lower.containsAny("parcel", "fedex", "dhl", "post", "courier")       -> PARCEL_SCAM_QUESTIONS
            lower.containsAny("phishing", "otp", "credential")                   -> PHISHING_QUESTIONS
            lower.containsAny("kyc", "account update", "bank", "verify account") -> KYC_QUESTIONS
            lower.containsAny("electricity", "bill", "disconnect", "utility")    -> ELECTRICITY_QUESTIONS
            else                                                                   -> GENERIC_QUESTIONS
        }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }
}

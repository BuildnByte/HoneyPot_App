package com.example.myapplication.agent

import com.example.myapplication.model.ScamResult

class HoneypotAgent {

    private val conversations =
        mutableMapOf<String, ConversationState>()

    fun process(
        sender: String,
        message: String,
        scamResult: ScamResult
    ): AgentDecision {

        val state = conversations.getOrPut(sender) {
            ConversationState(sender = sender)
        }

        // 1. Store the incoming message
        state.messages.add(message)

        // 2. Update scam information from ScamResult
        state.scamType = scamResult.scamType
        state.confidence = scamResult.confidence

        // 3. Determine the current scam stage
        updateStage(state, message)

        // 4. Determine objectives based on scam type
        updateObjectives(state, scamResult)

        // 5. Select the next action
        return decideNextAction(state)
    }

    /**
     * Determines what the scammer is currently trying to do.
     */
    private fun updateStage(
        state: ConversationState,
        message: String
    ) {
        val text = message.lowercase()

        state.currentStage = when {
            containsAny(
                text,
                "otp",
                "password",
                "pin",
                "cvv",
                "verification code"
            ) -> ScamStage.CREDENTIAL_REQUEST

            containsAny(
                text,
                "pay",
                "payment",
                "transfer",
                "upi",
                "send money",
                "deposit",
                "fee",
                "charge"
            ) -> ScamStage.PAYMENT_REQUEST

            containsAny(
                text,
                "aadhaar",
                "pan",
                "account number",
                "bank details",
                "date of birth",
                "personal details"
            ) -> ScamStage.INFORMATION_REQUEST

            containsAny(
                text,
                "immediately",
                "urgent",
                "within",
                "today",
                "now",
                "expires",
                "last chance"
            ) -> ScamStage.URGENCY

            containsAny(
                text,
                "official",
                "representative",
                "employee id",
                "customer care",
                "bank officer",
                "government officer"
            ) -> ScamStage.TRUST_BUILDING

            state.messages.size > 1 -> ScamStage.FOLLOW_UP

            else -> ScamStage.INITIAL_CONTACT
        }
    }

    /**
     * Creates the objectives that the honeypot wants to accomplish.
     */
    private fun updateObjectives(
        state: ConversationState,
        result: ScamResult
    ) {

        // If the message is not confidently classified as a scam,
        // don't aggressively engage.
        if (!result.isScam || result.confidence < 0.60f) {
            return
        }

        val objectives = objectivesForScamType(result.scamType)

        state.pendingObjectives.addAll(objectives)

        // Remove objectives that have already been achieved.
        state.pendingObjectives.removeAll(
            state.completedObjectives
        )
    }

    /**
     * Defines intelligence-gathering priorities for each scam type.
     */
    private fun objectivesForScamType(
        scamType: String
    ): List<AgentObjective> {

        return when (scamType.lowercase()) {

            "kyc_fraud" -> listOf(
                AgentObjective.OBTAIN_SCAMMER_IDENTITY,
                AgentObjective.OBTAIN_SUSPICIOUS_LINK,
                AgentObjective.OBTAIN_PHONE_NUMBER,
                AgentObjective.OBTAIN_PAYMENT_DETAILS
            )

            "impersonation" -> listOf(
                AgentObjective.OBTAIN_SCAMMER_IDENTITY,
                AgentObjective.OBTAIN_PHONE_NUMBER,
                AgentObjective.OBTAIN_SUSPICIOUS_LINK,
                AgentObjective.OBTAIN_PAYMENT_DETAILS
            )

            "phishing" -> listOf(
                AgentObjective.OBTAIN_SUSPICIOUS_LINK,
                AgentObjective.OBTAIN_SCAMMER_IDENTITY,
                AgentObjective.OBTAIN_EMAIL_ADDRESS,
                AgentObjective.OBTAIN_PAYMENT_DETAILS
            )

            "job_scam" -> listOf(
                AgentObjective.OBTAIN_SCAMMER_IDENTITY,
                AgentObjective.OBTAIN_PHONE_NUMBER,
                AgentObjective.OBTAIN_SUSPICIOUS_LINK,
                AgentObjective.OBTAIN_PAYMENT_DETAILS
            )

            "lottery" -> listOf(
                AgentObjective.OBTAIN_PAYMENT_DETAILS,
                AgentObjective.OBTAIN_UPI_ID,
                AgentObjective.OBTAIN_PHONE_NUMBER,
                AgentObjective.OBTAIN_SUSPICIOUS_LINK
            )

            else -> listOf(
                AgentObjective.UNDERSTAND_SCAM_METHOD,
                AgentObjective.OBTAIN_SCAMMER_IDENTITY,
                AgentObjective.OBTAIN_PHONE_NUMBER,
                AgentObjective.OBTAIN_SUSPICIOUS_LINK
            )
        }
    }

    /**
     * Chooses what the honeypot should do next.
     */
    private fun decideNextAction(
        state: ConversationState
    ): AgentDecision {

        // Nothing left to accomplish.
        if (state.pendingObjectives.isEmpty()) {

            state.isActive = false
            state.currentStage = ScamStage.COMPLETED

            return AgentDecision(
                action = AgentAction.END_CONVERSATION,
                reason = "All available intelligence-gathering objectives have been completed.",
                objective = null,
                stage = state.currentStage
            )
        }

        // Select the first pending objective.
        val objective = selectNextObjective(state)

        val action = actionForObjective(objective)

        state.actionsTaken.add(action)

        return AgentDecision(
            action = action,
            reason = "Continue engagement to accomplish the objective: $objective",
            objective = objective,
            stage = state.currentStage
        )
    }

    /**
     * Selects the next objective based on priority.
     */
    private fun selectNextObjective(
        state: ConversationState
    ): AgentObjective {

        val priorityOrder = listOf(
            AgentObjective.OBTAIN_SUSPICIOUS_LINK,
            AgentObjective.OBTAIN_SCAMMER_IDENTITY,
            AgentObjective.OBTAIN_PHONE_NUMBER,
            AgentObjective.OBTAIN_EMAIL_ADDRESS,
            AgentObjective.OBTAIN_UPI_ID,
            AgentObjective.OBTAIN_BANK_ACCOUNT,
            AgentObjective.OBTAIN_PAYMENT_DETAILS,
            AgentObjective.UNDERSTAND_SCAM_METHOD,
            AgentObjective.MAINTAIN_ENGAGEMENT,
            AgentObjective.END_CONVERSATION
        )

        return priorityOrder.firstOrNull {
            state.pendingObjectives.contains(it)
        } ?: state.pendingObjectives.first()
    }

    /**
     * Converts an objective into an agent action.
     */
    private fun actionForObjective(
        objective: AgentObjective
    ): AgentAction {

        return when (objective) {

            AgentObjective.OBTAIN_SUSPICIOUS_LINK ->
                AgentAction.ASK_QUESTION

            AgentObjective.OBTAIN_SCAMMER_IDENTITY ->
                AgentAction.ASK_QUESTION

            AgentObjective.OBTAIN_PHONE_NUMBER ->
                AgentAction.REQUEST_INFORMATION

            AgentObjective.OBTAIN_EMAIL_ADDRESS ->
                AgentAction.REQUEST_INFORMATION

            AgentObjective.OBTAIN_UPI_ID ->
                AgentAction.REQUEST_INFORMATION

            AgentObjective.OBTAIN_BANK_ACCOUNT ->
                AgentAction.REQUEST_INFORMATION

            AgentObjective.OBTAIN_PAYMENT_DETAILS ->
                AgentAction.REQUEST_INFORMATION

            AgentObjective.UNDERSTAND_SCAM_METHOD ->
                AgentAction.ASK_QUESTION

            AgentObjective.MAINTAIN_ENGAGEMENT ->
                AgentAction.CONTINUE_ENGAGEMENT

            AgentObjective.END_CONVERSATION ->
                AgentAction.END_CONVERSATION
        }
    }

    private fun containsAny(
        text: String,
        vararg keywords: String
    ): Boolean {
        return keywords.any { text.contains(it) }
    }
}
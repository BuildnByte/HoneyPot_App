package com.example.myapplication.agent

import com.example.myapplication.model.ScamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoneypotAgentTest {

    @Test
    fun `agent detects KYC scam and creates objective`() {

        // Arrange
        val agent = HoneypotAgent()

        val scamResult = ScamResult(
            isScam = true,
            scamType = "kyc_fraud",
            confidence = 0.94f,
            reasoning = "The message creates urgency and requests KYC information.",
            extractedEntities = emptyList()
        )

        val message =
            "Your KYC has expired. You must verify your account immediately."

        // Act
        val decision = agent.process(
            sender = "+919876543210",
            message = message,
            scamResult = scamResult
        )

        // Assert
        assertTrue(decision.objective != null)

        assertEquals(
            AgentObjective.OBTAIN_SUSPICIOUS_LINK,
            decision.objective
        )

        assertEquals(
            AgentAction.ASK_QUESTION,
            decision.action
        )

        assertEquals(
            ScamStage.URGENCY,
            decision.stage
        )
    }
}
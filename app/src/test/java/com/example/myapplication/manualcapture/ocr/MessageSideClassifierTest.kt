package com.example.myapplication.manualcapture.ocr

import android.graphics.Rect
import com.example.myapplication.manualcapture.ManualCapturePrefs
import com.example.myapplication.manualcapture.model.BlockRole
import com.example.myapplication.manualcapture.model.MessageSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageSideClassifierTest {

    private val screenWidth = 1080f
    private val screenHeight = 2400f

    @Test
    fun testIncomingMessage_shortAndLong() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)

        // Short incoming message (e.g. "Hi")
        // left: 60px (5.5%), top: 400, right: 260px (24%), bottom: 460
        assertEquals(MessageSide.INCOMING, classifier.classifyBlock(60, 400, 260, 460, screenWidth, screenHeight))
        assertEquals(BlockRole.MESSAGE, classifier.inferRole("Hi", 60, 400, 260, 460, screenWidth, screenHeight))

        // Long incoming message spanning 70% width
        // left: 60px (5.5%), top: 500, right: 800px (74%), bottom: 680
        assertEquals(MessageSide.INCOMING, classifier.classifyBlock(60, 500, 800, 680, screenWidth, screenHeight))
        assertEquals(
            BlockRole.MESSAGE,
            classifier.inferRole("Can you please send the OTP immediately?", 60, 500, 800, 680, screenWidth, screenHeight)
        )
    }

    @Test
    fun testOutgoingMessage_shortAndLong() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)

        // Short outgoing message (e.g. "Ok")
        // left: 860px, top: 700, right: 1020px, bottom: 760 (right margin: 60px / 5.5%)
        assertEquals(MessageSide.OUTGOING, classifier.classifyBlock(860, 700, 1020, 760, screenWidth, screenHeight))
        assertEquals(BlockRole.MESSAGE, classifier.inferRole("Ok", 860, 700, 1020, 760, screenWidth, screenHeight))

        // Long outgoing message
        // left: 320px, top: 800, right: 1020px, bottom: 960 (right margin: 60px / 5.5%)
        assertEquals(MessageSide.OUTGOING, classifier.classifyBlock(320, 800, 1020, 960, screenWidth, screenHeight))
        assertEquals(
            BlockRole.MESSAGE,
            classifier.inferRole("I am checking this right now, wait a sec.", 320, 800, 1020, 960, screenWidth, screenHeight)
        )
    }

    @Test
    fun testCenteredDateHeaderAndSystemLabels() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)

        // Centered "TODAY" pill
        // left: 450, top: 300, right: 630, bottom: 350 (centered around 540)
        assertEquals(MessageSide.SYSTEM_OR_UNKNOWN, classifier.classifyBlock(450, 300, 630, 350, screenWidth, screenHeight))
        assertEquals(BlockRole.SYSTEM_LABEL, classifier.inferRole("TODAY", 450, 300, 630, 350, screenWidth, screenHeight))

        // "Messages are end-to-end encrypted"
        assertEquals(
            BlockRole.SYSTEM_LABEL,
            classifier.inferRole("Messages and calls are end-to-end encrypted", 150, 360, 930, 420, screenWidth, screenHeight)
        )
    }

    @Test
    fun testScreenChromeHeaderAndFooter() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)

        // Top bar (y = 50px on a 2400px screen -> top 2%)
        assertEquals(MessageSide.SYSTEM_OR_UNKNOWN, classifier.classifyBlock(120, 40, 400, 90, screenWidth, screenHeight))
        assertEquals(BlockRole.SYSTEM_LABEL, classifier.inferRole("John Doe (Online)", 120, 40, 400, 90, screenWidth, screenHeight))

        // Bottom input bar (y = 2300px on a 2400px screen -> bottom 4%)
        assertEquals(MessageSide.SYSTEM_OR_UNKNOWN, classifier.classifyBlock(80, 2280, 800, 2350, screenWidth, screenHeight))
        assertEquals(BlockRole.SYSTEM_LABEL, classifier.inferRole("Message...", 80, 2280, 800, 2350, screenWidth, screenHeight))
    }

    @Test
    fun testPureTimestampVsMessageContainingTime() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.DEFAULT_LAYOUT)

        // Pure timestamp
        assertEquals(BlockRole.TIMESTAMP, classifier.inferRole("10:45 AM", 60, 500, 800, 600, screenWidth, screenHeight))
        assertEquals(BlockRole.TIMESTAMP, classifier.inferRole("23:15", 60, 500, 800, 600, screenWidth, screenHeight))
        assertEquals(BlockRole.TIMESTAMP, classifier.inferRole("1:30 pm ✓✓", 60, 500, 800, 600, screenWidth, screenHeight))

        // Message that happens to mention a time
        assertEquals(
            BlockRole.MESSAGE,
            classifier.inferRole("Let's meet at 10:30 AM at the station", 60, 500, 800, 600, screenWidth, screenHeight)
        )
    }

    @Test
    fun testLayoutLeftIsMine() {
        val classifier = MessageSideClassifier(ManualCapturePrefs.LAYOUT_LEFT_IS_MINE)

        // Left messages should now be classified as OUTGOING
        assertEquals(MessageSide.OUTGOING, classifier.classifyBlock(60, 500, 700, 600, screenWidth, screenHeight))
        // Right messages should now be classified as INCOMING
        assertEquals(MessageSide.INCOMING, classifier.classifyBlock(380, 700, 1020, 800, screenWidth, screenHeight))
    }
}

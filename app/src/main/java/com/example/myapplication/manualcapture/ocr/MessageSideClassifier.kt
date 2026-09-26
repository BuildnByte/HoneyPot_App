package com.example.myapplication.manualcapture.ocr

import android.graphics.Rect
import com.example.myapplication.manualcapture.model.BlockRole
import com.example.myapplication.manualcapture.model.MessageSide
import com.example.myapplication.manualcapture.ManualCapturePrefs

/**
 * Heuristic classifier that maps an OCR block's horizontal position to a
 * [MessageSide] (INCOMING / OUTGOING / SYSTEM_OR_UNKNOWN).
 *
 * Algorithm (v1):
 * - Divide the image into three horizontal thirds.
 * - A block whose horizontal centre falls in the LEFT third → INCOMING.
 * - A block whose horizontal centre falls in the RIGHT third → OUTGOING.
 * - Centre/ambiguous → SYSTEM_OR_UNKNOWN.
 * - If [senderLayout] is [ManualCapturePrefs.LAYOUT_LEFT_IS_MINE], sides are flipped.
 *
 * Role heuristics:
 * - Short blocks (< MIN_MESSAGE_CHARS) that look like timestamps → TIMESTAMP.
 * - Very narrow blocks spanning less than 20% image width → SYSTEM_LABEL candidate.
 * - Everything else → MESSAGE.
 *
 * This is a heuristic only. Output must never be presented as certain sender identification.
 */
class MessageSideClassifier(private val senderLayout: String = ManualCapturePrefs.DEFAULT_LAYOUT) {

    /**
     * Classify a block's horizontal position relative to [imageWidth] and [imageHeight].
     *
     * In mobile messaging apps (WhatsApp, Telegram, Signal, Instagram, SMS):
     * - Incoming messages start near the left margin (small left margin) and leave
     *   a clear gap on the right (large right margin).
     * - Outgoing messages end near the right margin (small right margin) and leave
     *   a clear gap on the left (large left margin).
     * - Centered text (date headers, encryption notices) has roughly equal left and right margins.
     */
    fun classifyBlock(bounds: Rect, imageWidth: Float, imageHeight: Float = 0f): MessageSide =
        classifyBlock(bounds.left, bounds.top, bounds.right, bounds.bottom, imageWidth, imageHeight)

    fun classifyBlock(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Float,
        imageHeight: Float = 0f
    ): MessageSide {
        if (imageWidth <= 0f) return MessageSide.SYSTEM_OR_UNKNOWN

        val leftMargin = left.toFloat().coerceAtLeast(0f)
        val rightMargin = (imageWidth - right.toFloat()).coerceAtLeast(0f)
        val blockWidth = (right - left).toFloat()

        val leftRatio = leftMargin / imageWidth
        val rightRatio = rightMargin / imageWidth
        val widthRatio = blockWidth / imageWidth

        // If the text spans nearly the entire screen width (> 88%), it's a banner/divider/system element
        if (widthRatio > FULL_WIDTH_BANNER_RATIO) {
            return MessageSide.SYSTEM_OR_UNKNOWN
        }

        // Screen chrome check: top bar (contact name/status) or bottom bar (message input/mic)
        if (imageHeight > 0f) {
            val topRatio = top.toFloat() / imageHeight
            val bottomRatio = bottom.toFloat() / imageHeight
            if (topRatio < TOP_CHROME_RATIO || bottomRatio > BOTTOM_CHROME_RATIO) {
                return MessageSide.SYSTEM_OR_UNKNOWN
            }
        }

        // Check for centered headers (e.g. "TODAY", "Messages are end-to-end encrypted")
        val isCentered = Math.abs(leftRatio - rightRatio) < CENTER_TOLERANCE &&
                leftRatio > MIN_MARGIN_FOR_CENTERED &&
                rightRatio > MIN_MARGIN_FOR_CENTERED

        val raw = when {
            isCentered -> MessageSide.SYSTEM_OR_UNKNOWN

            // Incoming: Starts close to left edge (leftRatio <= 0.28) and has distinctly more right margin
            leftRatio <= MAX_ALIGNED_EDGE_RATIO && (rightRatio > leftRatio + MARGIN_DIFFERENCE_THRESHOLD || rightRatio >= 0.12f) ->
                MessageSide.INCOMING

            // Outgoing: Ends close to right edge (rightRatio <= 0.28) and has distinctly more left margin
            rightRatio <= MAX_ALIGNED_EDGE_RATIO && (leftRatio > rightRatio + MARGIN_DIFFERENCE_THRESHOLD || leftRatio >= 0.12f) ->
                MessageSide.OUTGOING

            // Fallback: If one margin is substantially smaller than the other
            leftRatio < rightRatio - MARGIN_DIFFERENCE_THRESHOLD -> MessageSide.INCOMING
            rightRatio < leftRatio - MARGIN_DIFFERENCE_THRESHOLD -> MessageSide.OUTGOING

            else -> MessageSide.SYSTEM_OR_UNKNOWN
        }

        // Flip if the user's messages are on the left (RTL layout, etc.)
        return if (senderLayout == ManualCapturePrefs.LAYOUT_LEFT_IS_MINE) {
            when (raw) {
                MessageSide.INCOMING -> MessageSide.OUTGOING
                MessageSide.OUTGOING -> MessageSide.INCOMING
                MessageSide.SYSTEM_OR_UNKNOWN -> MessageSide.SYSTEM_OR_UNKNOWN
            }
        } else {
            raw
        }
    }

    fun inferRole(text: String, bounds: Rect, imageWidth: Float, imageHeight: Float = 0f): BlockRole =
        inferRole(text, bounds.left, bounds.top, bounds.right, bounds.bottom, imageWidth, imageHeight)

    fun inferRole(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Float,
        imageHeight: Float = 0f
    ): BlockRole {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return BlockRole.UNKNOWN

        // Check top/bottom screen chrome
        if (imageHeight > 0f) {
            val topRatio = top.toFloat() / imageHeight
            val bottomRatio = bottom.toFloat() / imageHeight
            if (topRatio < TOP_CHROME_RATIO || bottomRatio > BOTTOM_CHROME_RATIO) {
                return BlockRole.SYSTEM_LABEL
            }
        }

        // Standalone pure timestamp (e.g. "10:34", "2:15 PM", "12:00 pm ✓✓")
        // Note: Do NOT mark entire messages as timestamps just because they contain a time!
        if (PURE_TIMESTAMP_REGEX.matches(trimmed)) {
            return BlockRole.TIMESTAMP
        }

        // Centered system labels (e.g., "Today", "Yesterday", date headers, encryption alerts)
        if (SYSTEM_DATE_REGEX.matches(trimmed) || ENCRYPTION_ALERT_REGEX.containsMatchIn(trimmed)) {
            return BlockRole.SYSTEM_LABEL
        }

        // If the text is centered and short, it's likely a date or divider badge
        if (imageWidth > 0f) {
            val leftMargin = left.toFloat() / imageWidth
            val rightMargin = (imageWidth - right.toFloat()) / imageWidth
            val widthRatio = (right - left).toFloat() / imageWidth
            if (Math.abs(leftMargin - rightMargin) < 0.12f && widthRatio < 0.50f && trimmed.length < 35) {
                return BlockRole.SYSTEM_LABEL
            }
        }

        // By default, text on the left or right side is considered conversational message text
        return BlockRole.MESSAGE
    }

    companion object {
        private const val FULL_WIDTH_BANNER_RATIO = 0.88f
        private const val MAX_ALIGNED_EDGE_RATIO = 0.28f
        private const val MARGIN_DIFFERENCE_THRESHOLD = 0.05f
        private const val CENTER_TOLERANCE = 0.12f
        private const val MIN_MARGIN_FOR_CENTERED = 0.10f

        private const val TOP_CHROME_RATIO = 0.075f
        private const val BOTTOM_CHROME_RATIO = 0.925f

        // Matches only pure timestamps, optionally with AM/PM and checkmarks
        private val PURE_TIMESTAMP_REGEX = Regex(
            """^\d{1,2}:\d{2}(\s*(AM|PM|am|pm))?(\s*[✓✔\s]{1,3})?$""",
            RegexOption.IGNORE_CASE
        )

        // Matches common date pills like "Today", "Yesterday", "Monday", "Sep 5", "August 24, 2026"
        private val SYSTEM_DATE_REGEX = Regex(
            """^(Today|Yesterday|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Jan(uary)?|Feb(ruary)?|Mar(ch)?|Apr(il)?|May|Jun(e)?|Jul(y)?|Aug(ust)?|Sep(tember)?|Oct(ober)?|Nov(ember)?|Dec(ember)?)\b.*$""",
            RegexOption.IGNORE_CASE
        )

        private val ENCRYPTION_ALERT_REGEX = Regex(
            """(end-to-end|encrypted|security code|disappearing messages)""",
            RegexOption.IGNORE_CASE
        )
    }
}

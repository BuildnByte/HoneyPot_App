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
     * Classify a block's horizontal position relative to [imageWidth].
     */
    fun classifyBlock(bounds: Rect, imageWidth: Float): MessageSide {
        val centerX = bounds.exactCenterX()
        val third = imageWidth / 3f

        val raw = when {
            centerX < third -> MessageSide.INCOMING
            centerX > 2f * third -> MessageSide.OUTGOING
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

    /**
     * Infer the semantic role of a block from its text and geometry.
     */
    fun inferRole(text: String, bounds: Rect, imageWidth: Float): BlockRole {
        // Very tiny text (< 5 chars) → likely a timestamp or indicator
        if (text.length < MIN_MESSAGE_CHARS) return BlockRole.TIMESTAMP

        // Looks like a time string (e.g. "10:34", "2:15 PM")
        if (TIMESTAMP_REGEX.containsMatchIn(text)) return BlockRole.TIMESTAMP

        // Very narrow block that doesn't span much of the image → could be system label
        val widthFraction = bounds.width().toFloat() / imageWidth
        if (widthFraction < NARROW_FRACTION_THRESHOLD && text.length < SYSTEM_LABEL_MAX_CHARS) {
            return BlockRole.SYSTEM_LABEL
        }

        return BlockRole.MESSAGE
    }

    companion object {
        private const val MIN_MESSAGE_CHARS = 5
        private const val SYSTEM_LABEL_MAX_CHARS = 30
        private const val NARROW_FRACTION_THRESHOLD = 0.25f
        private val TIMESTAMP_REGEX = Regex("""\b\d{1,2}:\d{2}(\s?(AM|PM|am|pm))?\b""")
    }
}

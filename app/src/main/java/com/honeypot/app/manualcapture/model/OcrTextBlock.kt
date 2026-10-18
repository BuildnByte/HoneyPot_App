package com.honeypot.app.manualcapture.model

import android.graphics.Rect

/** Classification of which side of a chat screen a text block belongs to. */
enum class MessageSide { INCOMING, OUTGOING, SYSTEM_OR_UNKNOWN }

/** High-level role of the block, used to decide whether to pre-select it. */
enum class BlockRole { MESSAGE, TIMESTAMP, SYSTEM_LABEL, UNKNOWN }

/**
 * A single OCR-detected text region from a screenshot, enriched with layout
 * classification so the user can review and correct the pre-selection.
 *
 * Note: [confidence] may be null — ML Kit does not always provide it.
 */
data class OcrTextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float?,
    val side: MessageSide,
    val role: BlockRole,
    var isSelected: Boolean
)

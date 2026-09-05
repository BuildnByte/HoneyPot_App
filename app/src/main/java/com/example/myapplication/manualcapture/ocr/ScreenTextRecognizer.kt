package com.example.myapplication.manualcapture.ocr

import android.graphics.Bitmap
import android.util.Log
import com.example.myapplication.manualcapture.model.BlockRole
import com.example.myapplication.manualcapture.model.MessageSide
import com.example.myapplication.manualcapture.model.OcrTextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs ML Kit on-device text recognition (bundled Latin model) on a screenshot
 * and returns a list of [OcrTextBlock] enriched by [MessageSideClassifier].
 *
 * Uses [suspendCancellableCoroutine] to bridge ML Kit's Task callback API
 * to Kotlin coroutines without requiring the play-services coroutines extension.
 *
 * Usage: one instance per capture; close() must be called when done.
 */
class ScreenTextRecognizer {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Recognise all text in [bitmap] and classify blocks using [classifier].
     *
     * Must be called from a background coroutine (Dispatchers.Default or IO).
     * @return an ordered list of [OcrTextBlock], top-to-bottom.
     */
    suspend fun recognise(
        bitmap: Bitmap,
        classifier: MessageSideClassifier
    ): List<OcrTextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)

        val result: Text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { text -> cont.resume(text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
                .addOnCanceledListener { cont.cancel() }
        }

        val imageWidth = bitmap.width.toFloat()
        val blocks = mutableListOf<OcrTextBlock>()

        for (block in result.textBlocks) {
            val blockBounds = block.boundingBox ?: continue
            val blockText = block.text.trim()
            if (blockText.isBlank()) continue

            // Confidence: ML Kit provides it per element but not always per block.
            // Collect element-level confidences where available.
            val confidences = mutableListOf<Float>()
            for (line in block.lines) {
                for (element in line.elements) {
                    val c = element.confidence
                    if (c != null) confidences.add(c)
                }
            }
            val confidence: Float? = if (confidences.isNotEmpty()) {
                confidences.average().toFloat()
            } else null

            val side = classifier.classifyBlock(blockBounds, imageWidth)
            val role = classifier.inferRole(blockText, blockBounds, imageWidth)

            blocks.add(
                OcrTextBlock(
                    text = blockText,
                    bounds = blockBounds,
                    confidence = confidence,
                    side = side,
                    role = role,
                    // Pre-select only blocks classified as INCOMING messages
                    isSelected = side == MessageSide.INCOMING && role == BlockRole.MESSAGE
                )
            )
        }

        // Sort top-to-bottom
        blocks.sortBy { it.bounds.top }
        return blocks
    }

    fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ScreenTextRecognizer"
    }
}

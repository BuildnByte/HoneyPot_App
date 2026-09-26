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
        val imageHeight = bitmap.height.toFloat()
        val blocks = mutableListOf<OcrTextBlock>()

        for (block in result.textBlocks) {
            val lines = block.lines.filter { it.text.isNotBlank() && it.boundingBox != null }
            if (lines.isEmpty()) continue

            // Cluster lines by message side and vertical proximity
            val clusters = mutableListOf<MutableList<Text.Line>>()
            var currentCluster = mutableListOf<Text.Line>()
            var currentSide: MessageSide? = null

            for (line in lines) {
                val lineBounds = line.boundingBox!!
                val lineSide = classifier.classifyBlock(lineBounds, imageWidth, imageHeight)

                if (currentCluster.isEmpty()) {
                    currentCluster.add(line)
                    currentSide = lineSide
                } else {
                    val prevBounds = currentCluster.last().boundingBox!!
                    val verticalGap = lineBounds.top - prevBounds.bottom
                    val lineH = Math.max(prevBounds.height(), 16)

                    // If same side and lines are reasonably close vertically, group into one bubble
                    if (lineSide == currentSide && verticalGap <= lineH * 2.2f) {
                        currentCluster.add(line)
                    } else {
                        clusters.add(currentCluster)
                        currentCluster = mutableListOf(line)
                        currentSide = lineSide
                    }
                }
            }
            if (currentCluster.isNotEmpty()) {
                clusters.add(currentCluster)
            }

            for (cluster in clusters) {
                var left = Int.MAX_VALUE
                var top = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var bottom = Int.MIN_VALUE
                val confidences = mutableListOf<Float>()

                for (line in cluster) {
                    val lb = line.boundingBox!!
                    left = minOf(left, lb.left)
                    top = minOf(top, lb.top)
                    right = maxOf(right, lb.right)
                    bottom = maxOf(bottom, lb.bottom)

                    for (element in line.elements) {
                        element.confidence?.let { confidences.add(it) }
                    }
                }

                val clusterBounds = android.graphics.Rect(left, top, right, bottom)
                val clusterText = cluster.joinToString("\n") { it.text.trim() }.trim()
                if (clusterText.isBlank()) continue

                val confidence: Float? = if (confidences.isNotEmpty()) {
                    confidences.average().toFloat()
                } else null

                val side = classifier.classifyBlock(clusterBounds, imageWidth, imageHeight)
                val role = classifier.inferRole(clusterText, clusterBounds, imageWidth, imageHeight)

                blocks.add(
                    OcrTextBlock(
                        text = clusterText,
                        bounds = clusterBounds,
                        confidence = confidence,
                        side = side,
                        role = role,
                        // Pre-select only blocks classified as INCOMING messages
                        isSelected = side == MessageSide.INCOMING && role == BlockRole.MESSAGE
                    )
                )
            }
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

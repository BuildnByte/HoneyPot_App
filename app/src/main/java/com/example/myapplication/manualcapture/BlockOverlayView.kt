package com.example.myapplication.manualcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.example.myapplication.manualcapture.model.BlockRole
import com.example.myapplication.manualcapture.model.MessageSide
import com.example.myapplication.manualcapture.model.OcrTextBlock
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Custom view that displays the captured screenshot and enables Google Lens-style
 * text selection:
 * - Touch & drag anywhere on the screenshot to draw a selection bounding box.
 * - Multiple bounding boxes are supported.
 * - Each custom bounding box has a top-right '✕' delete badge to remove it.
 * - OCR text blocks intersecting any custom box are automatically selected.
 * - Direct tapping on text blocks is also supported for fine-grained toggling.
 */
class BlockOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minDragSizePx = 18f * density
    private val badgeRadius = 14f * density
    private val badgeTouchRadius = 26f * density

    private var bitmap: Bitmap? = null
    private var blocks: List<OcrTextBlock> = emptyList()
    private var imageWidthPx: Float = 1f

    // User-drawn custom bounding boxes in bitmap/image coordinates
    private val customBoxes = mutableListOf<RectF>()

    // Blocks manually toggled by direct tap
    private val manuallyToggledBlocks = mutableSetOf<OcrTextBlock>()

    // Active drag box during touch-move in view coordinates
    private var activeDragRect: RectF? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isDragging = false

    var onBlockTapped: ((OcrTextBlock) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null
    var onBoxesChanged: ((Int) -> Unit)? = null

    // Paints
    private val incomingSelectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 34, 197, 94) // Green
        style = Paint.Style.FILL
    }
    private val incomingSelectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 22, 163, 74)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val outgoingFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 100, 116, 139) // Slate
        style = Paint.Style.FILL
    }
    private val outgoingStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 100, 116, 139)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val systemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(25, 148, 163, 184)
        style = Paint.Style.FILL
    }

    // Google Lens style custom bounding box paints
    private val customBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 2, 132, 199) // Google Lens cyan/blue tint
        style = Paint.Style.FILL
    }
    private val customBoxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 2, 132, 199)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 229, 255) // Vibrant cyan bracket
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Active drag paints
    private val activeDragFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 0, 229, 255)
        style = Paint.Style.FILL
    }
    private val activeDragStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    // Badge paints for the '✕' remove button on each custom box
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 239, 68, 68) // Red badge
        style = Paint.Style.FILL
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    fun setImageBitmap(bmp: Bitmap) {
        bitmap = bmp
        invalidate()
    }

    fun setBlocks(newBlocks: List<OcrTextBlock>, imgWidth: Float) {
        blocks = newBlocks
        imageWidthPx = imgWidth
        manuallyToggledBlocks.clear()
        updateSelectionState()
        invalidate()
    }

    fun getCustomBoxCount(): Int = customBoxes.size

    fun clearCustomBoxes() {
        customBoxes.clear()
        manuallyToggledBlocks.clear()
        // Restore default incoming selection
        for (block in blocks) {
            block.isSelected = block.side == MessageSide.INCOMING && block.role == BlockRole.MESSAGE
        }
        onBoxesChanged?.invoke(0)
        onSelectionChanged?.invoke()
        invalidate()
    }

    fun selectAllIncoming() {
        customBoxes.clear()
        manuallyToggledBlocks.clear()
        for (block in blocks) {
            block.isSelected = block.side == MessageSide.INCOMING && block.role == BlockRole.MESSAGE
        }
        onBoxesChanged?.invoke(0)
        onSelectionChanged?.invoke()
        invalidate()
    }

    fun selectAll() {
        customBoxes.clear()
        manuallyToggledBlocks.clear()
        for (block in blocks) {
            block.isSelected = true
        }
        onBoxesChanged?.invoke(0)
        onSelectionChanged?.invoke()
        invalidate()
    }

    fun clearAllSelection() {
        customBoxes.clear()
        manuallyToggledBlocks.clear()
        for (block in blocks) {
            block.isSelected = false
        }
        onBoxesChanged?.invoke(0)
        onSelectionChanged?.invoke()
        invalidate()
    }

    private fun updateSelectionState() {
        if (customBoxes.isNotEmpty()) {
            for (block in blocks) {
                val blockRect = RectF(block.bounds)
                val intersectsAny = customBoxes.any { box ->
                    RectF.intersects(box, blockRect) || box.contains(blockRect.centerX(), blockRect.centerY())
                }
                block.isSelected = intersectsAny || manuallyToggledBlocks.contains(block)
            }
        } else {
            // When no custom boxes exist, respect auto-incoming and manual toggles
            for (block in blocks) {
                val defaultSelected = block.side == MessageSide.INCOMING && block.role == BlockRole.MESSAGE
                block.isSelected = if (manuallyToggledBlocks.contains(block)) {
                    !defaultSelected
                } else {
                    defaultSelected
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * scale) / 2f
        val dy = (vh - bmp.height * scale) / 2f

        // 1. Draw screenshot
        val destRect = RectF(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale)
        canvas.drawBitmap(bmp, null, destRect, null)

        // 2. Draw OCR blocks
        for (block in blocks) {
            val r = block.bounds
            val scaledRect = RectF(
                dx + r.left * scale,
                dy + r.top * scale,
                dx + r.right * scale,
                dy + r.bottom * scale
            )
            when {
                block.isSelected -> {
                    canvas.drawRoundRect(scaledRect, 8f * density, 8f * density, incomingSelectedFillPaint)
                    canvas.drawRoundRect(scaledRect, 8f * density, 8f * density, incomingSelectedStrokePaint)
                }
                block.side == MessageSide.OUTGOING -> {
                    canvas.drawRoundRect(scaledRect, 6f * density, 6f * density, outgoingFillPaint)
                    canvas.drawRoundRect(scaledRect, 6f * density, 6f * density, outgoingStrokePaint)
                }
                else -> {
                    canvas.drawRoundRect(scaledRect, 4f * density, 4f * density, systemPaint)
                }
            }
        }

        // 3. Draw committed custom bounding boxes (Google Lens style)
        for (box in customBoxes) {
            val vRect = RectF(
                dx + box.left * scale,
                dy + box.top * scale,
                dx + box.right * scale,
                dy + box.bottom * scale
            )
            canvas.drawRoundRect(vRect, 10f * density, 10f * density, customBoxFillPaint)
            canvas.drawRoundRect(vRect, 10f * density, 10f * density, customBoxStrokePaint)

            // Corner brackets
            drawCornerBrackets(canvas, vRect, 14f * density)

            // Top-right close badge ('✕')
            drawCloseBadge(canvas, vRect.right, vRect.top)
        }

        // 4. Draw active dragging box (in progress)
        activeDragRect?.let { drag ->
            canvas.drawRoundRect(drag, 10f * density, 10f * density, activeDragFillPaint)
            canvas.drawRoundRect(drag, 10f * density, 10f * density, activeDragStrokePaint)
            drawCornerBrackets(canvas, drag, 16f * density)
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, len: Float) {
        val actualLen = minOf(len, r.width() / 3f, r.height() / 3f)
        if (actualLen <= 2f) return

        // Top-Left
        canvas.drawLine(r.left, r.top + actualLen, r.left, r.top, cornerBracketPaint)
        canvas.drawLine(r.left, r.top, r.left + actualLen, r.top, cornerBracketPaint)

        // Top-Right
        canvas.drawLine(r.right - actualLen, r.top, r.right, r.top, cornerBracketPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + actualLen, cornerBracketPaint)

        // Bottom-Left
        canvas.drawLine(r.left, r.bottom - actualLen, r.left, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.left, r.bottom, r.left + actualLen, r.bottom, cornerBracketPaint)

        // Bottom-Right
        canvas.drawLine(r.right - actualLen, r.bottom, r.right, r.bottom, cornerBracketPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - actualLen, cornerBracketPaint)
    }

    private fun drawCloseBadge(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, badgeRadius, badgeBgPaint)
        val crossHalf = 5.5f * density
        canvas.drawLine(cx - crossHalf, cy - crossHalf, cx + crossHalf, cy + crossHalf, badgeStrokePaint)
        canvas.drawLine(cx - crossHalf, cy + crossHalf, cx + crossHalf, cy - crossHalf, badgeStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return super.onTouchEvent(event)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * scale) / 2f
        val dy = (vh - bmp.height * scale) / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                activeDragRect = null

                // Check if user tapped a close badge on any custom box
                for (i in customBoxes.indices.reversed()) {
                    val box = customBoxes[i]
                    val badgeX = dx + box.right * scale
                    val badgeY = dy + box.top * scale
                    if (hypot(event.x - badgeX, event.y - badgeY) <= badgeTouchRadius) {
                        // Delete this box!
                        customBoxes.removeAt(i)
                        updateSelectionState()
                        onBoxesChanged?.invoke(customBoxes.size)
                        onSelectionChanged?.invoke()
                        invalidate()
                        return true
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dragDist = hypot(event.x - downX, event.y - downY)
                if (dragDist > touchSlop) {
                    isDragging = true
                    val l = min(downX, event.x)
                    val t = min(downY, event.y)
                    val r = max(downX, event.x)
                    val b = max(downY, event.y)
                    activeDragRect = RectF(l, t, r, b)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isDragging && activeDragRect != null) {
                    val rect = activeDragRect!!
                    activeDragRect = null
                    isDragging = false

                    if (rect.width() >= minDragSizePx && rect.height() >= minDragSizePx) {
                        // Convert view drag rect to image coordinates
                        val imgL = ((rect.left - dx) / scale).coerceIn(0f, bmp.width.toFloat())
                        val imgT = ((rect.top - dy) / scale).coerceIn(0f, bmp.height.toFloat())
                        val imgR = ((rect.right - dx) / scale).coerceIn(0f, bmp.width.toFloat())
                        val imgB = ((rect.bottom - dy) / scale).coerceIn(0f, bmp.height.toFloat())

                        val imageBox = RectF(min(imgL, imgR), min(imgT, imgB), max(imgL, imgR), max(imgT, imgB))
                        customBoxes.add(imageBox)
                        updateSelectionState()
                        onBoxesChanged?.invoke(customBoxes.size)
                        onSelectionChanged?.invoke()
                    }
                    invalidate()
                    return true
                }

                // If not dragging, treat as tap on an OCR block
                activeDragRect = null
                isDragging = false

                val imgX = ((event.x - dx) / scale).toInt()
                val imgY = ((event.y - dy) / scale).toInt()

                val tapped = blocks.lastOrNull { it.bounds.contains(imgX, imgY) }
                if (tapped != null) {
                    if (manuallyToggledBlocks.contains(tapped)) {
                        manuallyToggledBlocks.remove(tapped)
                    } else {
                        manuallyToggledBlocks.add(tapped)
                    }
                    tapped.isSelected = !tapped.isSelected
                    onBlockTapped?.invoke(tapped)
                    onSelectionChanged?.invoke()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activeDragRect = null
                isDragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

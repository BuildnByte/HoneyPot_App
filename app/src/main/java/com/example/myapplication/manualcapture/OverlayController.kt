package com.example.myapplication.manualcapture

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.R

/**
 * Controls the floating scan bubble overlay window.
 *
 * Designed for instantaneous, zero-friction interaction:
 * - Single tap on bubble: immediately triggers silent screen capture and in-place Lens overlay.
 * - Drag: reposition bubble anywhere on screen (snaps cleanly to nearest edge on release).
 * - Drag to bottom target: drop on the bottom dismiss pill to immediately remove the overlay from screen.
 * - Long press: close/disable the overlay bubble.
 */
class OverlayController(
    private val context: Context,
    private val prefs: ManualCapturePrefs,
    private val onCapture: () -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var dismissTargetView: View? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var isOverDismissTarget = false

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_MyApplication)
        val inflater = LayoutInflater.from(themedContext)

        // 1. Add bottom dismiss target (hidden by default, non-touchable passthrough)
        val paramsDismiss = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.y = 0
        }
        val dismissView = inflater.inflate(R.layout.overlay_dismiss_target, null)
        dismissView.visibility = View.GONE
        dismissTargetView = dismissView
        windowManager.addView(dismissView, paramsDismiss)

        // 2. Add floating scan squircle button
        val view = inflater.inflate(R.layout.overlay_scan, null)
        overlayView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = 40
            it.y = 300
        }

        setupViews(view, params)
        windowManager.addView(view, params)
    }

    private fun setupViews(view: View, params: WindowManager.LayoutParams) {
        val shieldBtn = view.findViewById<View>(R.id.btnShield)
        val cardExpanded = view.findViewById<View>(R.id.cardExpanded)
        cardExpanded?.visibility = View.GONE

        // Drag & single-tap support on shield button
        shieldBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isOverDismissTarget = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val metrics = context.resources.displayMetrics
                        val density = metrics.density
                        val screenHeight = metrics.heightPixels
                        val screenWidth = metrics.widthPixels

                        // Reveal dismiss target at bottom if hidden
                        dismissTargetView?.let { dtView ->
                            if (dtView.visibility != View.VISIBLE) {
                                dtView.visibility = View.VISIBLE
                                dtView.alpha = 0f
                                dtView.animate().alpha(1f).setDuration(150).start()
                            }
                        }

                        // Move floating button
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, params)

                        // Check if dragged to dismiss zone (bottom ~140dp & within center ~280dp)
                        val bottomThreshold = screenHeight - (140 * density)
                        val xDistFromCenter = Math.abs(event.rawX - (screenWidth / 2f))
                        val horizontalThreshold = 140 * density
                        val currentlyOver = event.rawY > bottomThreshold && xDistFromCenter < horizontalThreshold

                        val layoutDismiss = dismissTargetView?.findViewById<View>(R.id.layoutDismissCircle)
                        val tvDismiss = dismissTargetView?.findViewById<TextView>(R.id.tvDismissText)

                        if (currentlyOver && !isOverDismissTarget) {
                            isOverDismissTarget = true
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            layoutDismiss?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                            layoutDismiss?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(120)?.start()
                            tvDismiss?.text = "Release to remove"
                            view.animate().scaleX(0.75f).scaleY(0.75f).alpha(0.6f).setDuration(120).start()
                        } else if (!currentlyOver && isOverDismissTarget) {
                            isOverDismissTarget = false
                            layoutDismiss?.backgroundTintList = null
                            layoutDismiss?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                            tvDismiss?.text = "Drag here to remove"
                            view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(120).start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Hide bottom dismiss target
                    dismissTargetView?.let { dtView ->
                        dtView.animate().alpha(0f).setDuration(150).withEndAction {
                            dtView.visibility = View.GONE
                            val layoutDismiss = dtView.findViewById<View>(R.id.layoutDismissCircle)
                            layoutDismiss?.backgroundTintList = null
                            layoutDismiss?.scaleX = 1.0f
                            layoutDismiss?.scaleY = 1.0f
                        }.start()
                    }

                    view.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(100).start()

                    if (isOverDismissTarget) {
                        // User dropped the overlay on the dismiss target!
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        Toast.makeText(context, "Scan overlay removed", Toast.LENGTH_SHORT).show()
                        onClose()
                    } else if (!isDragging) {
                        // Single tap: trigger capture immediately!
                        onCapture()
                    } else {
                        // Snaps cleanly to the nearest screen edge (left or right)
                        val metrics = context.resources.displayMetrics
                        val screenWidth = metrics.widthPixels
                        val density = metrics.density
                        val buttonWidth = 52 * density
                        val currentCenterX = params.x + buttonWidth / 2f
                        val targetX = if (currentCenterX < screenWidth / 2f) (16 * density).toInt() else (screenWidth - (68 * density)).toInt()
                        params.x = targetX.coerceIn(0, (screenWidth - buttonWidth).toInt())
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                else -> false
            }
        }

        shieldBtn.setOnLongClickListener {
            Toast.makeText(context, "Scan overlay removed", Toast.LENGTH_SHORT).show()
            onClose()
            true
        }
    }

    fun refreshBadge() {
        // Redundant with in-place analysis; kept for API compatibility
    }

    fun hideTemporarily() {
        overlayView?.visibility = View.GONE
        dismissTargetView?.visibility = View.GONE
    }

    fun restoreVisibility() {
        overlayView?.visibility = View.VISIBLE
    }

    fun hide() {
        dismissTargetView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dismissTargetView = null

        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    companion object {
        private const val TAG = "OverlayController"
    }
}

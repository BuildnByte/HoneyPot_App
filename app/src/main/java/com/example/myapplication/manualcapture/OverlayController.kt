package com.example.myapplication.manualcapture

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.myapplication.R
import com.google.android.material.button.MaterialButton

/**
 * Controls the floating scan overlay window.
 *
 * The overlay is a small draggable shield button. Tapping it expands to a
 * compact card with:
 *  - "View response" → opens the latest ManualAnalysisActivity result.
 *  - "Capture & analyse" → launches CaptureConsentActivity.
 *
 * This controller does NOT hold a Service reference; it is passed callbacks.
 * Accessibility: all interactive elements have contentDescriptions; state changes
 * are announced via ViewCompat.setAccessibilityLiveRegion where applicable.
 */
class OverlayController(
    private val context: Context,
    private val prefs: ManualCapturePrefs,
    private val onCapture: () -> Unit,
    private val onViewResult: () -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isExpanded = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        val themedContext = ContextThemeWrapper(context, R.style.Theme_MyApplication)
        val inflater = LayoutInflater.from(themedContext)
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
        refreshBadge()
    }

    private fun setupViews(view: View, params: WindowManager.LayoutParams) {
        val shieldBtn = view.findViewById<View>(R.id.btnShield)
        val cardExpanded = view.findViewById<View>(R.id.cardExpanded)
        val btnCapture = view.findViewById<MaterialButton>(R.id.btnCapture)
        val btnViewResult = view.findViewById<MaterialButton>(R.id.btnViewResult)
        val btnCloseOverlay = view.findViewById<View>(R.id.btnClose)

        cardExpanded.visibility = View.GONE

        // Drag support on shield button
        shieldBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Toggle expanded card
                        isExpanded = !isExpanded
                        cardExpanded.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        // Need focus to handle button clicks in card
                        val flags = if (isExpanded)
                            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        else
                            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        params.flags = flags
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                else -> false
            }
        }

        btnCapture.setOnClickListener {
            collapse(view, params)
            onCapture()
        }

        btnViewResult.setOnClickListener {
            collapse(view, params)
            onViewResult()
        }

        btnCloseOverlay.setOnClickListener {
            onClose()
        }
    }

    private fun collapse(view: View, params: WindowManager.LayoutParams) {
        isExpanded = false
        view.findViewById<View>(R.id.cardExpanded).visibility = View.GONE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(view, params)
    }

    /**
     * Update the "View response" badge based on whether there is an unviewed result.
     */
    fun refreshBadge() {
        val view = overlayView ?: return
        val btnViewResult = view.findViewById<MaterialButton>(R.id.btnViewResult)
        val hasPending = prefs.unviewedCaptureId != null
        val isAnalysing = prefs.isAnalysing
        btnViewResult.text = when {
            isAnalysing -> "Analysing…"
            hasPending -> "View response ●"
            else -> "View response"
        }
        btnViewResult.isEnabled = hasPending || (!isAnalysing)
        btnViewResult.contentDescription = when {
            isAnalysing -> "Analysis is in progress"
            hasPending -> "New scan result available — View response"
            else -> "View the latest scan response"
        }
    }

    fun hideTemporarily() {
        overlayView?.visibility = View.GONE
    }

    fun restoreVisibility() {
        overlayView?.visibility = View.VISIBLE
    }

    fun hide() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        isExpanded = false
    }

    companion object {
        private const val TAG = "OverlayController"
    }
}

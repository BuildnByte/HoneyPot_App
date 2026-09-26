package com.example.myapplication.manualcapture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

import android.app.NotificationManager
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.delay

/**
 * Accessibility service that provides Google Lens / Assistant-style silent screen capture.
 *
 * Uses Android 11+ [takeScreenshot] API to capture directly from the hardware buffer
 * without showing any "recording or casting" dialogs or status bar recording indicators.
 * Also hosts the floating overlay bubble directly without requiring a foreground notification.
 */
class HoneypotAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var overlayController: OverlayController? = null
    private val inPlaceLensController by lazy { InPlaceLensController(this) }
    private val prefs by lazy { ManualCapturePrefs(this) }
    private var isCapturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "HoneypotAccessibilityService connected and active")

        if (prefs.overlayEnabled && Settings.canDrawOverlays(this)) {
            showOverlayInternal()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != packageName) {
                lastForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "HoneypotAccessibilityService interrupted")
    }

    override fun onDestroy() {
        isServiceRunning = false
        hideOverlayInternal()
        if (instance === this) {
            instance = null
        }
        job.cancel()
        super.onDestroy()
    }

    private fun showOverlayInternal() {
        if (overlayController != null) return
        if (!Settings.canDrawOverlays(this)) return

        // Stop legacy ScanOverlayService and dismiss any lingering foreground notification
        try {
            stopService(Intent(this, ScanOverlayService::class.java))
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(9002)
        } catch (_: Exception) {}

        overlayController = OverlayController(
            context = this,
            prefs = prefs,
            onCapture = {
                performAccessibilityCapture()
            },
            onClose = {
                prefs.overlayEnabled = false
                hideOverlayInternal()
                ScanQuickSettingsTileService.requestTileUpdate(this)
            }
        )
        overlayController?.show()
    }

    private fun hideOverlayInternal() {
        overlayController?.hide()
        overlayController = null
        inPlaceLensController.dismiss()
    }

    private fun performAccessibilityCapture() {
        if (isCapturing) {
            Log.w(TAG, "Capture already in progress, ignoring duplicate call")
            return
        }

        val fgPackage = lastForegroundPackage ?: AppForegroundDetector.getForegroundPackage(this)
        val appName = AppForegroundDetector.getAppName(this, fgPackage)

        if (!AppForegroundDetector.isAppAllowed(prefs, fgPackage)) {
            Toast.makeText(this, "Capture not allowed for $appName per settings", Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        overlayController?.hideTemporarily()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Short delay so overlay is excluded from the screenshot
                delay(120L)
                captureScreen(this@HoneypotAccessibilityService) { success, errorMsg, captureId, bitmap ->
                    isCapturing = false
                    if (success && captureId != null && bitmap != null) {
                        inPlaceLensController.show(captureId, bitmap) {
                            overlayController?.restoreVisibility()
                        }
                    } else {
                        overlayController?.restoreVisibility()
                        if (!success && errorMsg != null) {
                            Toast.makeText(this@HoneypotAccessibilityService, errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility capture error", e)
                overlayController?.restoreVisibility()
                isCapturing = false
            }
        }
    }

    /**
     * Captures the current screen silently from the hardware buffer,
     * writes the image to evidence storage, persists a DB row, and launches [CaptureReviewActivity].
     */
    fun captureScreen(
        context: Context,
        onComplete: (success: Boolean, error: String?, captureId: String?, bitmap: Bitmap?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onComplete(false, "Silent screenshot requires Android 11 or newer", null, null)
            return
        }

        val displayId = Display.DEFAULT_DISPLAY

        takeScreenshot(
            displayId,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    val rawBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    
                    if (rawBitmap == null) {
                        hardwareBuffer.close()
                        onComplete(false, "Failed to decode screenshot from hardware buffer", null, null)
                        return
                    }

                    // Copy to software bitmap so compress() functions reliably
                    val softwareBitmap = try {
                        rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error copying hardware buffer to software bitmap", e)
                        rawBitmap
                    }
                    hardwareBuffer.close()

                    scope.launch {
                        try {
                            val fgPackage = lastForegroundPackage ?: AppForegroundDetector.getForegroundPackage(context)
                            val appName = AppForegroundDetector.getAppName(context, fgPackage)

                            val captureDir = File(context.filesDir, "evidence_captures").also { it.mkdirs() }
                            val captureId = UUID.randomUUID().toString()
                            val tmpFile = File(captureDir, "$captureId.tmp")
                            val finalFile = File(captureDir, "$captureId.png")

                            FileOutputStream(tmpFile).use { out ->
                                softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.flush()
                            }
                            tmpFile.renameTo(finalFile)

                            val sha256 = computeSha256(finalFile)

                            val capture = ManualCapture(
                                id = captureId,
                                sourceApp = appName,
                                imagePath = finalFile.absolutePath,
                                imageSha256 = sha256,
                                capturedAt = System.currentTimeMillis(),
                                width = softwareBitmap.width,
                                height = softwareBitmap.height,
                                status = "CAPTURED"
                            )
                            val db = KnowledgeBaseDatabase.getInstance(context.applicationContext)
                            db.knowledgeBaseDao().insertManualCapture(capture)

                            withContext(Dispatchers.Main) {
                                onComplete(true, null, captureId, softwareBitmap)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving capture from accessibility service", e)
                            withContext(Dispatchers.Main) {
                                onComplete(false, e.message, null, null)
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Accessibility takeScreenshot failed with error code: $errorCode")
                    val errorMsg = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal system error"
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "No accessibility access"
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Capturing too fast, please try again"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "Invalid display"
                        else -> "Capture failed (code $errorCode)"
                    }
                    onComplete(false, errorMsg, null, null)
                }
            }
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "HoneypotAccessService"

        var instance: HoneypotAccessibilityService? = null
            private set

        var isServiceRunning: Boolean = false
            private set

        var lastForegroundPackage: String? = null
            private set

        fun captureCurrentScreen(
            context: Context,
            onComplete: (success: Boolean, error: String?, captureId: String?, bitmap: Bitmap?) -> Unit
        ) {
            val service = instance
            if (service == null) {
                onComplete(false, "Accessibility Service is not enabled", null, null)
                return
            }
            service.captureScreen(context, onComplete)
        }

        fun showOverlay(): Boolean {
            val service = instance ?: return false
            service.showOverlayInternal()
            return true
        }

        fun hideOverlay() {
            instance?.hideOverlayInternal()
        }

        /**
         * Checks whether Honeypot is enabled in Android's secure accessibility settings.
         */
        fun isAccessibilityEnabledInSettings(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myComponentName = android.content.ComponentName(context, HoneypotAccessibilityService::class.java).flattenToString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myComponentName, ignoreCase = true) ||
                    componentName.contains(context.packageName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**
         * Programmatically dismisses the notification shade / Quick Settings panel.
         */
        fun dismissNotificationShade(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val success = service.performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                if (success) true else service.performGlobalAction(GLOBAL_ACTION_BACK)
            } else {
                service.performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }

        /**
         * Builds an intent that opens Accessibility settings, passing extras
         * to highlight or deep-link to Honeypot's specific service where supported.
         */
        fun buildSettingsIntent(context: Context): Intent {
            val cn = android.content.ComponentName(context, HoneypotAccessibilityService::class.java)
            val cnString = cn.flattenToString()

            return Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:fragment_args_key", cnString)
                val bundle = android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", cnString)
                    putString("extra_fragment_arg_key", cnString)
                }
                putExtra(":settings:show_fragment_args", bundle)
                putExtra("extra_fragment_arg_key", cnString)
                putExtra(Intent.EXTRA_COMPONENT_NAME, cnString)
                putExtra("android.intent.extra.COMPONENT_NAME", cnString)
            }
        }
    }
}

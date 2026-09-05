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

/**
 * Accessibility service that provides Google Lens / Assistant-style silent screen capture.
 *
 * Uses Android 11+ [takeScreenshot] API to capture directly from the hardware buffer
 * without showing any "recording or casting" dialogs or status bar recording indicators.
 */
class HoneypotAccessibilityService : AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        Log.i(TAG, "HoneypotAccessibilityService connected and active")
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
        if (instance === this) {
            instance = null
        }
        job.cancel()
        super.onDestroy()
    }

    /**
     * Captures the current screen silently from the hardware buffer,
     * writes the image to evidence storage, persists a DB row, and launches [CaptureReviewActivity].
     */
    fun captureScreen(context: Context, onComplete: (Boolean, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onComplete(false, "Silent screenshot requires Android 11 or newer")
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
                        onComplete(false, "Failed to decode screenshot from hardware buffer")
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
                                val reviewIntent = Intent(context, CaptureReviewActivity::class.java).apply {
                                    putExtra(CaptureReviewActivity.EXTRA_CAPTURE_ID, captureId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(reviewIntent)
                                onComplete(true, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving capture from accessibility service", e)
                            withContext(Dispatchers.Main) {
                                onComplete(false, e.message)
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
                    onComplete(false, errorMsg)
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

        fun captureCurrentScreen(context: Context, onComplete: (Boolean, String?) -> Unit) {
            val service = instance
            if (service == null) {
                onComplete(false, "Accessibility Service is not enabled")
                return
            }
            service.captureScreen(context, onComplete)
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

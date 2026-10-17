package com.example.myapplication.manualcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * One-shot MediaProjection foreground service.
 *
 * Lifecycle:
 * 1. Started by [CaptureConsentActivity] with the MediaProjection result data.
 * 2. Calls startForeground() immediately to satisfy Android 14 requirements.
 * 3. Captures exactly one frame, saves it atomically to filesDir/evidence_captures/<uuid>.png.
 * 4. Computes SHA-256, inserts a [ManualCapture] DB row (status = CAPTURED).
 * 5. Launches [CaptureReviewActivity] with the capture ID.
 * 6. Releases all MediaProjection resources in finally block and stops itself.
 *
 * Privacy: no persistent recording, no repeated captures, no content uploaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OneShotCaptureService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val sourceApp = intent?.getStringExtra(EXTRA_SOURCE_APP) ?: "Unknown"

        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result — stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                captureAndSave(resultCode, resultData, sourceApp)
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
            } finally {
                releaseResources()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun captureAndSave(resultCode: Int, data: Intent, sourceApp: String) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        // Derive screen dimensions using modern APIs (avoids deprecated Display methods)
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { b ->
                Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
            }
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
        val (width, height, dpi) = metrics

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HoneypotCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        // Wait for the first non-null image (bounded to 5 seconds)
        val bitmap: Bitmap? = withTimeoutOrNull(5_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                var isResumed = false
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                imageReader!!.setOnImageAvailableListener({ reader ->
                    if (isResumed) return@setOnImageAvailableListener
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        isResumed = true
                        var bmp: Bitmap? = null
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width
                            val rawBmp = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            rawBmp.copyPixelsFromBuffer(buffer)
                            bmp = Bitmap.createBitmap(rawBmp, 0, 0, width, height)
                            rawBmp.recycle()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing image", e)
                        } finally {
                            image.close()
                        }
                        reader.setOnImageAvailableListener(null, null)
                        if (continuation.isActive) {
                            continuation.resume(bmp) {
                                bmp?.recycle()
                            }
                        } else {
                            bmp?.recycle()
                        }
                    }
                }, handler)
                
                continuation.invokeOnCancellation {
                    imageReader?.setOnImageAvailableListener(null, null)
                }
            }
        }

        if (bitmap == null) {
            Log.e(TAG, "Timed out waiting for screen frame")
            return
        }

        // --- Atomic file write ---
        val captureDir = File(cacheDir, "scan_cache").also { it.mkdirs() }
        val captureId = UUID.randomUUID().toString()
        val tmpFile = File(captureDir, "$captureId.tmp")
        val finalFile = File(captureDir, "$captureId.png")

        FileOutputStream(tmpFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        bitmap.recycle()

        tmpFile.renameTo(finalFile)

        // SHA-256
        val sha256 = computeSha256(finalFile)

        // Persist DB row
        val capture = ManualCapture(
            id = captureId,
            sourceApp = sourceApp,
            imagePath = finalFile.absolutePath,
            imageSha256 = sha256,
            capturedAt = System.currentTimeMillis(),
            width = width,
            height = height,
            status = "CAPTURED"
        )
        val db = KnowledgeBaseDatabase.getInstance(applicationContext)
        db.knowledgeBaseDao().insertManualCapture(capture)

        // Launch review activity
        val reviewIntent = Intent(this, CaptureReviewActivity::class.java).apply {
            putExtra(CaptureReviewActivity.EXTRA_CAPTURE_ID, captureId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(reviewIntent)
    }

    private fun releaseResources() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        job.cancel()
        releaseResources()
        super.onDestroy()
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Honeypot is capturing one screenshot for analysis"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle("Capturing screenshot…")
            .setContentText("Honeypot is capturing one screenshot for scam analysis")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "OneShotCaptureService"
        private const val NOTIF_ID = 9001
        private const val CHANNEL_ID = "honeypot_capture"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SOURCE_APP = "extra_source_app"

        fun buildIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            sourceApp: String
        ): Intent = Intent(context, OneShotCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_SOURCE_APP, sourceApp)
        }
    }
}

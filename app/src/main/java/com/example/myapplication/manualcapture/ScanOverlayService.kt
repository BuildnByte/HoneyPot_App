package com.example.myapplication.manualcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.myapplication.R
import com.example.myapplication.knowledge.KnowledgeBaseDatabase
import com.example.myapplication.manualcapture.model.ManualCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Foreground service that hosts the floating scan overlay and manages
 * the active [MediaProjection] session for zero-prompt on-demand captures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanOverlayService : LifecycleService() {

    private val prefs by lazy { ManualCapturePrefs(this) }
    private var overlayController: OverlayController? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceInForeground()
        startOverlay()
    }

    private fun startServiceInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgType = if (isCaptureReady) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIF_ID, buildNotification(), fgType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = if (isCaptureReady) {
                @Suppress("DEPRECATION")
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                @Suppress("DEPRECATION")
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            startForeground(NOTIF_ID, buildNotification(), fgType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        overlayController = OverlayController(
            context = this,
            prefs = prefs,
            onCapture = {
                if (HoneypotAccessibilityService.isServiceRunning) {
                    performAccessibilityCapture()
                } else if (isCaptureReady) {
                    performCapture()
                } else {
                    Toast.makeText(this, "Tap 'Downloaded apps' \u2794 'Honeypot' to enable", Toast.LENGTH_LONG).show()
                    startActivity(HoneypotAccessibilityService.buildSettingsIntent(this))
                }
            },
            onViewResult = {
                val captureId = prefs.unviewedCaptureId ?: return@OverlayController
                startActivity(ManualAnalysisActivity.buildIntent(this, captureId))
            },
            onClose = {
                prefs.overlayEnabled = false
                ScanQuickSettingsTileService.requestTileUpdate(this)
                stopSelf()
            }
        )
        overlayController?.show()
    }

    private fun performAccessibilityCapture() {
        if (isCapturing) {
            Log.w(TAG, "Capture already in progress, ignoring duplicate call")
            return
        }

        val fgPackage = HoneypotAccessibilityService.lastForegroundPackage
            ?: AppForegroundDetector.getForegroundPackage(this)
        val appName = AppForegroundDetector.getAppName(this, fgPackage)

        if (!AppForegroundDetector.isAppAllowed(prefs, fgPackage)) {
            Toast.makeText(this, "Capture not allowed for $appName per settings", Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        overlayController?.hideTemporarily()

        scope.launch {
            try {
                // Short delay so overlay is excluded from the screenshot
                delay(120L)
                HoneypotAccessibilityService.captureCurrentScreen(this@ScanOverlayService) { success, errorMsg ->
                    overlayController?.restoreVisibility()
                    isCapturing = false
                    if (!success && errorMsg != null) {
                        Toast.makeText(this@ScanOverlayService, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility capture error", e)
                overlayController?.restoreVisibility()
                isCapturing = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_INIT_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != 0 && resultData != null) {
                    initProjection(resultCode, resultData)
                }
            }
            ACTION_CAPTURE -> {
                performCapture()
            }
        }
        overlayController?.refreshBadge()
        return START_STICKY
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        // MUST promote foreground service to MEDIA_PROJECTION BEFORE calling getMediaProjection!
        isCaptureReady = true
        startServiceInForeground()

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain MediaProjection from token", e)
            isCaptureReady = false
            startServiceInForeground()
            Toast.makeText(this, "Permission initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection instance is null")
            isCaptureReady = false
            startServiceInForeground()
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection stopped by system")
                releaseProjectionResources()
            }
        }, Handler(Looper.getMainLooper()))

        val (width, height, dpi) = getScreenMetrics()
        screenWidth = width
        screenHeight = height
        screenDpi = dpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "HoneypotCaptureDisplay",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.d(TAG, "VirtualDisplay stopped")
                }
            },
            Handler(Looper.getMainLooper())
        )

        Log.i(TAG, "MediaProjection initialized. Capturing first frame.")
        performCapture()
    }

    private fun performCapture() {
        if (isCapturing) {
            Log.w(TAG, "Capture already in progress, ignoring duplicate call")
            return
        }

        val vd = virtualDisplay
        val reader = imageReader
        if (vd == null || reader == null || mediaProjection == null) {
            Log.w(TAG, "Projection session not active, prompting user")
            val detectedPkg = AppForegroundDetector.getForegroundPackage(this)
            val appName = AppForegroundDetector.getAppName(this, detectedPkg)
            startActivity(CaptureConsentActivity.buildIntent(this, appName))
            return
        }

        val fgPackage = AppForegroundDetector.getForegroundPackage(this)
        val appName = AppForegroundDetector.getAppName(this, fgPackage)
        if (!AppForegroundDetector.isAppAllowed(prefs, fgPackage)) {
            Toast.makeText(this, "Capture not allowed for $appName per settings", Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        overlayController?.hideTemporarily()

        scope.launch {
            try {
                // Allow WindowManager a frame to detach the overlay view from screen rendering
                delay(120L)

                val (width, height) = screenWidth to screenHeight

                // Drain any stale image from previous frames
                try {
                    var stale = reader.acquireLatestImage()
                    while (stale != null) {
                        stale.close()
                        stale = reader.acquireLatestImage()
                    }
                } catch (_: Exception) {}

                val bitmap: Bitmap? = withTimeoutOrNull(4_000L) {
                    suspendCancellableCoroutine { cont ->
                        var isResumed = false
                        val handler = Handler(Looper.getMainLooper())
                        reader.setOnImageAvailableListener({ r ->
                            if (isResumed) return@setOnImageAvailableListener
                            val image = r.acquireLatestImage()
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
                                    Log.e(TAG, "Error decoding frame", e)
                                } finally {
                                    image.close()
                                }
                                r.setOnImageAvailableListener(null, null)
                                if (cont.isActive) {
                                    cont.resume(bmp) { bmp?.recycle() }
                                } else {
                                    bmp?.recycle()
                                }
                            }
                        }, handler)

                        cont.invokeOnCancellation {
                            reader.setOnImageAvailableListener(null, null)
                        }
                    }
                }

                reader.setOnImageAvailableListener(null, null)

                if (bitmap == null) {
                    Log.e(TAG, "Timed out waiting for screen frame")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScanOverlayService, "Screen capture timed out", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val captureDir = File(filesDir, "evidence_captures").also { it.mkdirs() }
                    val captureId = UUID.randomUUID().toString()
                    val tmpFile = File(captureDir, "$captureId.tmp")
                    val finalFile = File(captureDir, "$captureId.png")

                    FileOutputStream(tmpFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                    }
                    bitmap.recycle()
                    tmpFile.renameTo(finalFile)

                    val sha256 = computeSha256(finalFile)

                    val capture = ManualCapture(
                        id = captureId,
                        sourceApp = appName,
                        imagePath = finalFile.absolutePath,
                        imageSha256 = sha256,
                        capturedAt = System.currentTimeMillis(),
                        width = width,
                        height = height,
                        status = "CAPTURED"
                    )
                    val db = KnowledgeBaseDatabase.getInstance(applicationContext)
                    db.knowledgeBaseDao().insertManualCapture(capture)

                    val reviewIntent = Intent(this@ScanOverlayService, CaptureReviewActivity::class.java).apply {
                        putExtra(CaptureReviewActivity.EXTRA_CAPTURE_ID, captureId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(reviewIntent)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Capture execution error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScanOverlayService, "Capture error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    overlayController?.restoreVisibility()
                    isCapturing = false
                }
            }
        }
    }

    private fun getScreenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
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

    private fun releaseProjectionResources() {
        isCaptureReady = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    override fun onDestroy() {
        isCaptureReady = false
        job.cancel()
        releaseProjectionResources()
        overlayController?.hide()
        overlayController = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scan overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Honeypot scan overlay is active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle("Honeypot scan overlay active")
            .setContentText("Tap to scan a suspicious message")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "ScanOverlayService"
        private const val NOTIF_ID = 9002
        private const val CHANNEL_ID = "honeypot_overlay"

        const val ACTION_INIT_PROJECTION = "com.example.myapplication.ACTION_INIT_PROJECTION"
        const val ACTION_CAPTURE = "com.example.myapplication.ACTION_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var isCaptureReady: Boolean = false
            private set

        fun buildProjectionIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent
        ): Intent = Intent(context, ScanOverlayService::class.java).apply {
            action = ACTION_INIT_PROJECTION
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
        }

        fun buildCaptureIntent(context: Context): Intent =
            Intent(context, ScanOverlayService::class.java).apply {
                action = ACTION_CAPTURE
            }
    }
}

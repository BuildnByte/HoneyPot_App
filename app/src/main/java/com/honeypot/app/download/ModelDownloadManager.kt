package com.honeypot.app.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages post-install downloading, verification, and storage of on-device ML models
 * (MuRIL BERT ONNX classifier and Universal Sentence Encoder Lite).
 *
 * For instructions on how to retrain, quantize, and publish new models to GitHub Releases,
 * see: MODEL_UPDATE_GUIDE.md in the project root.
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"

    // Directory inside context.filesDir where models are stored
    const val MODELS_DIR_NAME = "models"

    /**
     * GitHub Releases configuration for remote model hosting.
     * Download URL format:
     * https://github.com/<owner>/<repo>/releases/download/<tag>/<filename>
     */
    var githubOwner: String = "StoneCollector"
    var githubRepo: String  = "honeypot-models"
    var releaseTag: String  = "v1.0-models"

    val baseUrl: String
        get() = "https://github.com/$githubOwner/$githubRepo/releases/download/$releaseTag"

    /**
     * Convenience method to configure GitHub repository details.
     */
    fun configureGitHubRelease(owner: String, repo: String, tag: String) {
        githubOwner = owner
        githubRepo = repo
        releaseTag = tag
    }

    // Model specifications with expected sizes
    const val MURIL_MODEL_NAME = "scam_classifier_int8.onnx"
    const val MURIL_VOCAB_NAME = "vocab.txt"
    const val USE_MODEL_NAME   = "universal_sentence_encoder_lite.tflite"

    // Expected byte sizes from training and export
    const val MURIL_MODEL_SIZE = 238_733_951L // ~227.67 MB
    const val MURIL_VOCAB_SIZE = 3_162_999L    // ~3.02 MB
    const val USE_MODEL_SIZE   = 6_120_274L    // ~5.84 MB

    data class ModelSpec(
        val fileName: String,
        val expectedSizeBytes: Long,
        val displayName: String
    ) {
        fun getDownloadUrl(base: String): String = "$base/$fileName"
    }

    val REQUIRED_MODELS = listOf(
        ModelSpec(MURIL_MODEL_NAME, MURIL_MODEL_SIZE, "Detection Engine"),
        ModelSpec(MURIL_VOCAB_NAME, MURIL_VOCAB_SIZE, "Language Data"),
        ModelSpec(USE_MODEL_NAME,   USE_MODEL_SIZE,   "Semantic Index")
    )

    sealed class DownloadProgress {
        object Idle : DownloadProgress()
        data class Downloading(
            val currentFileName: String,
            val currentFileIndex: Int,
            val totalFiles: Int,
            val fileProgressPercent: Int,
            val totalDownloadedMB: Float,
            val totalSizeMB: Float
        ) : DownloadProgress()
        object Completed : DownloadProgress()
        data class Failed(val error: String) : DownloadProgress()
    }

    private val _downloadStatus = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val downloadStatus: StateFlow<DownloadProgress> = _downloadStatus.asStateFlow()

    fun isDownloading(): Boolean =
        _downloadStatus.value is DownloadProgress.Downloading || currentCall != null

    /**
     * Returns the target directory for downloaded models: context.filesDir/models/
     */
    fun getModelsDir(context: Context): File {
        return File(context.filesDir, MODELS_DIR_NAME).apply { mkdirs() }
    }

    /**
     * Returns the local file for a specific model name.
     */
    fun getModelFile(context: Context, fileName: String): File {
        return File(getModelsDir(context), fileName)
    }

    /**
     * Checks if all required models are present on internal disk and have valid sizes.
     * Also falls back to assets if bundled during development.
     */
    fun areModelsReady(context: Context): Boolean {
        return REQUIRED_MODELS.all { isModelAvailable(context, it) }
    }

    /**
     * Checks if a single model is available on disk or in APK assets.
     */
    fun isModelAvailable(context: Context, spec: ModelSpec): Boolean {
        val file = getModelFile(context, spec.fileName)
        if (file.exists() && file.length() > 0) {
            return true
        }

        // Secondary check: look in legacy /muril/ folder or assets
        val legacyFile = File(File(context.filesDir, "muril"), spec.fileName)
        if (legacyFile.exists() && legacyFile.length() > 0) {
            return true
        }

        // Tertiary check: check APK assets
        return isAssetAvailable(context, spec.fileName) ||
               isAssetAvailable(context, "muril/${spec.fileName}")
    }

    private fun isAssetAvailable(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the working file path for a model (checking filesDir/models, filesDir/muril, or assets).
     */
    fun resolveModelFile(context: Context, fileName: String): File? {
        val mainFile = getModelFile(context, fileName)
        if (mainFile.exists() && mainFile.length() > 0) return mainFile

        val legacyFile = File(File(context.filesDir, "muril"), fileName)
        if (legacyFile.exists() && legacyFile.length() > 0) return legacyFile

        return null
    }

    /**
     * Returns total disk space used by downloaded models in MB.
     */
    fun getDownloadedModelsSizeMB(context: Context): Float {
        val dir = getModelsDir(context)
        if (!dir.exists()) return 0f
        val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        return bytes / (1024f * 1024f)
    }

    /**
     * Deletes downloaded models from internal storage (e.g. to test re-downloading).
     */
    fun deleteDownloadedModels(context: Context): Boolean {
        var allDeleted = true
        REQUIRED_MODELS.forEach { spec ->
            val file = getModelFile(context, spec.fileName)
            if (file.exists()) {
                val ok = file.delete()
                if (!ok) allDeleted = false
            }
        }
        return allDeleted
    }

    @Volatile
    private var currentCall: okhttp3.Call? = null

    /**
     * Cancels any active network download immediately and aborts the socket connection.
     */
    fun cancelDownload() {
        try {
            currentCall?.cancel()
        } catch (_: Exception) {}
        currentCall = null
        _downloadStatus.value = DownloadProgress.Idle
    }

    /**
     * Streams downloads of all missing models and emits real-time progress.
     */
    fun downloadMissingModels(context: Context): Flow<DownloadProgress> = flow {
        val missing = REQUIRED_MODELS.filter { spec ->
            val file = getModelFile(context, spec.fileName)
            !file.exists() || (spec.expectedSizeBytes > 0 && file.length() != spec.expectedSizeBytes)
        }

        if (missing.isEmpty()) {
            _downloadStatus.value = DownloadProgress.Completed
            emit(DownloadProgress.Completed)
            return@flow
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val totalBytesToDownload = missing.sumOf { it.expectedSizeBytes }
        var totalBytesDownloadedCumulative = 0L

        missing.forEachIndexed { index, spec ->
            val targetFile = getModelFile(context, spec.fileName)
            val tempFile = File(targetFile.parentFile, "${spec.fileName}.tmp")
            val url = spec.getDownloadUrl(baseUrl)

            Log.d(TAG, "Starting download: ${spec.fileName} from $url")

            // Immediately emit 0% progress so UI switches out of 'Connecting...' without waiting for first socket byte
            val initialProgress = DownloadProgress.Downloading(
                currentFileName = spec.displayName,
                currentFileIndex = index + 1,
                totalFiles = missing.size,
                fileProgressPercent = 0,
                totalDownloadedMB = totalBytesDownloadedCumulative / (1024f * 1024f),
                totalSizeMB = totalBytesToDownload / (1024f * 1024f)
            )
            _downloadStatus.value = initialProgress
            emit(initialProgress)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Honeypot-Android-App/1.0")
                .build()

            val call = client.newCall(request)
            currentCall = call

            try {
                call.execute().use { response ->
                    Log.d(TAG, "Response ${response.code} received for ${spec.fileName}")
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} (${response.message}) downloading ${spec.fileName}")
                    }

                    val body = response.body ?: throw IllegalStateException("Empty response body for ${spec.fileName}")
                    val contentLength = if (body.contentLength() > 0) body.contentLength() else spec.expectedSizeBytes

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var currentFileBytes = 0L
                            var lastEmittedTime = 0L
                            var lastEmittedPercent = -1

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                currentFileBytes += bytesRead
                                totalBytesDownloadedCumulative += bytesRead

                                val percent = ((currentFileBytes.toFloat() / contentLength) * 100).toInt().coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (percent != lastEmittedPercent || now - lastEmittedTime >= 200) {
                                    lastEmittedPercent = percent
                                    lastEmittedTime = now
                                    val progress = DownloadProgress.Downloading(
                                        currentFileName = spec.displayName,
                                        currentFileIndex = index + 1,
                                        totalFiles = missing.size,
                                        fileProgressPercent = percent,
                                        totalDownloadedMB = totalBytesDownloadedCumulative / (1024f * 1024f),
                                        totalSizeMB = totalBytesToDownload / (1024f * 1024f)
                                    )
                                    _downloadStatus.value = progress
                                    emit(progress)
                                }
                            }
                            output.flush()
                        }
                    }

                    // Rename temp to target atomically
                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        throw IllegalStateException("Failed to rename ${tempFile.name} to ${targetFile.name}")
                    }

                    Log.d(TAG, "Completed download: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                }
            } catch (e: Exception) {
                tempFile.delete()
                currentCall = null
                if (e is kotlinx.coroutines.CancellationException ||
                    (e is java.io.IOException && e.message?.contains("Canceled", ignoreCase = true) == true)) {
                    Log.d(TAG, "Download cancelled for ${spec.fileName}")
                    _downloadStatus.value = DownloadProgress.Idle
                    return@flow
                }
                Log.e(TAG, "Error downloading ${spec.fileName}", e)
                val failed = DownloadProgress.Failed(e.message ?: "Failed downloading ${spec.displayName}")
                _downloadStatus.value = failed
                emit(failed)
                return@flow
            } finally {
                currentCall = null
            }
        }

        _downloadStatus.value = DownloadProgress.Completed
        emit(DownloadProgress.Completed)
    }.flowOn(Dispatchers.IO)
}

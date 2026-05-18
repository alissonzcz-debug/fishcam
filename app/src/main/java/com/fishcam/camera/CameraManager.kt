package com.fishcam.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fishcam.utils.AppPreferences
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    companion object {
        private const val TAG = "CameraManager"
        private const val CHUNK_DURATION_MS = 3_000L  // 3-second chunks
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private lateinit var videoBuffer: VideoBuffer
    private var isCapturing = false       // chunk loop running
    private var isSavingFinal = false     // currently saving triggered clip
    private var finalChunks: MutableList<File> = mutableListOf()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Callbacks
    var onStatusChanged: ((CameraStatus) -> Unit)? = null
    var onVideoSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    enum class CameraStatus {
        IDLE, BUFFERING, SAVING, STOPPED
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    fun initialize() {
        val bufferSec = AppPreferences.getBufferSeconds(context)
        videoBuffer = VideoBuffer(context.cacheDir, bufferSec)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("MissingPermission")
    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val facing = if (AppPreferences.getCameraFacing(context) == "front")
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val qualitySelector = QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setExecutor(executor)
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            Log.d(TAG, "Camera bound, facing=$facing")
            startChunkLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            onError?.invoke("Erro ao iniciar câmera: ${e.message}")
        }
    }

    // ── Chunk Loop ───────────────────────────────────────────────────────────

    private fun startChunkLoop() {
        isCapturing = true
        onStatusChanged?.invoke(CameraStatus.BUFFERING)
        recordNextChunk()
    }

    @SuppressLint("MissingPermission")
    private fun recordNextChunk() {
        if (!isCapturing) return
        val vc = videoCapture ?: return

        val chunkFile = videoBuffer.nextChunkFile()
        val outputOptions = FileOutputOptions.Builder(chunkFile).build()

        activeRecording = vc.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            if (isSavingFinal) {
                                // This chunk is part of the triggered recording
                                finalChunks.add(chunkFile)
                            } else {
                                // Normal buffering — add to circular buffer
                                videoBuffer.addChunk(chunkFile)
                            }
                        } else {
                            Log.e(TAG, "Chunk error: ${event.error}")
                            chunkFile.delete()
                        }
                        // Start next chunk unless stopping
                        if (isCapturing) recordNextChunk()
                    }
                    else -> {}
                }
            }

        // Stop this chunk after CHUNK_DURATION_MS
        coroutineScope.launch {
            delay(CHUNK_DURATION_MS)
            if (isCapturing) {
                activeRecording?.stop()
            }
        }
    }

    // ── Trigger: START saving ─────────────────────────────────────────────────

    fun triggerSaveStart() {
        if (isSavingFinal) return
        Log.d(TAG, "TRIGGER: Start saving final clip")
        isSavingFinal = true
        finalChunks.clear()
        onStatusChanged?.invoke(CameraStatus.SAVING)
    }

    // ── Trigger: STOP saving and finalize file ────────────────────────────────

    fun triggerSaveStop() {
        if (!isSavingFinal) return
        Log.d(TAG, "TRIGGER: Stop saving — finalizing clip")
        isSavingFinal = false

        coroutineScope.launch(Dispatchers.IO) {
            // Wait a tiny bit for last chunk to finalize
            delay(500)

            val savedFile = buildOutputFile()
            val bufferedChunks = videoBuffer.getBufferedChunks()

            val success = videoBuffer.mergeChunksInto(savedFile, finalChunks)
            finalChunks.clear()

            withContext(Dispatchers.Main) {
                if (success) {
                    // Copy to MediaStore (gallery)
                    copyToMediaStore(savedFile)
                    onVideoSaved?.invoke(savedFile)
                    Log.d(TAG, "Video saved: ${savedFile.absolutePath}")
                } else {
                    onError?.invoke("Erro ao salvar vídeo")
                }
                onStatusChanged?.invoke(CameraStatus.BUFFERING)
            }
        }
    }

    // ── Output File ───────────────────────────────────────────────────────────

    private fun buildOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val folder = AppPreferences.getSaveFolder(context)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            folder
        ).also { it.mkdirs() }
        return File(dir, "FishCam_$timestamp.mp4")
    }

    private fun copyToMediaStore(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/${AppPreferences.getSaveFolder(context)}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            Log.d(TAG, "Added to MediaStore: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore insert failed", e)
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        isCapturing = false
        isSavingFinal = false
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        coroutineScope.cancel()
        executor.shutdown()
        videoBuffer.clear()
        onStatusChanged?.invoke(CameraStatus.STOPPED)
    }

    fun isCurrentlySaving() = isSavingFinal
    fun isBuffering() = isCapturing && !isSavingFinal
}

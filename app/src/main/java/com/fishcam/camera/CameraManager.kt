package com.fishcam.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var state = State.IDLE
    private var triggerTime = 0L
    private var recordingStartTime = 0L

    enum class State { IDLE, BUFFERING, SAVING }

    var onStatusChanged: ((String) -> Unit)? = null
    var onVideoSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun initialize() {
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
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val cameraSelector = CameraSelector.Builder().requireLensFacing(facing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val qualitySelector = QualitySelector.from(Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        val recorder = Recorder.Builder()
            .setExecutor(executor)
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            startBuffering()
        } catch (e: Exception) {
            onError?.invoke("Erro ao iniciar camera: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBuffering() {
        val vc = videoCapture ?: return
        state = State.BUFFERING
        onStatusChanged?.invoke("Buffer ativo")
        val tmpFile = File(context.cacheDir, "buffer_tmp.mp4").also { it.delete() }
        val outputOptions = FileOutputOptions.Builder(tmpFile).build()
        activeRecording = vc.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingStartTime = System.currentTimeMillis()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (state == State.SAVING) {
                            coroutineScope.launch(Dispatchers.IO) {
                                processAndSave(tmpFile)
                            }
                        } else if (state == State.BUFFERING) {
                            startBuffering()
                        }
                    }
                    else -> {}
                }
            }
    }

    fun triggerSaveStart() {
        if (state != State.BUFFERING) return
        triggerTime = System.currentTimeMillis()
        state = State.SAVING
        onStatusChanged?.invoke("Fisgada! Gravando...")
    }

    fun triggerSaveStop() {
        if (state != State.SAVING) return
        onStatusChanged?.invoke("Salvando video...")
        activeRecording?.stop()
        activeRecording = null
    }

    private suspend fun processAndSave(tmpFile: File) {
        try {
            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                withContext(Dispatchers.Main) { onError?.invoke("Arquivo vazio, espere o buffer encher") }
                state = State.BUFFERING
                withContext(Dispatchers.Main) { startBuffering() }
                return
            }
            val savedFile = buildOutputFile()
            tmpFile.copyTo(savedFile, overwrite = true)
            addToMediaStore(savedFile)
            withContext(Dispatchers.Main) {
                onVideoSaved?.invoke(savedFile)
                onStatusChanged?.invoke("Buffer ativo")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError?.invoke("Erro ao salvar: ${e.message}") }
        } finally {
            state = State.BUFFERING
            withContext(Dispatchers.Main) { startBuffering() }
        }
    }

    private fun buildOutputFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val folder = AppPreferences.getSaveFolder(context)
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), folder
        ).also { it.mkdirs() }
        return File(dir, "FishCam_$ts.mp4")
    }

    private fun addToMediaStore(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/${AppPreferences.getSaveFolder(context)}")
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore error", e)
        }
    }

    fun isCurrentlySaving() = state == State.SAVING

    fun release() {
        state = State.IDLE
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        coroutineScope.cancel()
        executor.shutdown()
    }
}

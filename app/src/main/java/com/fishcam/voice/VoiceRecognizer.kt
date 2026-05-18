package com.fishcam.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.fishcam.utils.AppPreferences
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File

class VoiceRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_SECONDS = 0.2f
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks
    var onStartCommandDetected: (() -> Unit)? = null
    var onStopCommandDetected: (() -> Unit)? = null
    var onModelLoaded: (() -> Unit)? = null
    var onModelLoadError: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    // ── Model Loading ─────────────────────────────────────────────────────────

    fun loadModel() {
        coroutineScope.launch {
            try {
                Log.d(TAG, "Loading Vosk model...")
                StorageService.unpack(context, "model-pt", "model",
                    { m ->
                        model = m
                        Log.d(TAG, "Vosk model loaded successfully")
                        withContext(Dispatchers.Main) {
                            onModelLoaded?.invoke()
                        }
                    },
                    { e ->
                        Log.e(TAG, "Model load error: $e")
                        withContext(Dispatchers.Main) {
                            onModelLoadError?.invoke("Erro ao carregar modelo de voz: $e")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading model", e)
                withContext(Dispatchers.Main) {
                    onModelLoadError?.invoke(e.message ?: "Erro desconhecido")
                }
            }
        }
    }

    // ── Listening ─────────────────────────────────────────────────────────────

    fun startListening() {
        val m = model ?: run {
            Log.w(TAG, "Model not loaded yet")
            return
        }
        if (isListening) return

        recognizer = Recognizer(m, SAMPLE_RATE.toFloat())
        isListening = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        audioRecord?.startRecording()
        Log.d(TAG, "Voice recognition started")

        coroutineScope.launch {
            val buf = ByteArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS * 2).toInt())
            while (isListening) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (read > 0) {
                    processAudio(buf, read)
                }
            }
        }
    }

    private suspend fun processAudio(buf: ByteArray, read: Int) {
        val rec = recognizer ?: return
        val startCmd = AppPreferences.getStartCommand(context).lowercase()
        val stopCmd = AppPreferences.getStopCommand(context).lowercase()

        if (rec.acceptWaveForm(buf, read)) {
            val result = rec.result.lowercase()
            Log.d(TAG, "Full result: $result")

            withContext(Dispatchers.Main) {
                when {
                    result.contains(startCmd) -> {
                        Log.d(TAG, "START command detected!")
                        onStartCommandDetected?.invoke()
                    }
                    result.contains(stopCmd) -> {
                        Log.d(TAG, "STOP command detected!")
                        onStopCommandDetected?.invoke()
                    }
                }
            }
        } else {
            val partial = rec.partialResult.lowercase()
            if (partial.contains("partial") && partial.length > 20) {
                withContext(Dispatchers.Main) {
                    onPartialResult?.invoke(partial)
                }
            }
        }
    }

    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        Log.d(TAG, "Voice recognition stopped")
    }

    fun release() {
        stopListening()
        model?.close()
        model = null
        coroutineScope.cancel()
    }
}

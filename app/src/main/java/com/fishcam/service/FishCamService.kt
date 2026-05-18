package com.fishcam.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import com.fishcam.R
import com.fishcam.camera.CameraManager
import com.fishcam.ui.main.MainActivity
import com.fishcam.voice.VoiceRecognizer
import java.io.File

class FishCamService : LifecycleService() {

    companion object {
        private const val TAG = "FishCamService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "fishcam_channel"
        private const val CHANNEL_NAME = "FishCam - Gravação Ativa"

        const val ACTION_START = "com.fishcam.START"
        const val ACTION_STOP = "com.fishcam.STOP"
        const val ACTION_SAVE_START = "com.fishcam.SAVE_START"
        const val ACTION_SAVE_STOP = "com.fishcam.SAVE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FishCamService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FishCamService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    // Binder for Activity to get direct access
    inner class LocalBinder : Binder() {
        fun getService(): FishCamService = this@FishCamService
    }
    private val binder = LocalBinder()

    private var cameraManager: CameraManager? = null
    private var voiceRecognizer: VoiceRecognizer? = null

    // Callbacks to UI
    var onStatusChanged: ((String) -> Unit)? = null
    var onVideoSaved: ((File) -> Unit)? = null
    var onModelLoaded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var previewView: PreviewView? = null
    private var isSaving = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Buffer ativo — aguardando fisgada 🎣"))
                initializeVoice()
            }
            ACTION_STOP -> {
                shutDown()
                stopSelf()
            }
            ACTION_SAVE_START -> triggerSaveStart()
            ACTION_SAVE_STOP -> triggerSaveStop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    fun attachPreviewView(pv: PreviewView) {
        previewView = pv
        startCamera(pv)
    }

    private fun startCamera(pv: PreviewView) {
        cameraManager = CameraManager(this, this, pv).apply {
            onStatusChanged = { status ->
                val msg = when (status) {
                    CameraManager.CameraStatus.BUFFERING -> "Buffer ativo 🎣"
                    CameraManager.CameraStatus.SAVING    -> "Gravando captura! 🐟"
                    CameraManager.CameraStatus.IDLE      -> "Câmera pronta"
                    CameraManager.CameraStatus.STOPPED   -> "Câmera parada"
                }
                updateNotification(msg)
                this@FishCamService.onStatusChanged?.invoke(msg)
            }
            onVideoSaved = { file ->
                updateNotification("Vídeo salvo! ✅")
                this@FishCamService.onVideoSaved?.invoke(file)
            }
            onError = { err ->
                this@FishCamService.onError?.invoke(err)
            }
            initialize()
        }
    }

    // ── Voice ─────────────────────────────────────────────────────────────────

    private fun initializeVoice() {
        voiceRecognizer = VoiceRecognizer(this).apply {
            onModelLoaded = {
                Log.d(TAG, "Voice model ready, starting listener")
                startListening()
                this@FishCamService.onModelLoaded?.invoke()
            }
            onModelLoadError = { err ->
                onError?.invoke("Erro no reconhecimento de voz: $err")
            }
            onStartCommandDetected = {
                Log.d(TAG, "Voice START command → triggerSaveStart")
                triggerSaveStart()
            }
            onStopCommandDetected = {
                Log.d(TAG, "Voice STOP command → triggerSaveStop")
                triggerSaveStop()
            }
            loadModel()
        }
    }

    // ── Trigger ───────────────────────────────────────────────────────────────

    fun triggerSaveStart() {
        if (isSaving) return
        isSaving = true
        cameraManager?.triggerSaveStart()
        updateNotification("🐟 Fisgada detectada! Gravando...")
    }

    fun triggerSaveStop() {
        if (!isSaving) return
        isSaving = false
        cameraManager?.triggerSaveStop()
        updateNotification("Salvando vídeo...")
    }

    fun isCurrentlySaving() = isSaving

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a câmera e microfone ativos em segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FishCam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private fun shutDown() {
        voiceRecognizer?.release()
        cameraManager?.release()
        voiceRecognizer = null
        cameraManager = null
        isSaving = false
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
}

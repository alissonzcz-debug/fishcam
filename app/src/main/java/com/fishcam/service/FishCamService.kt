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
        private const val CHANNEL_NAME = "FishCam - Gravacao Ativa"

        const val ACTION_START = "com.fishcam.START"
        const val ACTION_STOP  = "com.fishcam.STOP"

        fun start(context: Context) {
            val intent = Intent(context, FishCamService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FishCamService::class.java).apply { action = ACTION_STOP })
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): FishCamService = this@FishCamService
    }
    private val binder = LocalBinder()

    private var cameraManager: CameraManager? = null
    private var voiceRecognizer: VoiceRecognizer? = null
    private var isSaving = false

    var onStatusChanged: ((String) -> Unit)? = null
    var onVideoSaved: ((File) -> Unit)? = null
    var onModelLoaded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Buffer ativo - aguardando fisgada"))
                initializeVoice()
            }
            ACTION_STOP -> {
                shutDown()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun attachPreviewView(pv: PreviewView) {
        cameraManager = CameraManager(this, this, pv).apply {
            onStatusChanged = { status ->
                updateNotification(status)
                this@FishCamService.onStatusChanged?.invoke(status)
            }
            onVideoSaved = { file ->
                isSaving = false
                updateNotification("Video salvo!")
                this@FishCamService.onVideoSaved?.invoke(file)
            }
            onError = { err ->
                isSaving = false
                this@FishCamService.onError?.invoke(err)
            }
            initialize()
        }
    }

    private fun initializeVoice() {
        voiceRecognizer = VoiceRecognizer(this).apply {
            onModelLoaded = {
                startListening()
                this@FishCamService.onModelLoaded?.invoke()
            }
            onModelLoadError = { err -> onError?.invoke(err) }
            onStartCommandDetected = { triggerSaveStart() }
            onStopCommandDetected  = { triggerSaveStop() }
            loadModel()
        }
    }

    fun triggerSaveStart() {
        if (isSaving) return
        isSaving = true
        cameraManager?.triggerSaveStart()
        updateNotification("Fisgada detectada! Gravando...")
    }

    fun triggerSaveStop() {
        if (!isSaving) return
        cameraManager?.triggerSaveStop()
        updateNotification("Salvando video...")
    }

    fun isCurrentlySaving() = isSaving

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FishCam")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

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
    }
}

package com.fishcam.ui.main

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fishcam.R
import com.fishcam.databinding.ActivityMainBinding
import com.fishcam.service.FishCamService
import com.fishcam.ui.settings.SettingsActivity
import com.fishcam.utils.AppPreferences
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fishCamService: FishCamService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as FishCamService.LocalBinder
            fishCamService = localBinder.getService().also { svc ->
                // Attach preview
                svc.attachPreviewView(binding.previewView)

                svc.onStatusChanged = { status ->
                    runOnUiThread { updateStatusUI(status) }
                }
                svc.onVideoSaved = { file ->
                    runOnUiThread { onVideoSaved(file) }
                }
                svc.onModelLoaded = {
                    runOnUiThread {
                        binding.tvVoiceStatus.text = "🎤 Voz pronta"
                        binding.pbLoading.visibility = View.GONE
                    }
                }
                svc.onError = { err ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            fishCamService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while fishing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        startAndBindService()
    }

    private fun setupUI() {
        val startCmd = AppPreferences.getStartCommand(this)
        val stopCmd  = AppPreferences.getStopCommand(this)

        binding.tvCommandHint.text = "\"$startCmd\" → inicia  |  \"$stopCmd\" → para"

        binding.btnManualStart.setOnClickListener {
            fishCamService?.triggerSaveStart()
        }

        binding.btnManualStop.setOnClickListener {
            fishCamService?.triggerSaveStop()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStatusUI("Carregando modelo de voz...")
    }

    private fun startAndBindService() {
        FishCamService.start(this)
        val intent = Intent(this, FishCamService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String) {
        binding.tvStatus.text = status

        val isSaving = status.contains("Gravando") || status.contains("Fisgada")
        binding.viewRecordingIndicator.visibility = if (isSaving) View.VISIBLE else View.INVISIBLE
        binding.btnManualStart.isEnabled = !isSaving
        binding.btnManualStop.isEnabled = isSaving
    }

    private fun onVideoSaved(file: File) {
        binding.tvLastSaved.text = "✅ Salvo: ${file.name}"
        Toast.makeText(this, "Vídeo salvo em Filmes/FishCam!", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Go to background — keep service running
        moveTaskToBack(true)
    }
}

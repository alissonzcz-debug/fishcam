package com.fishcam.ui.main

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
                svc.attachPreviewView(binding.previewView)
                svc.onStatusChanged = { status -> runOnUiThread { updateStatusUI(status) } }
                svc.onVideoSaved = { file -> runOnUiThread { onVideoSaved(file) } }
                svc.onModelLoaded = {
                    runOnUiThread {
                        binding.tvVoiceStatus.text = "Voz pronta"
                        binding.pbLoading.visibility = View.GONE
                    }
                }
                svc.onError = { err -> runOnUiThread {
                    Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                }}
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        startAndBindService()
    }

    private fun setupUI() {
        val startCmd = AppPreferences.getStartCommand(this)
        val stopCmd  = AppPreferences.getStopCommand(this)
        val triggerMode = AppPreferences.getTriggerMode(this)

        val hint = when (triggerMode) {
            "volume" -> "Vol+ inicia  |  Vol- para"
            "voice"  -> "\"$startCmd\" inicia  |  \"$stopCmd\" para"
            else     -> "Use os botoes abaixo"
        }
        binding.tvCommandHint.text = hint

        binding.btnManualStart.setOnClickListener { fishCamService?.triggerSaveStart() }
        binding.btnManualStop.setOnClickListener  { fishCamService?.triggerSaveStop() }
        binding.btnSettings.setOnClickListener    {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateStatusUI("Iniciando...")
    }

    // ── Botões de volume ─────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val triggerMode = AppPreferences.getTriggerMode(this)
        if (triggerMode == "volume" && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    fishCamService?.triggerSaveStart()
                    return true  // consome o evento (não muda volume)
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    fishCamService?.triggerSaveStop()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startAndBindService() {
        FishCamService.start(this)
        bindService(Intent(this, FishCamService::class.java),
            serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateStatusUI(status: String) {
        binding.tvStatus.text = status
        val isSaving = status.contains("Gravando") || status.contains("Fisgada")
        binding.viewRecordingIndicator.visibility = if (isSaving) View.VISIBLE else View.INVISIBLE
        binding.btnManualStart.isEnabled = !isSaving
        binding.btnManualStop.isEnabled  = isSaving
    }

    private fun onVideoSaved(file: File) {
        binding.tvLastSaved.text = "Salvo: ${file.name}"
        Toast.makeText(this, "Video salvo em Filmes/FishCam!", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        if (bound) unbindService(serviceConnection)
        super.onDestroy()
    }

    override fun onBackPressed() { moveTaskToBack(true) }
}

package com.fishcam.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fishcam.databinding.ActivitySetupBinding
import com.fishcam.ui.main.MainActivity
import com.fishcam.utils.AppPreferences

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            saveAndProceed()
        } else {
            Toast.makeText(this,
                "Permissões necessárias para o app funcionar.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip setup if already done
        if (AppPreferences.isSetupDone(this)) {
            goToMain()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Default values
        binding.etStartCommand.setText(AppPreferences.getStartCommand(this))
        binding.etStopCommand.setText(AppPreferences.getStopCommand(this))

        // Camera selection
        val isFront = AppPreferences.getCameraFacing(this) == "front"
        binding.rgCamera.check(
            if (isFront) binding.rbFront.id else binding.rbBack.id
        )

        // Buffer slider (15-30s)
        binding.sliderBuffer.value = AppPreferences.getBufferSeconds(this).toFloat()
        binding.tvBufferValue.text = "${AppPreferences.getBufferSeconds(this)}s"
        binding.sliderBuffer.addOnChangeListener { _, value, _ ->
            binding.tvBufferValue.text = "${value.toInt()}s"
        }

        // Start button
        binding.btnStart.setOnClickListener {
            if (validateInputs()) {
                checkPermissionsAndProceed()
            }
        }
    }

    private fun validateInputs(): Boolean {
        val start = binding.etStartCommand.text.toString().trim()
        val stop = binding.etStopCommand.text.toString().trim()

        if (start.isBlank()) {
            binding.tilStartCommand.error = "Digite o comando de início"
            return false
        }
        if (stop.isBlank()) {
            binding.tilStopCommand.error = "Digite o comando de parada"
            return false
        }
        if (start.equals(stop, ignoreCase = true)) {
            binding.tilStopCommand.error = "Comandos devem ser diferentes"
            return false
        }
        binding.tilStartCommand.error = null
        binding.tilStopCommand.error = null
        return true
    }

    private fun checkPermissionsAndProceed() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            saveAndProceed()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun saveAndProceed() {
        val start = binding.etStartCommand.text.toString().trim()
        val stop = binding.etStopCommand.text.toString().trim()
        val facing = if (binding.rbFront.isChecked) "front" else "back"
        val buffer = binding.sliderBuffer.value.toInt()

        AppPreferences.setStartCommand(this, start)
        AppPreferences.setStopCommand(this, stop)
        AppPreferences.setCameraFacing(this, facing)
        AppPreferences.setBufferSeconds(this, buffer)
        AppPreferences.markSetupDone(this)

        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

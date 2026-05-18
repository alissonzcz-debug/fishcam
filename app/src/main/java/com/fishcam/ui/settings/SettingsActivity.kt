package com.fishcam.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fishcam.databinding.ActivitySettingsBinding
import com.fishcam.utils.AppPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Configurações"
            setDisplayHomeAsUpEnabled(true)
        }

        loadCurrentSettings()
        setupSaveButton()
    }

    private fun loadCurrentSettings() {
        binding.etStartCommand.setText(AppPreferences.getStartCommand(this))
        binding.etStopCommand.setText(AppPreferences.getStopCommand(this))

        val isFront = AppPreferences.getCameraFacing(this) == "front"
        binding.rgCamera.check(
            if (isFront) binding.rbFront.id else binding.rbBack.id
        )

        binding.sliderBuffer.value = AppPreferences.getBufferSeconds(this).toFloat()
        binding.tvBufferValue.text = "${AppPreferences.getBufferSeconds(this)}s"
        binding.sliderBuffer.addOnChangeListener { _, value, _ ->
            binding.tvBufferValue.text = "${value.toInt()}s"
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val start = binding.etStartCommand.text.toString().trim()
            val stop  = binding.etStopCommand.text.toString().trim()

            if (start.isBlank() || stop.isBlank()) {
                Toast.makeText(this, "Preencha os dois comandos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (start.equals(stop, ignoreCase = true)) {
                Toast.makeText(this, "Comandos devem ser diferentes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AppPreferences.setStartCommand(this, start)
            AppPreferences.setStopCommand(this, stop)
            AppPreferences.setCameraFacing(this, if (binding.rbFront.isChecked) "front" else "back")
            AppPreferences.setBufferSeconds(this, binding.sliderBuffer.value.toInt())

            Toast.makeText(this, "✅ Configurações salvas!\nReinicie o app para aplicar.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

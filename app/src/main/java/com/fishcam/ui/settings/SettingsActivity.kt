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
        supportActionBar?.apply { title = "Configuracoes"; setDisplayHomeAsUpEnabled(true) }
        loadCurrentSettings()
        setupSaveButton()
    }

    private fun loadCurrentSettings() {
        binding.etStartCommand.setText(AppPreferences.getStartCommand(this))
        binding.etStopCommand.setText(AppPreferences.getStopCommand(this))

        val isFront = AppPreferences.getCameraFacing(this) == "front"
        binding.rgCamera.check(if (isFront) binding.rbFront.id else binding.rbBack.id)

        binding.sliderBuffer.value = AppPreferences.getBufferSeconds(this).toFloat()
        binding.tvBufferValue.text = "${AppPreferences.getBufferSeconds(this)}s"
        binding.sliderBuffer.addOnChangeListener { _, value, _ ->
            binding.tvBufferValue.text = "${value.toInt()}s"
        }

        when (AppPreferences.getTriggerMode(this)) {
            "volume"  -> binding.rgTrigger.check(binding.rbVolume.id)
            "voice"   -> binding.rgTrigger.check(binding.rbVoice.id)
            else      -> binding.rgTrigger.check(binding.rbButtons.id)
        }

        updateVoiceFieldsVisibility(AppPreferences.getTriggerMode(this))
        binding.rgTrigger.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbVolume.id  -> "volume"
                binding.rbVoice.id   -> "voice"
                else                 -> "buttons"
            }
            updateVoiceFieldsVisibility(mode)
        }
    }

    private fun updateVoiceFieldsVisibility(mode: String) {
        val visible = if (mode == "voice") android.view.View.VISIBLE else android.view.View.GONE
        binding.tilStartCommand.visibility = visible
        binding.tilStopCommand.visibility  = visible
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val mode = when (binding.rgTrigger.checkedRadioButtonId) {
                binding.rbVolume.id -> "volume"
                binding.rbVoice.id  -> "voice"
                else                -> "buttons"
            }

            if (mode == "voice") {
                val start = binding.etStartCommand.text.toString().trim()
                val stop  = binding.etStopCommand.text.toString().trim()
                if (start.isBlank() || stop.isBlank()) {
                    Toast.makeText(this, "Preencha os dois comandos de voz", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (start.equals(stop, ignoreCase = true)) {
                    Toast.makeText(this, "Comandos devem ser diferentes", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppPreferences.setStartCommand(this, start)
                AppPreferences.setStopCommand(this, stop)
            }

            AppPreferences.setCameraFacing(this, if (binding.rbFront.isChecked) "front" else "back")
            AppPreferences.setBufferSeconds(this, binding.sliderBuffer.value.toInt())
            AppPreferences.setTriggerMode(this, mode)

            Toast.makeText(this, "Configuracoes salvas! Reinicie o app.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

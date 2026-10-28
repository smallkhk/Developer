package com.remotedroid.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedroid.app.R
import com.remotedroid.app.capture.CaptureService
import com.remotedroid.app.databinding.ActivityMainBinding
import com.remotedroid.app.input.RemoteInputService

/**
 * Minimal setup screen: one Start button. The relay URL and token are baked in
 * (config.xml), Fast mode is on by default. Two one-time setup buttons below
 * handle Accessibility and battery exemption.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val relayUrl by lazy { getString(R.string.default_relay_url) }
    private val token by lazy { getString(R.string.default_token) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data, relayUrl, token, true)
            setRunning(true)
            binding.status.text = "Running — control it from your browser."
        } else {
            binding.status.text = "Screen-capture permission was declined."
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.startBtn.setOnClickListener {
            if (relayUrl.isBlank()) {
                binding.status.text = "No relay baked in. Rebuild with your relay URL."
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopBtn.setOnClickListener {
            CaptureService.stop(this)
            setRunning(false)
            binding.status.text = "Stopped."
        }

        binding.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.batteryBtn.setOnClickListener { requestIgnoreBattery() }
    }

    override fun onResume() {
        super.onResume()
        binding.accessibilityBtn.text =
            if (RemoteInputService.isRunning) "1. Remote input enabled ✓"
            else "1. Enable remote input (Accessibility)"
    }

    /** Ask the OS to stop battery-killing the app (esp. Samsung/Xiaomi). */
    private fun requestIgnoreBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.status.text = "Battery limits already disabled ✓"
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun setRunning(running: Boolean) {
        binding.startBtn.isEnabled = !running
        binding.stopBtn.isEnabled = running
    }
}

package com.remotedroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.remotedroid.app.capture.CaptureService
import com.remotedroid.app.databinding.ActivityMainBinding
import com.remotedroid.app.input.RemoteInputService

/**
 * The on-phone setup screen. You enter your relay URL and token, enable remote
 * input once, then tap Start — which is where Android shows the one screen-capture
 * prompt. After that you drive everything from the browser.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private val prefs by lazy { getSharedPreferences("remotedroid", Context.MODE_PRIVATE) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            CaptureService.start(
                this, result.resultCode, data,
                binding.relayUrl.text.toString().trim(),
                binding.token.text.toString()
            )
            setRunning(true)
            binding.status.text = "Session running. You can leave this screen."
        } else {
            binding.status.text = "Screen-capture permission was declined."
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored; capture works regardless, this just enables the notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.relayUrl.setText(prefs.getString("relay", ""))
        binding.token.setText(prefs.getString("token", ""))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.startBtn.setOnClickListener {
            val url = binding.relayUrl.text.toString().trim()
            if (url.isBlank()) {
                binding.status.text = "Enter your relay URL first."
                return@setOnClickListener
            }
            prefs.edit()
                .putString("relay", url)
                .putString("token", binding.token.text.toString())
                .apply()
            // This is the one capture prompt Android requires on the phone.
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopBtn.setOnClickListener {
            CaptureService.stop(this)
            setRunning(false)
            binding.status.text = "Session stopped."
        }
    }

    override fun onResume() {
        super.onResume()
        if (!RemoteInputService.isRunning) {
            binding.accessibilityBtn.text = "Enable remote input (Accessibility)"
        } else {
            binding.accessibilityBtn.text = "Remote input enabled ✓"
        }
    }

    private fun setRunning(running: Boolean) {
        binding.startBtn.isEnabled = !running
        binding.stopBtn.isEnabled = running
    }
}

package com.example.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.service.OverlayService

/**
 * Translucent helper activity used to request a fresh MediaProjection consent token
 * every time continuous screen watching starts (Android 14+ requirement).
 */
class ScreenCaptureActivity : ComponentActivity() {

    private var launched = false
    private var scanMode: String = OverlayService.SCAN_MODE_CONTINUOUS

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_SCAN_MODE, scanMode)
            }
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                serviceIntent.action = OverlayService.ACTION_WATCH_GRANTED
                serviceIntent.putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                serviceIntent.putExtra(OverlayService.EXTRA_RESULT_DATA, data)
            } else {
                serviceIntent.action = OverlayService.ACTION_WATCH_CANCELLED
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            finishNoAnim()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanMode = savedInstanceState?.getString(KEY_SCAN_MODE)
            ?: intent?.getStringExtra(OverlayService.EXTRA_SCAN_MODE)
            ?: OverlayService.SCAN_MODE_CONTINUOUS
        if (savedInstanceState != null) {
            launched = savedInstanceState.getBoolean(KEY_LAUNCHED, false)
        }
        if (!launched) {
            launched = true
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (e: Exception) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_WATCH_FAILED
                    putExtra(OverlayService.EXTRA_SCAN_MODE, scanMode)
                    putExtra(
                        OverlayService.EXTRA_ERROR_MESSAGE,
                        e.message ?: "Unable to launch screen capture prompt",
                    )
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                finishNoAnim()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
        outState.putString(KEY_SCAN_MODE, scanMode)
    }

    private fun finishNoAnim() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val KEY_LAUNCHED = "capture_launched"
        private const val KEY_SCAN_MODE = "scan_mode"
    }
}

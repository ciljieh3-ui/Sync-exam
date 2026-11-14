package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.OverlayPreferences
import com.example.service.OverlayService

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val prefs = OverlayPreferences(context)
        if (!prefs.isOverlayEnabled) {
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission missing after boot; skipping OverlayService start")
            return
        }

        try {
            val serviceIntent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart OverlayService after boot", e)
        }
    }

    companion object {
        private const val TAG = "PrismBootReceiver"
    }
}

package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class BoxBounds(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("x", x)
            .put("y", y)
            .put("w", w)
            .put("h", h)
            .toString()
    }
}

class OverlayPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()
        }

    var isWatchRunning: Boolean
        get() = prefs.getBoolean(KEY_WATCH_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WATCH_RUNNING, value).apply()
        }

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, UNSET_COORD)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, UNSET_COORD)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, UNSET_COORD)
        set(value) {
            prefs.edit().putInt(KEY_PANEL_X, value).apply()
        }

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, UNSET_COORD)
        set(value) {
            prefs.edit().putInt(KEY_PANEL_Y, value).apply()
        }

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0.01f, 0.30f)
        set(value) {
            prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(0.01f, 0.30f)).apply()
        }

    fun saveBubblePosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_BUBBLE_X, x)
            .putInt(KEY_BUBBLE_Y, y)
            .apply()
    }

    fun savePanelPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_PANEL_X, x)
            .putInt(KEY_PANEL_Y, y)
            .apply()
    }

    fun saveBoxBounds(bounds: BoxBounds) {
        prefs.edit()
            .putInt(KEY_BOX_X, bounds.x)
            .putInt(KEY_BOX_Y, bounds.y)
            .putInt(KEY_BOX_W, bounds.w)
            .putInt(KEY_BOX_H, bounds.h)
            .apply()
    }

    fun getBoxBoundsOrNull(): BoxBounds? {
        val x = prefs.getInt(KEY_BOX_X, UNSET_COORD)
        val y = prefs.getInt(KEY_BOX_Y, UNSET_COORD)
        val w = prefs.getInt(KEY_BOX_W, UNSET_COORD)
        val h = prefs.getInt(KEY_BOX_H, UNSET_COORD)
        return if (x != UNSET_COORD && y != UNSET_COORD && w > 0 && h > 0) {
            BoxBounds(x, y, w, h)
        } else {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "prism_overlay_prefs"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_WATCH_RUNNING = "watch_running"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
        private const val KEY_BOX_X = "box_x"
        private const val KEY_BOX_Y = "box_y"
        private const val KEY_BOX_W = "box_w"
        private const val KEY_BOX_H = "box_h"
        private const val KEY_SENSITIVITY = "sensitivity"
        const val DEFAULT_SENSITIVITY = 0.05f
        const val UNSET_COORD = Int.MIN_VALUE

        private val _serviceRunning = MutableStateFlow(false)
        val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

        private val _watchingActive = MutableStateFlow(false)
        val watchingActive: StateFlow<Boolean> = _watchingActive.asStateFlow()

        fun setServiceRunning(running: Boolean) {
            _serviceRunning.value = running
        }

        fun setWatchingActive(watching: Boolean) {
            _watchingActive.value = watching
        }
    }
}

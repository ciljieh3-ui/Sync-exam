package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BoxBounds
import com.example.data.OverlayPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Prism`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Prism", appName)
    }

    @Test
    fun `overlay preferences persist coordinates box bounds sensitivity and running flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = OverlayPreferences(context)

        assertFalse(prefs.isOverlayEnabled)
        assertFalse(prefs.isWatchRunning)
        assertNull(prefs.getBoxBoundsOrNull())
        assertEquals(OverlayPreferences.DEFAULT_SENSITIVITY, prefs.sensitivity, 0.0001f)

        val bounds = BoxBounds(x = 80, y = 240, w = 900, h = 620)
        prefs.isOverlayEnabled = true
        prefs.isWatchRunning = true
        prefs.saveBubblePosition(120, 340)
        prefs.savePanelPosition(40, 180)
        prefs.saveBoxBounds(bounds)
        prefs.sensitivity = 0.12f

        assertTrue(prefs.isOverlayEnabled)
        assertTrue(prefs.isWatchRunning)
        assertEquals(120, prefs.bubbleX)
        assertEquals(340, prefs.bubbleY)
        assertEquals(40, prefs.panelX)
        assertEquals(180, prefs.panelY)
        assertEquals(bounds, prefs.getBoxBoundsOrNull())
        assertEquals(0.12f, prefs.sensitivity, 0.0001f)
        assertEquals("{\"x\":80,\"y\":240,\"w\":900,\"h\":620}", bounds.toJsonString())
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BoxBounds
import com.example.data.OverlayPreferences
import com.example.service.OverlayService
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
    fun `bundled overlay html asset exists and defines expected bridge callbacks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val html = context.assets.open("overlay.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("window.AndroidBridge"))
        assertTrue(html.contains("window.onFrame"))
        assertTrue(html.contains("window.onBoxChanged"))
        assertTrue(html.contains("generativelanguage.googleapis.com"))
        assertTrue(html.contains("gemini-flash-latest"))
        assertTrue(html.contains("X-goog-api-key"))
    }

    @Test
    fun `configured api key ignores blank and placeholder values`() {
        assertEquals("", OverlayService.resolveConfiguredApiKey(null))
        assertEquals("", OverlayService.resolveConfiguredApiKey("   "))
        assertEquals("", OverlayService.resolveConfiguredApiKey("MY_GEMINI_API_KEY"))
        assertEquals("test-secret-value", OverlayService.resolveConfiguredApiKey("  test-secret-value  "))
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

package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.BoxBounds
import com.example.data.OverlayPreferences
import com.example.receiver.BootCompletedReceiver
import com.example.service.BoxTextRecognizer
import com.example.service.FrameChangeDetector
import com.example.service.PrismAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrismEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `main activity displays primary controls and stop clears boot persistence flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = OverlayPreferences(context)
        prefs.isOverlayEnabled = true

        composeRule.onNodeWithTag("enable_overlay_button").assertIsDisplayed()
        composeRule.onNodeWithTag("enable_accessibility_button").assertIsDisplayed()
        composeRule.onNodeWithTag("start_prism_button").assertIsDisplayed()
        composeRule.onNodeWithTag("stop_prism_button").assertIsDisplayed()

        composeRule.onNodeWithTag("stop_prism_button").performClick()
        composeRule.waitForIdle()

        assertFalse(prefs.isOverlayEnabled)
    }

    @Test
    fun `auto click preferences and google ai url builder work as expected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = OverlayPreferences(context)

        assertFalse(prefs.isAutoClickEnabled)
        prefs.isAutoClickEnabled = true
        assertTrue(prefs.isAutoClickEnabled)

        assertEquals(OverlayPreferences.DEFAULT_GOOGLE_SEARCH_HOME, prefs.lastFloatingUrl)
        val googleUrl = OverlayPreferences.buildGoogleSearchUrl("Ibu kota Indonesia?", aiMode = false)
        assertTrue(googleUrl.startsWith("https://www.google.com/search?q="))
        assertTrue(googleUrl.contains("Ibu%20kota%20Indonesia%3F"))

        val searchUrl = OverlayPreferences.buildGoogleAiSearchUrl("Ibu kota Indonesia?")
        assertTrue(searchUrl.startsWith("https://www.google.com/search?udm=50&q="))
        assertTrue(searchUrl.contains("Ibu%20kota%20Indonesia%3F"))
    }

    @Test
    fun `box text recognizer cleans whitespace and frame blank check distinguishes black vs quiz frame`() {
        val cleaned = BoxTextRecognizer.cleanRecognizedText("  Apa  ibu\n\n  kota   Indonesia?  ")
        assertEquals("Apa ibu kota Indonesia?", cleaned)

        val blankBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        blankBmp.eraseColor(Color.BLACK)
        assertTrue(FrameChangeDetector.isBitmapMostlyBlank(blankBmp))
        blankBmp.recycle()

        val quizBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        quizBmp.eraseColor(Color.parseColor("#111826"))
        assertFalse(FrameChangeDetector.isBitmapMostlyBlank(quizBmp))
        quizBmp.recycle()
    }

    @Test
    fun `accessibility service normalizes option prefixes and builds ranked candidate targets`() {
        assertEquals("jakarta selatan", PrismAccessibilityService.normalizeText("B)   Jakarta Selatan "))
        assertEquals("mitokondria", PrismAccessibilityService.normalizeText("A. Mitokondria"))

        val candidates = PrismAccessibilityService.buildCandidateTargets(
            answerText = "B) Jakarta",
            answerIndex = 1,
            options = listOf("Bandung", "Jakarta", "Surabaya"),
        )
        assertTrue(candidates.contains("jakarta"))
        assertEquals("jakarta", candidates.first())
    }

    @Test
    fun `boot receiver does not start service when overlay was disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = OverlayPreferences(context)
        prefs.isOverlayEnabled = false

        val receiver = BootCompletedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val appShadow = shadowOf(context as android.app.Application)
        assertNull(appShadow.nextStartedService)
    }

    @Test
    fun `frame change detector crops selection box ignores identical frames and emits on new question`() {
        val detector = FrameChangeDetector()
        val screenW = 1080
        val screenH = 1920
        val captureW = 1080
        val captureH = 1920
        val box = BoxBounds(x = 100, y = 300, w = 880, h = 600)

        // Frame 1: Initial blank quiz container inside box
        val frame1 = Bitmap.createBitmap(captureW, captureH, Bitmap.Config.ARGB_8888)
        val canvas1 = Canvas(frame1)
        canvas1.drawColor(Color.WHITE)

        val res1 = detector.evaluateFrame(
            fullBitmap = frame1,
            box = box,
            screenW = screenW,
            screenH = screenH,
            captureW = captureW,
            captureH = captureH,
            strokeInsetPx = 4,
            sensitivity = 0.05f,
        )
        assertTrue(res1.shouldEmit)
        assertNotNull(res1.base64Png)

        // Decode emitted Base64 PNG to confirm valid PNG image cropped to box dimensions
        val bytes1 = Base64.decode(res1.base64Png!!, Base64.NO_WRAP)
        val decoded1 = BitmapFactory.decodeByteArray(bytes1, 0, bytes1.size)
        assertNotNull(decoded1)
        assertEquals(872, decoded1.width) // 880 - 2*4 inset
        assertEquals(592, decoded1.height) // 600 - 2*4 inset
        decoded1.recycle()

        // Frame 1b: Identical frame -> should NOT re-trigger
        val res1b = detector.evaluateFrame(
            fullBitmap = frame1,
            box = box,
            screenW = screenW,
            screenH = screenH,
            captureW = captureW,
            captureH = captureH,
            strokeInsetPx = 4,
            sensitivity = 0.05f,
        )
        assertFalse(res1b.shouldEmit)
        assertEquals(0f, res1b.changeRatio, 0.0001f)
        assertNull(res1b.base64Png)

        // Frame 1c: Change pixels OUTSIDE the selection box only -> should NOT trigger
        val frameOutsideChange = frame1.copy(Bitmap.Config.ARGB_8888, true)
        val canvasOutside = Canvas(frameOutsideChange)
        val blackPaint = Paint().apply { color = Color.BLACK }
        canvasOutside.drawRect(0f, 0f, 1080f, 200f, blackPaint)
        val resOutside = detector.evaluateFrame(
            fullBitmap = frameOutsideChange,
            box = box,
            screenW = screenW,
            screenH = screenH,
            captureW = captureW,
            captureH = captureH,
            strokeInsetPx = 4,
            sensitivity = 0.05f,
        )
        assertFalse(resOutside.shouldEmit)
        assertEquals(0f, resOutside.changeRatio, 0.0001f)
        frameOutsideChange.recycle()

        // Frame 2: New quiz question block appears INSIDE the selection box -> MUST emit new PNG
        val frame2 = frame1.copy(Bitmap.Config.ARGB_8888, true)
        val canvas2 = Canvas(frame2)
        val darkPaint = Paint().apply { color = Color.parseColor("#111826") }
        canvas2.drawRect(180f, 380f, 900f, 820f, darkPaint)

        val res2 = detector.evaluateFrame(
            fullBitmap = frame2,
            box = box,
            screenW = screenW,
            screenH = screenH,
            captureW = captureW,
            captureH = captureH,
            strokeInsetPx = 4,
            sensitivity = 0.05f,
        )
        assertTrue(res2.shouldEmit)
        assertTrue(res2.changeRatio > 0.05f)
        assertNotNull(res2.base64Png)

        val bytes2 = Base64.decode(res2.base64Png!!, Base64.NO_WRAP)
        val decoded2 = BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size)
        assertNotNull(decoded2)
        assertEquals(872, decoded2.width)
        assertEquals(592, decoded2.height)

        decoded2.recycle()
        frame1.recycle()
        frame2.recycle()
        detector.reset()
    }
}

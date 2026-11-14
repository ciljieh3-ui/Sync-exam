package com.example.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.example.data.BoxBounds
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

data class FrameEvaluationResult(
    val shouldEmit: Boolean,
    val changeRatio: Float,
    val base64Png: String?,
)

/**
 * Reuses pre-allocated 64x36 grayscale buffers to detect meaningful visual changes
 * inside the selection box and only encodes a PNG when changeRatio > sensitivity.
 */
class FrameChangeDetector {

    private var smallBitmap: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private val smallPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstSmallRect = Rect(0, 0, DIFF_W, DIFF_H)
    private val smallPixels = IntArray(DIFF_PIXEL_COUNT)
    private val currGray = FloatArray(DIFF_PIXEL_COUNT)
    private val refGray = FloatArray(DIFF_PIXEL_COUNT)
    private var hasRefFrame = false

    fun evaluateFrame(
        fullBitmap: Bitmap,
        box: BoxBounds,
        screenW: Int,
        screenH: Int,
        captureW: Int,
        captureH: Int,
        strokeInsetPx: Int,
        sensitivity: Float,
    ): FrameEvaluationResult {
        if (smallBitmap == null || smallBitmap?.isRecycled == true) {
            smallBitmap = Bitmap.createBitmap(DIFF_W, DIFF_H, Bitmap.Config.ARGB_8888)
            smallCanvas = Canvas(smallBitmap!!)
        }

        val safeScreenW = screenW.coerceAtLeast(1)
        val safeScreenH = screenH.coerceAtLeast(1)
        val safeCaptureW = captureW.coerceAtLeast(1)
        val safeCaptureH = captureH.coerceAtLeast(1)

        val scaleX = safeCaptureW.toFloat() / safeScreenW.toFloat()
        val scaleY = safeCaptureH.toFloat() / safeScreenH.toFloat()
        val scaledInset = (strokeInsetPx * scaleX).roundToInt().coerceAtLeast(0)

        val cropLeft = (box.x * scaleX).roundToInt().plus(scaledInset)
            .coerceIn(0, (safeCaptureW - 2).coerceAtLeast(0))
        val cropTop = (box.y * scaleY).roundToInt().plus(scaledInset)
            .coerceIn(0, (safeCaptureH - 2).coerceAtLeast(0))
        val cropRight = ((box.x + box.w) * scaleX).roundToInt().minus(scaledInset)
            .coerceIn(cropLeft + 1, safeCaptureW)
        val cropBottom = ((box.y + box.h) * scaleY).roundToInt().minus(scaledInset)
            .coerceIn(cropTop + 1, safeCaptureH)
        val cropW = (cropRight - cropLeft).coerceAtLeast(1)
        val cropH = (cropBottom - cropTop).coerceAtLeast(1)

        srcRect.set(cropLeft, cropTop, cropRight, cropBottom)
        val sBmp = smallBitmap!!
        val sCanvas = smallCanvas!!
        sCanvas.drawBitmap(fullBitmap, srcRect, dstSmallRect, smallPaint)
        sBmp.getPixels(smallPixels, 0, DIFF_W, 0, 0, DIFF_W, DIFF_H)

        for (i in 0 until DIFF_PIXEL_COUNT) {
            val c = smallPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            currGray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val changeRatio: Float
        val shouldEmit: Boolean
        if (!hasRefFrame) {
            currGray.copyInto(refGray)
            hasRefFrame = true
            changeRatio = 1f
            shouldEmit = true
        } else {
            var changed = 0
            for (i in 0 until DIFF_PIXEL_COUNT) {
                if (abs(currGray[i] - refGray[i]) > PIXEL_DELTA_THRESHOLD) {
                    changed++
                }
            }
            changeRatio = changed.toFloat() / DIFF_PIXEL_COUNT.toFloat()
            if (changeRatio > sensitivity) {
                currGray.copyInto(refGray)
                shouldEmit = true
            } else {
                for (i in 0 until DIFF_PIXEL_COUNT) {
                    refGray[i] = refGray[i] * 0.9f + currGray[i] * 0.1f
                }
                shouldEmit = false
            }
        }

        val base64 = if (shouldEmit) {
            encodeCroppedPngBase64(fullBitmap, cropLeft, cropTop, cropW, cropH)
        } else {
            null
        }

        return FrameEvaluationResult(
            shouldEmit = shouldEmit,
            changeRatio = changeRatio,
            base64Png = base64,
        )
    }

    fun reset() {
        try {
            smallBitmap?.recycle()
        } catch (_: Exception) {
        }
        smallBitmap = null
        smallCanvas = null
        hasRefFrame = false
    }

    companion object {
        private const val MAX_WIDTH_PX = 1280
        private const val DIFF_W = 64
        private const val DIFF_H = 36
        private const val DIFF_PIXEL_COUNT = DIFF_W * DIFF_H
        private const val PIXEL_DELTA_THRESHOLD = 16f

        fun cropBoxBitmap(
            fullBmp: Bitmap,
            box: BoxBounds,
            screenW: Int,
            screenH: Int,
            captureW: Int,
            captureH: Int,
            strokeInsetPx: Int,
        ): Bitmap {
            val safeScreenW = screenW.coerceAtLeast(1)
            val safeScreenH = screenH.coerceAtLeast(1)
            val safeCaptureW = captureW.coerceAtLeast(1)
            val safeCaptureH = captureH.coerceAtLeast(1)

            val scaleX = safeCaptureW.toFloat() / safeScreenW.toFloat()
            val scaleY = safeCaptureH.toFloat() / safeScreenH.toFloat()
            val scaledInset = (strokeInsetPx * scaleX).roundToInt().coerceAtLeast(0)

            val cropLeft = (box.x * scaleX).roundToInt().plus(scaledInset)
                .coerceIn(0, (safeCaptureW - 2).coerceAtLeast(0))
            val cropTop = (box.y * scaleY).roundToInt().plus(scaledInset)
                .coerceIn(0, (safeCaptureH - 2).coerceAtLeast(0))
            val cropRight = ((box.x + box.w) * scaleX).roundToInt().minus(scaledInset)
                .coerceIn(cropLeft + 1, safeCaptureW)
            val cropBottom = ((box.y + box.h) * scaleY).roundToInt().minus(scaledInset)
                .coerceIn(cropTop + 1, safeCaptureH)
            val cropW = (cropRight - cropLeft).coerceAtLeast(1)
            val cropH = (cropBottom - cropTop).coerceAtLeast(1)

            val cropped = Bitmap.createBitmap(fullBmp, cropLeft, cropTop, cropW, cropH)
            return if (cropped.width > MAX_WIDTH_PX) {
                val ratio = MAX_WIDTH_PX.toFloat() / cropped.width.toFloat()
                val targetH = (cropped.height * ratio).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, MAX_WIDTH_PX, targetH, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            } else {
                cropped
            }
        }

        fun encodeBitmapToBase64(bitmap: Bitmap): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        fun isBitmapMostlyBlank(bitmap: Bitmap): Boolean {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return true
            val stepX = (w / 8).coerceAtLeast(1)
            val stepY = (h / 8).coerceAtLeast(1)
            var nonBlack = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = bitmap.getPixel(x, y)
                    val a = (p ushr 24) and 0xFF
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    if (a > 0 && (r > 6 || g > 6 || b > 6)) {
                        nonBlack++
                        if (nonBlack >= 3) return false
                    }
                    x += stepX
                }
                y += stepY
            }
            return true
        }

        fun cropBoxToBase64(
            fullBmp: Bitmap,
            box: BoxBounds,
            screenW: Int,
            screenH: Int,
            captureW: Int,
            captureH: Int,
            strokeInsetPx: Int,
        ): String {
            val exportBmp = cropBoxBitmap(
                fullBmp = fullBmp,
                box = box,
                screenW = screenW,
                screenH = screenH,
                captureW = captureW,
                captureH = captureH,
                strokeInsetPx = strokeInsetPx,
            )
            return encodeBitmapToBase64(exportBmp).also {
                exportBmp.recycle()
            }
        }

        private fun encodeCroppedPngBase64(
            fullBmp: Bitmap,
            cropLeft: Int,
            cropTop: Int,
            cropW: Int,
            cropH: Int,
        ): String {
            val cropped = Bitmap.createBitmap(fullBmp, cropLeft, cropTop, cropW, cropH)
            val exportBmp = if (cropped.width > MAX_WIDTH_PX) {
                val ratio = MAX_WIDTH_PX.toFloat() / cropped.width.toFloat()
                val targetH = (cropped.height * ratio).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(cropped, MAX_WIDTH_PX, targetH, true).also {
                    if (it !== cropped) cropped.recycle()
                }
            } else {
                cropped
            }

            return encodeBitmapToBase64(exportBmp).also {
                exportBmp.recycle()
            }
        }
    }
}

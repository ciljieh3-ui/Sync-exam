package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.example.R
import com.example.data.BoxBounds
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Transparent overlay window showing a 2dp mint (#00E5A0) selection rectangle
 * with four 24dp circular corner handles (3dp white border, subtle drop shadow).
 * Supports dragging from anywhere inside and resizing from any corner handle.
 */
@SuppressLint("ViewConstructor")
class SelectionBoxView(
    context: Context,
    private val getScreenSize: () -> Pair<Int, Int>,
    private val onBoundsChanging: (boxX: Int, boxY: Int, boxW: Int, boxH: Int) -> Unit,
    private val onBoundsCommitted: (BoxBounds) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val handleRadiusPx = 12f * density // 24dp diameter
    val handlePaddingPx: Int = (16f * density).roundToInt() // 12dp radius + 4dp shadow margin
    private val minBoxWidthPx: Int = (120f * density).roundToInt()
    private val minBoxHeightPx: Int = (80f * density).roundToInt()
    private val handleHitRadiusPx = 30f * density

    var isLocked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        setShadowLayer(4f * density, 0f, 1.5f * density, Color.argb(140, 0, 0, 0))
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.FILL
        setShadowLayer(5f * density, 0f, 2f * density, Color.argb(170, 0, 0, 0))
    }

    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val rect = RectF()

    private enum class DragMode {
        NONE,
        MOVE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }

    private var dragMode = DragMode.NONE
    private var startRawX = 0f
    private var startRawY = 0f
    private var startBoxX = 0
    private var startBoxY = 0
    private var startBoxW = 0
    private var startBoxH = 0

    // Current inner box bounds in screen coordinates, kept in sync by OverlayService.
    var currentBoxX = 0
    var currentBoxY = 0
    var currentBoxW = 0
    var currentBoxH = 0

    init {
        contentDescription = context.getString(R.string.selection_box_desc)
        // Software layer ensures Paint.setShadowLayer renders crisply across API 26-34.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = handlePaddingPx.toFloat()
        val left = pad
        val top = pad
        val right = (width - pad).coerceAtLeast(left + 1f)
        val bottom = (height - pad).coerceAtLeast(top + 1f)

        rect.set(left, top, right, bottom)
        canvas.drawRect(rect, strokePaint)

        if (!isLocked) {
            drawHandle(canvas, left, top)
            drawHandle(canvas, right, top)
            drawHandle(canvas, left, bottom)
            drawHandle(canvas, right, bottom)
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadiusPx, handleFillPaint)
        canvas.drawCircle(cx, cy, handleRadiusPx - handleBorderPaint.strokeWidth / 2f, handleBorderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pad = handlePaddingPx.toFloat()
                val left = pad
                val top = pad
                val right = (width - pad).toFloat()
                val bottom = (height - pad).toFloat()
                val lx = event.x
                val ly = event.y

                dragMode = when {
                    hypot(lx - left, ly - top) <= handleHitRadiusPx -> DragMode.TOP_LEFT
                    hypot(lx - right, ly - top) <= handleHitRadiusPx -> DragMode.TOP_RIGHT
                    hypot(lx - left, ly - bottom) <= handleHitRadiusPx -> DragMode.BOTTOM_LEFT
                    hypot(lx - right, ly - bottom) <= handleHitRadiusPx -> DragMode.BOTTOM_RIGHT
                    lx in left..right && ly in top..bottom -> DragMode.MOVE
                    else -> DragMode.NONE
                }

                if (dragMode == DragMode.NONE) return false

                startRawX = event.rawX
                startRawY = event.rawY
                startBoxX = currentBoxX
                startBoxY = currentBoxY
                startBoxW = currentBoxW
                startBoxH = currentBoxH
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = (event.rawX - startRawX).roundToInt()
                val dy = (event.rawY - startRawY).roundToInt()
                val (screenW, screenH) = getScreenSize()

                var nx = startBoxX
                var ny = startBoxY
                var nw = startBoxW
                var nh = startBoxH

                when (dragMode) {
                    DragMode.MOVE -> {
                        nx = (startBoxX + dx).coerceIn(0, (screenW - startBoxW).coerceAtLeast(0))
                        ny = (startBoxY + dy).coerceIn(0, (screenH - startBoxH).coerceAtLeast(0))
                    }

                    DragMode.TOP_LEFT -> {
                        val rightEdge = startBoxX + startBoxW
                        val bottomEdge = startBoxY + startBoxH
                        nx = (startBoxX + dx).coerceIn(0, (rightEdge - minBoxWidthPx).coerceAtLeast(0))
                        ny = (startBoxY + dy).coerceIn(0, (bottomEdge - minBoxHeightPx).coerceAtLeast(0))
                        nw = rightEdge - nx
                        nh = bottomEdge - ny
                    }

                    DragMode.TOP_RIGHT -> {
                        val bottomEdge = startBoxY + startBoxH
                        val maxW = (screenW - startBoxX).coerceAtLeast(minBoxWidthPx)
                        nw = (startBoxW + dx).coerceIn(minBoxWidthPx, maxW)
                        ny = (startBoxY + dy).coerceIn(0, (bottomEdge - minBoxHeightPx).coerceAtLeast(0))
                        nh = bottomEdge - ny
                    }

                    DragMode.BOTTOM_LEFT -> {
                        val rightEdge = startBoxX + startBoxW
                        val maxH = (screenH - startBoxY).coerceAtLeast(minBoxHeightPx)
                        nx = (startBoxX + dx).coerceIn(0, (rightEdge - minBoxWidthPx).coerceAtLeast(0))
                        nw = rightEdge - nx
                        nh = (startBoxH + dy).coerceIn(minBoxHeightPx, maxH)
                    }

                    DragMode.BOTTOM_RIGHT -> {
                        val maxW = (screenW - startBoxX).coerceAtLeast(minBoxWidthPx)
                        val maxH = (screenH - startBoxY).coerceAtLeast(minBoxHeightPx)
                        nw = (startBoxW + dx).coerceIn(minBoxWidthPx, maxW)
                        nh = (startBoxH + dy).coerceIn(minBoxHeightPx, maxH)
                    }

                    DragMode.NONE -> return false
                }

                currentBoxX = nx
                currentBoxY = ny
                currentBoxW = nw
                currentBoxH = nh
                onBoundsChanging(nx, ny, nw, nh)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    dragMode = DragMode.NONE
                    onBoundsCommitted(BoxBounds(currentBoxX, currentBoxY, currentBoxW, currentBoxH))
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

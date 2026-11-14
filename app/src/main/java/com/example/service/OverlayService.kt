package com.example.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.capture.ScreenCaptureActivity
import com.example.data.BoxBounds
import com.example.data.OverlayPreferences
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: OverlayPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Three floating windows
    private var bubbleView: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelContainer: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var webView: WebView? = null

    private var selectionBoxView: SelectionBoxView? = null
    private var selectionBoxParams: WindowManager.LayoutParams? = null

    private var isPanelVisible = false
    private var isSelectionVisible = false

    // Continuous capture state
    private val isWatching = AtomicBoolean(false)
    private var isScreenInteractive = true
    @Volatile private var sensitivityThreshold: Float = OverlayPreferences.DEFAULT_SENSITIVITY

    private var activeProjection: MediaProjection? = null
    private var activeVirtualDisplay: VirtualDisplay? = null
    private var activeImageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null

    private var captureScreenW = 0
    private var captureScreenH = 0
    private var captureW = 0
    private var captureH = 0

    // Reusable buffers for zero-allocation steady-state change detection
    private var reusableFullBitmap: Bitmap? = null
    private var smallBitmap: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private val smallPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstSmallRect = Rect(0, 0, DIFF_W, DIFF_H)
    private val smallPixels = IntArray(DIFF_PIXEL_COUNT)
    private val currGray = FloatArray(DIFF_PIXEL_COUNT)
    private val refGray = FloatArray(DIFF_PIXEL_COUNT)
    private var hasRefFrame = false
    private var lastChangeTimeMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    captureHandler?.removeCallbacks(frameTickRunnable)
                }

                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    if (isWatching.get() && activeProjection != null) {
                        scheduleNextFrameTick(FPS_3_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private val frameTickRunnable = Runnable {
        processCaptureTick()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = OverlayPreferences(this)
        sensitivityThreshold = prefs.sensitivity
        prefs.isWatchRunning = false
        OverlayPreferences.setWatchingActive(false)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenInteractive = powerManager.isInteractive

        captureThread = HandlerThread("PrismWatchThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, screenFilter)
        }

        createNotificationChannel()
        startForegroundCompat(watching = false)
        OverlayPreferences.setServiceRunning(true)

        createSelectionBoxWindow()
        createBubbleWindow()
        createPanelWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCH_GRANTED -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = getParcelableIntentCompat(intent, EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    beginContinuousCapture(resultCode, resultData)
                } else {
                    stopWatchingInternal(notifyJs = true)
                }
            }

            ACTION_WATCH_CANCELLED, ACTION_WATCH_FAILED -> {
                stopWatchingInternal(notifyJs = true)
            }

            ACTION_STOP_WATCHING -> {
                stopWatchingInternal(notifyJs = true)
            }

            else -> {
                startForegroundCompat(watching = isWatching.get())
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clampViewsToScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchingInternal(notifyJs = false)
        OverlayPreferences.setServiceRunning(false)
        prefs.isWatchRunning = false

        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }

        mainHandler.removeCallbacksAndMessages(null)

        selectionBoxView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        selectionBoxView = null

        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null

        panelContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelContainer = null

        webView?.let { wv ->
            try {
                wv.removeJavascriptInterface(JS_BRIDGE_NAME)
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
        webView = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(watching: Boolean): Notification {
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopWatchPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply {
                action = ACTION_STOP_WATCHING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prism_notif)
            .setContentTitle(getString(R.string.notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (watching) {
            builder
                .setContentText(getString(R.string.notification_text_watching))
                .setContentIntent(stopWatchPendingIntent)
                .addAction(
                    0,
                    getString(R.string.notification_action_stop),
                    stopWatchPendingIntent,
                )
        } else {
            builder
                .setContentText(getString(R.string.notification_text_idle))
                .setContentIntent(openAppPendingIntent)
        }

        return builder.build()
    }

    private fun startForegroundCompat(watching: Boolean) {
        val notification = buildNotification(watching)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (watching) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (watching) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Window 1: Floating Bubble ----
    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleWindow() {
        val sizePx = dpToPx(56)
        val (sw, sh) = getScreenSizePx()
        val defaultX = (sw - sizePx - dpToPx(16)).coerceAtLeast(0)
        val defaultY = (sh * 0.35f).roundToInt().coerceAtLeast(0)

        val initialX = prefs.bubbleX.let {
            if (it != OverlayPreferences.UNSET_COORD) it.coerceIn(0, (sw - sizePx).coerceAtLeast(0)) else defaultX
        }
        val initialY = prefs.bubbleY.let {
            if (it != OverlayPreferences.UNSET_COORD) it.coerceIn(0, (sh - sizePx).coerceAtLeast(0)) else defaultY
        }

        val gradientBorder = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#00E5A0"), Color.parseColor("#B47EFF")),
        ).apply {
            shape = GradientDrawable.OVAL
        }
        val innerFill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#EA0A0E17"))
        }
        val borderInset = dpToPx(2.5f)
        val bubbleBackground = LayerDrawable(arrayOf(gradientBorder, innerFill)).apply {
            setLayerInset(1, borderInset, borderInset, borderInset, borderInset)
        }

        val container = FrameLayout(this).apply {
            contentDescription = getString(R.string.bubble_desc)
            elevation = dpToPx(8).toFloat()
            background = bubbleBackground
        }

        val glyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_prism_notif)
            setColorFilter(Color.parseColor("#00E5A0"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
        }
        container.addView(
            glyph,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        var longPressed = false

        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                container.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).withEndAction {
                    container.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }.start()
                hidePanel()
                showSelectionBoxInternal()
            }
        }

        container.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).roundToInt()
                    val dy = (event.rawY - touchStartY).roundToInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        val (curW, curH) = getScreenSizePx()
                        params.x = (startX + dx).coerceIn(0, (curW - sizePx).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (curH - sizePx).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (moved) {
                        prefs.saveBubblePosition(params.x, params.y)
                    } else if (!longPressed) {
                        view.performClick()
                        if (isPanelVisible) hidePanel() else showPanel()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    true
                }

                else -> false
            }
        }

        bubbleView = container
        bubbleParams = params
        windowManager.addView(container, params)
    }

    // ---- Window 2: Control Panel (WebView) ----
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createPanelWindow() {
        val (sw, sh) = getScreenSizePx()
        val desiredWidth = dpToPx(340).coerceAtMost((sw - dpToPx(16)).coerceAtLeast(dpToPx(240)))
        val desiredHeight = dpToPx(520).coerceAtMost((sh - dpToPx(32)).coerceAtLeast(dpToPx(300)))
        val cornerRadiusPx = dpToPx(18).toFloat()

        val defaultX = ((sw - desiredWidth) / 2).coerceAtLeast(0)
        val defaultY = ((sh - desiredHeight) / 4).coerceAtLeast(0)

        val initialX = prefs.panelX.let {
            if (it != OverlayPreferences.UNSET_COORD) it.coerceIn(0, (sw - desiredWidth).coerceAtLeast(0)) else defaultX
        }
        val initialY = prefs.panelY.let {
            if (it != OverlayPreferences.UNSET_COORD) it.coerceIn(0, (sh - desiredHeight).coerceAtLeast(0)) else defaultY
        }

        val root = object : LinearLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (isPanelVisible) {
                        hidePanel()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpToPx(12).toFloat()
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusPx
                setColor(Color.parseColor("#0A0E17"))
                setStroke(dpToPx(1), Color.parseColor("#25314A"))
            }
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            desiredWidth,
            desiredHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val dragStripHeight = dpToPx(44)
        val dragStrip = FrameLayout(this).apply {
            contentDescription = getString(R.string.drag_handle_desc)
            setBackgroundColor(Color.parseColor("#111826"))
        }

        val dragPill = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(3).toFloat()
                setColor(Color.parseColor("#354465"))
            }
        }
        dragStrip.addView(
            dragPill,
            FrameLayout.LayoutParams(dpToPx(44), dpToPx(5)).apply {
                gravity = Gravity.CENTER
            },
        )

        val dragLabel = TextView(this).apply {
            text = "PRISM"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.14f
            setTextColor(Color.parseColor("#4D5A72"))
        }
        dragStrip.addView(
            dragLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = dpToPx(14)
            },
        )

        val closeBtn = ImageButton(this).apply {
            contentDescription = getString(R.string.close_panel_desc)
            setImageResource(R.drawable.ic_close_panel)
            setColorFilter(Color.parseColor("#8A97AD"))
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dpToPx(12)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { hidePanel() }
        }
        dragStrip.addView(
            closeBtn,
            FrameLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = dpToPx(4)
            },
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#25314A"))
        }

        var panelStartX = 0
        var panelStartY = 0
        var dragTouchStartX = 0f
        var dragTouchStartY = 0f

        dragStrip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelStartX = params.x
                    panelStartY = params.y
                    dragTouchStartX = event.rawX
                    dragTouchStartY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragTouchStartX).roundToInt()
                    val dy = (event.rawY - dragTouchStartY).roundToInt()
                    val (curW, curH) = getScreenSizePx()
                    params.x = (panelStartX + dx).coerceIn(0, (curW - params.width).coerceAtLeast(0))
                    params.y = (panelStartY + dy).coerceIn(0, (curH - params.height).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(root, params)
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.savePanelPosition(params.x, params.y)
                    true
                }

                else -> false
            }
        }

        root.addView(
            dragStrip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dragStripHeight),
        )
        root.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)),
        )

        val wv = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    return false
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "WebView [${it.lineNumber()}]: ${it.message()}")
                    }
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    showOverlayConfirmDialog(message ?: "Confirm?", result)
                    return true
                }
            }
            addJavascriptInterface(AndroidBridge(), JS_BRIDGE_NAME)
            loadUrl(OVERLAY_ASSET_URL)
        }

        root.addView(
            wv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        panelContainer = root
        panelParams = params
        webView = wv
        windowManager.addView(root, params)
    }

    // ---- Window 3: Selection Box ----
    private fun createSelectionBoxWindow() {
        val initialBounds = resolveInitialBoxBounds()

        lateinit var boxView: SelectionBoxView
        boxView = SelectionBoxView(
            context = this,
            getScreenSize = { getScreenSizePx() },
            onBoundsChanging = { bx, by, bw, bh ->
                val pad = boxView.handlePaddingPx
                selectionBoxParams?.let { sp ->
                    sp.x = bx - pad
                    sp.y = by - pad
                    sp.width = bw + pad * 2
                    sp.height = bh + pad * 2
                    try {
                        windowManager.updateViewLayout(boxView, sp)
                    } catch (_: Exception) {
                    }
                }
                pushBoxChangedToJs(BoxBounds(bx, by, bw, bh))
            },
            onBoundsCommitted = { bounds ->
                prefs.saveBoxBounds(bounds)
                pushBoxChangedToJs(bounds)
            },
        ).apply {
            currentBoxX = initialBounds.x
            currentBoxY = initialBounds.y
            currentBoxW = initialBounds.w
            currentBoxH = initialBounds.h
            visibility = View.GONE
        }

        val pad = boxView.handlePaddingPx
        val params = WindowManager.LayoutParams(
            initialBounds.w + pad * 2,
            initialBounds.h + pad * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialBounds.x - pad
            y = initialBounds.y - pad
        }

        selectionBoxView = boxView
        selectionBoxParams = params
        windowManager.addView(boxView, params)
    }

    private fun resolveInitialBoxBounds(): BoxBounds {
        val (sw, sh) = getScreenSizePx()
        val saved = prefs.getBoxBoundsOrNull()
        if (saved != null) {
            val w = saved.w.coerceIn(dpToPx(120), sw.coerceAtLeast(dpToPx(120)))
            val h = saved.h.coerceIn(dpToPx(80), sh.coerceAtLeast(dpToPx(80)))
            val x = saved.x.coerceIn(0, (sw - w).coerceAtLeast(0))
            val y = saved.y.coerceIn(0, (sh - h).coerceAtLeast(0))
            return BoxBounds(x, y, w, h)
        }
        val defaultW = (sw * 0.85f).roundToInt().coerceAtLeast(dpToPx(120))
        val defaultH = (sh * 0.40f).roundToInt().coerceAtLeast(dpToPx(80))
        val defaultX = ((sw - defaultW) / 2).coerceAtLeast(0)
        val defaultY = ((sh - defaultH) / 2).coerceAtLeast(0)
        return BoxBounds(defaultX, defaultY, defaultW, defaultH).also {
            prefs.saveBoxBounds(it)
        }
    }

    private fun getCurrentBoxBounds(): BoxBounds {
        val bv = selectionBoxView ?: return resolveInitialBoxBounds()
        return BoxBounds(bv.currentBoxX, bv.currentBoxY, bv.currentBoxW, bv.currentBoxH)
    }

    private fun showSelectionBoxInternal() {
        mainHandler.post {
            val bv = selectionBoxView ?: return@post
            val bp = selectionBoxParams ?: return@post
            isSelectionVisible = true
            bv.isLocked = false
            bv.visibility = View.VISIBLE
            bp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try {
                windowManager.updateViewLayout(bv, bp)
            } catch (_: Exception) {
            }
            val bounds = getCurrentBoxBounds()
            prefs.saveBoxBounds(bounds)
            pushBoxChangedToJs(bounds)
            bringBubbleAndPanelToFrontIfNeeded()
        }
    }

    private fun lockSelectionBoxInternal() {
        mainHandler.post {
            val bv = selectionBoxView ?: return@post
            val bp = selectionBoxParams ?: return@post
            isSelectionVisible = true
            bv.isLocked = true
            bv.visibility = View.VISIBLE
            bp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try {
                windowManager.updateViewLayout(bv, bp)
            } catch (_: Exception) {
            }
            val bounds = getCurrentBoxBounds()
            prefs.saveBoxBounds(bounds)
            pushBoxChangedToJs(bounds)
        }
    }

    private fun bringBubbleAndPanelToFrontIfNeeded() {
        bubbleView?.let { bv ->
            bubbleParams?.let { bp ->
                try {
                    windowManager.removeViewImmediate(bv)
                    windowManager.addView(bv, bp)
                } catch (_: Exception) {
                }
            }
        }
        if (isPanelVisible) {
            panelContainer?.let { pv ->
                panelParams?.let { pp ->
                    try {
                        windowManager.removeViewImmediate(pv)
                        windowManager.addView(pv, pp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun showPanel() {
        mainHandler.post {
            isPanelVisible = true
            panelContainer?.visibility = View.VISIBLE
            clampViewsToScreen()
        }
    }

    private fun hidePanel() {
        mainHandler.post {
            isPanelVisible = false
            panelContainer?.visibility = View.GONE
        }
    }

    private fun pulseBubbleInternal() {
        mainHandler.post {
            val bv = bubbleView ?: return@post
            bv.animate().cancel()
            bv.animate()
                .scaleX(1.25f)
                .scaleY(1.25f)
                .alpha(1f)
                .setDuration(140)
                .withEndAction {
                    bv.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .start()
                }
                .start()
        }
    }

    private fun clampViewsToScreen() {
        val (sw, sh) = getScreenSizePx()
        bubbleView?.let { bv ->
            bubbleParams?.let { bp ->
                bp.x = bp.x.coerceIn(0, (sw - bp.width).coerceAtLeast(0))
                bp.y = bp.y.coerceIn(0, (sh - bp.height).coerceAtLeast(0))
                try {
                    windowManager.updateViewLayout(bv, bp)
                } catch (_: Exception) {
                }
            }
        }
        panelContainer?.let { pv ->
            panelParams?.let { pp ->
                pp.width = dpToPx(340).coerceAtMost((sw - dpToPx(16)).coerceAtLeast(dpToPx(240)))
                pp.height = dpToPx(520).coerceAtMost((sh - dpToPx(32)).coerceAtLeast(dpToPx(300)))
                pp.x = pp.x.coerceIn(0, (sw - pp.width).coerceAtLeast(0))
                pp.y = pp.y.coerceIn(0, (sh - pp.height).coerceAtLeast(0))
                try {
                    windowManager.updateViewLayout(pv, pp)
                } catch (_: Exception) {
                }
            }
        }
        selectionBoxView?.let { bv ->
            selectionBoxParams?.let { sp ->
                val bw = bv.currentBoxW.coerceIn(dpToPx(120), sw.coerceAtLeast(dpToPx(120)))
                val bh = bv.currentBoxH.coerceIn(dpToPx(80), sh.coerceAtLeast(dpToPx(80)))
                val bx = bv.currentBoxX.coerceIn(0, (sw - bw).coerceAtLeast(0))
                val by = bv.currentBoxY.coerceIn(0, (sh - bh).coerceAtLeast(0))
                bv.currentBoxX = bx
                bv.currentBoxY = by
                bv.currentBoxW = bw
                bv.currentBoxH = bh
                val pad = bv.handlePaddingPx
                sp.x = bx - pad
                sp.y = by - pad
                sp.width = bw + pad * 2
                sp.height = bh + pad * 2
                try {
                    windowManager.updateViewLayout(bv, sp)
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---- Continuous Screen Watching (3fps -> 1fps) ----
    private fun requestStartWatching() {
        mainHandler.post {
            if (isWatching.get()) return@post
            // Lock selection box so quiz taps underneath pass through cleanly during watching.
            lockSelectionBoxInternal()

            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ScreenCaptureActivity", e)
                stopWatchingInternal(notifyJs = true)
            }
        }
    }

    private fun beginContinuousCapture(resultCode: Int, data: Intent) {
        releaseCaptureResources()

        try {
            startForegroundCompat(watching = true)
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
            if (projection == null) {
                stopWatchingInternal(notifyJs = true)
                return
            }
            activeProjection = projection

            val (screenW, screenH) = getScreenSizePx()
            val densityDpi = resources.displayMetrics.densityDpi
            val scale = if (screenW > MAX_WIDTH_PX) MAX_WIDTH_PX.toFloat() / screenW.toFloat() else 1f
            captureScreenW = screenW.coerceAtLeast(1)
            captureScreenH = screenH.coerceAtLeast(1)
            captureW = (captureScreenW * scale).roundToInt().coerceAtLeast(1)
            captureH = (captureScreenH * scale).roundToInt().coerceAtLeast(1)

            val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
            activeImageReader = reader

            if (smallBitmap == null || smallBitmap?.isRecycled == true) {
                smallBitmap = Bitmap.createBitmap(DIFF_W, DIFF_H, Bitmap.Config.ARGB_8888)
                smallCanvas = Canvas(smallBitmap!!)
            }

            hasRefFrame = false
            lastChangeTimeMs = SystemClock.elapsedRealtime()
            isWatching.set(true)
            prefs.isWatchRunning = true
            OverlayPreferences.setWatchingActive(true)

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (isWatching.get()) {
                        mainHandler.post {
                            stopWatchingInternal(notifyJs = true)
                        }
                    }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, captureHandler)

            activeVirtualDisplay = projection.createVirtualDisplay(
                "PrismContinuousWatch",
                captureW,
                captureH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )

            if (isScreenInteractive) {
                // Allow consent sheet to settle briefly before reading first frame.
                scheduleNextFrameTick(INITIAL_SETTLE_DELAY_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting continuous capture", e)
            stopWatchingInternal(notifyJs = true)
        }
    }

    private fun processCaptureTick() {
        if (!isWatching.get() || !isScreenInteractive) return

        val reader = activeImageReader ?: return
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image != null) {
                analyzeCapturedImage(image)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring/analyzing frame", e)
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {
            }
        }

        if (isWatching.get() && isScreenInteractive) {
            val idleMs = SystemClock.elapsedRealtime() - lastChangeTimeMs
            val nextDelay = if (idleMs >= IDLE_DROP_AFTER_MS) FPS_1_INTERVAL_MS else FPS_3_INTERVAL_MS
            scheduleNextFrameTick(nextDelay)
        }
    }

    private fun analyzeCapturedImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureW
        val paddedW = captureW + rowPadding / pixelStride

        var fullBmp = reusableFullBitmap
        if (fullBmp == null || fullBmp.isRecycled || fullBmp.width != paddedW || fullBmp.height != captureH) {
            fullBmp?.recycle()
            fullBmp = Bitmap.createBitmap(paddedW, captureH, Bitmap.Config.ARGB_8888)
            reusableFullBitmap = fullBmp
        }
        buffer.rewind()
        fullBmp.copyPixelsFromBuffer(buffer)

        // Map current selection box from screen pixels to capture coordinates, inset slightly inside 2dp stroke.
        val box = getCurrentBoxBounds()
        val scaleX = captureW.toFloat() / captureScreenW.toFloat()
        val scaleY = captureH.toFloat() / captureScreenH.toFloat()
        val strokeInsetPx = (dpToPx(2) * scaleX).roundToInt()

        val cropLeft = (box.x * scaleX).roundToInt().plus(strokeInsetPx).coerceIn(0, (captureW - 2).coerceAtLeast(0))
        val cropTop = (box.y * scaleY).roundToInt().plus(strokeInsetPx).coerceIn(0, (captureH - 2).coerceAtLeast(0))
        val cropRight = ((box.x + box.w) * scaleX).roundToInt().minus(strokeInsetPx).coerceIn(cropLeft + 1, captureW)
        val cropBottom = ((box.y + box.h) * scaleY).roundToInt().minus(strokeInsetPx).coerceIn(cropTop + 1, captureH)
        val cropW = (cropRight - cropLeft).coerceAtLeast(1)
        val cropH = (cropBottom - cropTop).coerceAtLeast(1)

        srcRect.set(cropLeft, cropTop, cropRight, cropBottom)
        val sBmp = smallBitmap ?: return
        val sCanvas = smallCanvas ?: return
        sCanvas.drawBitmap(fullBmp, srcRect, dstSmallRect, smallPaint)
        sBmp.getPixels(smallPixels, 0, DIFF_W, 0, 0, DIFF_W, DIFF_H)

        for (i in 0 until DIFF_PIXEL_COUNT) {
            val c = smallPixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            currGray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val shouldEmit: Boolean
        if (!hasRefFrame) {
            currGray.copyInto(refGray)
            hasRefFrame = true
            lastChangeTimeMs = SystemClock.elapsedRealtime()
            shouldEmit = true
        } else {
            var changed = 0
            for (i in 0 until DIFF_PIXEL_COUNT) {
                if (abs(currGray[i] - refGray[i]) > PIXEL_DELTA_THRESHOLD) {
                    changed++
                }
            }
            val ratio = changed.toFloat() / DIFF_PIXEL_COUNT.toFloat()
            if (ratio > sensitivityThreshold) {
                currGray.copyInto(refGray)
                lastChangeTimeMs = SystemClock.elapsedRealtime()
                shouldEmit = true
            } else {
                // Slow moving average absorbs minor clock/cursor drift without re-triggering.
                for (i in 0 until DIFF_PIXEL_COUNT) {
                    refGray[i] = refGray[i] * 0.9f + currGray[i] * 0.1f
                }
                shouldEmit = false
            }
        }

        if (shouldEmit) {
            val base64 = encodeCroppedPngBase64(fullBmp, cropLeft, cropTop, cropW, cropH)
            mainHandler.post {
                if (isWatching.get()) {
                    evaluateJs("window.onFrame && window.onFrame('$base64')")
                }
            }
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

        val out = ByteArrayOutputStream()
        exportBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        exportBmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun scheduleNextFrameTick(delayMs: Long) {
        captureHandler?.removeCallbacks(frameTickRunnable)
        captureHandler?.postDelayed(frameTickRunnable, delayMs)
    }

    private fun stopWatchingInternal(notifyJs: Boolean) {
        val wasWatching = isWatching.getAndSet(false)
        prefs.isWatchRunning = false
        OverlayPreferences.setWatchingActive(false)
        releaseCaptureResources()

        if (wasWatching) {
            try {
                startForegroundCompat(watching = false)
            } catch (_: Exception) {
            }
        }

        if (notifyJs) {
            evaluateJs("window.onWatchStopped && window.onWatchStopped()")
        }
    }

    private fun releaseCaptureResources() {
        captureHandler?.removeCallbacks(frameTickRunnable)

        try {
            activeVirtualDisplay?.release()
        } catch (_: Exception) {
        }
        activeVirtualDisplay = null

        try {
            activeImageReader?.close()
        } catch (_: Exception) {
        }
        activeImageReader = null

        try {
            projectionCallback?.let { activeProjection?.unregisterCallback(it) }
            activeProjection?.stop()
        } catch (_: Exception) {
        }
        projectionCallback = null
        activeProjection = null

        try {
            reusableFullBitmap?.recycle()
        } catch (_: Exception) {
        }
        reusableFullBitmap = null

        try {
            smallBitmap?.recycle()
        } catch (_: Exception) {
        }
        smallBitmap = null
        smallCanvas = null
        hasRefFrame = false
    }

    private fun pushBoxChangedToJs(bounds: BoxBounds) {
        val jsonLiteral = org.json.JSONObject.quote(bounds.toJsonString())
        evaluateJs("window.onBoxChanged && window.onBoxChanged($jsonLiteral)")
    }

    private fun evaluateJs(script: String) {
        mainHandler.post {
            webView?.evaluateJavascript(script, null)
        }
    }

    private fun showOverlayConfirmDialog(message: String, result: JsResult?) {
        try {
            val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show JS confirm dialog", e)
            result?.cancel()
        }
    }

    private fun getScreenSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    @Suppress("DEPRECATION")
    private fun getParcelableIntentCompat(intent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun showSelectionBox() {
            showSelectionBoxInternal()
        }

        @JavascriptInterface
        fun lockSelectionBox() {
            lockSelectionBoxInternal()
        }

        @JavascriptInterface
        fun getBoxBounds(): String {
            return getCurrentBoxBounds().toJsonString()
        }

        @JavascriptInterface
        fun startWatching() {
            requestStartWatching()
        }

        @JavascriptInterface
        fun stopWatching() {
            mainHandler.post {
                stopWatchingInternal(notifyJs = false)
            }
        }

        @JavascriptInterface
        fun setSensitivity(value: Float) {
            val clamped = value.coerceIn(0.01f, 0.30f)
            sensitivityThreshold = clamped
            prefs.sensitivity = clamped
        }

        @JavascriptInterface
        fun pulseBubble() {
            pulseBubbleInternal()
        }

        @JavascriptInterface
        fun startDrag() {
            // Native 44dp top drag strip manages touch dragging directly.
            Log.d(TAG, "AndroidBridge.startDrag invoked")
        }

        @JavascriptInterface
        fun closePanel() {
            hidePanel()
        }

        @JavascriptInterface
        fun log(message: String?) {
            Log.d(TAG, "JS: ${message ?: ""}")
        }
    }

    companion object {
        private const val TAG = "PrismOverlayService"
        private const val CHANNEL_ID = "prism_overlay_channel"
        private const val NOTIFICATION_ID = 4101
        private const val JS_BRIDGE_NAME = "AndroidBridge"
        private const val OVERLAY_ASSET_URL = "file:///android_asset/overlay.html"

        private const val MAX_WIDTH_PX = 1280
        private const val DIFF_W = 64
        private const val DIFF_H = 36
        private const val DIFF_PIXEL_COUNT = DIFF_W * DIFF_H
        private const val PIXEL_DELTA_THRESHOLD = 16f

        private const val FPS_3_INTERVAL_MS = 333L
        private const val FPS_1_INTERVAL_MS = 1000L
        private const val IDLE_DROP_AFTER_MS = 10_000L
        private const val INITIAL_SETTLE_DELAY_MS = 350L

        const val ACTION_WATCH_GRANTED = "com.prism.overlay.ACTION_WATCH_GRANTED"
        const val ACTION_WATCH_CANCELLED = "com.prism.overlay.ACTION_WATCH_CANCELLED"
        const val ACTION_WATCH_FAILED = "com.prism.overlay.ACTION_WATCH_FAILED"
        const val ACTION_STOP_WATCHING = "com.prism.overlay.ACTION_STOP_WATCHING"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }
}

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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.capture.ScreenCaptureActivity
import com.example.data.BoxBounds
import com.example.data.OverlayPreferences
import org.json.JSONArray
import org.json.JSONObject
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

    // Floating Web browser UI inside the Panel
    private var tabPrismBtn: TextView? = null
    private var tabWebBtn: TextView? = null
    private var floatingWebContainer: LinearLayout? = null
    private var webBrowserView: WebView? = null
    private var webUrlInput: EditText? = null
    private var webStatusBanner: TextView? = null
    private var webProgressBar: ProgressBar? = null
    private var isWebTabActive = false
    private var lastScannedQuery: String = ""

    private var selectionBoxView: SelectionBoxView? = null
    private var selectionBoxParams: WindowManager.LayoutParams? = null

    private var isPanelVisible = false
    private var isSelectionVisible = false

    // Continuous capture & single-shot web scan state
    private val isWatching = AtomicBoolean(false)
    private val pendingSingleScanForWeb = AtomicBoolean(false)
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

    // Reusable buffer and change detector for zero-allocation steady-state detection
    private var reusableFullBitmap: Bitmap? = null
    private val frameChangeDetector = FrameChangeDetector()
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
        val scanMode = intent?.getStringExtra(EXTRA_SCAN_MODE) ?: SCAN_MODE_CONTINUOUS
        when (intent?.action) {
            ACTION_WATCH_GRANTED -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                val resultData = getParcelableIntentCompat(intent, EXTRA_RESULT_DATA)
                if (resultCode != Int.MIN_VALUE && resultData != null) {
                    if (scanMode == SCAN_MODE_SINGLE_WEB) {
                        captureSingleFrameForWeb(resultCode, resultData)
                    } else {
                        beginContinuousCapture(resultCode, resultData)
                    }
                } else {
                    if (scanMode == SCAN_MODE_SINGLE_WEB) {
                        showPanel()
                        showWebStatus("Izin rekam layar ditolak", isError = true)
                        evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Screen capture denied')")
                    } else {
                        stopWatchingInternal(notifyJs = true)
                    }
                }
            }

            ACTION_WATCH_CANCELLED, ACTION_WATCH_FAILED -> {
                if (scanMode == SCAN_MODE_SINGLE_WEB) {
                    val err = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Scan dibatalkan"
                    showPanel()
                    showWebStatus(err, isError = true)
                    evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled(${JSONObject.quote(err)})")
                } else {
                    stopWatchingInternal(notifyJs = true)
                }
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

        webBrowserView?.let { bwv ->
            try {
                bwv.stopLoading()
                bwv.destroy()
            } catch (_: Exception) {
            }
        }
        webBrowserView = null
        floatingWebContainer = null

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

        // 2-tab switcher: Prism AI | Floating Web
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0D1320"))
        }

        val prismTab = TextView(this).apply {
            text = "Prism AI"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dpToPx(36)
            setOnClickListener { switchPanelTab(webTab = false) }
        }
        val webTab = TextView(this).apply {
            text = "Floating Web"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dpToPx(36)
            setOnClickListener { switchPanelTab(webTab = true) }
        }
        tabBar.addView(prismTab, LinearLayout.LayoutParams(0, dpToPx(36), 1f))
        tabBar.addView(webTab, LinearLayout.LayoutParams(0, dpToPx(36), 1f))
        root.addView(
            tabBar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36)),
        )
        root.addView(
            View(this).apply { setBackgroundColor(Color.parseColor("#25314A")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)),
        )

        tabPrismBtn = prismTab
        tabWebBtn = webTab

        val contentHost = FrameLayout(this)

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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val configuredKey = resolveConfiguredApiKey(BuildConfig.GEMINI_API_KEY)
                    if (configuredKey.isNotEmpty()) {
                        val quotedKey = JSONObject.quote(configuredKey)
                        view?.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var raw = localStorage.getItem('prism_v2');
                                var st = raw ? JSON.parse(raw) : {};
                                if (!st.apiKey) {
                                  st.apiKey = $quotedKey;
                                  localStorage.setItem('prism_v2', JSON.stringify(st));
                                }
                              } catch (e) {}
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
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

        contentHost.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val webContainer = createFloatingWebViewContainer()
        contentHost.addView(
            webContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(
            contentHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        panelContainer = root
        panelParams = params
        webView = wv
        switchPanelTab(webTab = false)
        windowManager.addView(root, params)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createFloatingWebViewContainer(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E17"))
            visibility = View.INVISIBLE
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#111826"))
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
        }

        fun navBtn(label: String, desc: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                contentDescription = desc
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E6EDF7"))
                gravity = Gravity.CENTER
                minWidth = dpToPx(30)
                minHeight = dpToPx(34)
                setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
                setOnClickListener { onClick() }
            }
        }

        val urlBox = EditText(this).apply {
            hint = "Cari di Google AI atau ketik URL…"
            setHintTextColor(Color.parseColor("#4D5A72"))
            setTextColor(Color.parseColor("#E6EDF7"))
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#161F30"))
                setStroke(dpToPx(1), Color.parseColor("#25314A"))
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    navigateFloatingWebInput(text.toString())
                    true
                } else {
                    false
                }
            }
        }

        val scanPill = TextView(this).apply {
            text = "Scan"
            contentDescription = "Scan kotak ke Google AI"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0A0E17"))
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#00E5A0"))
            }
            setOnClickListener { requestScanBoxForWeb() }
        }

        val backBtn = navBtn("<", "Kembali") {
            if (webBrowserView?.canGoBack() == true) webBrowserView?.goBack()
        }
        val fwdBtn = navBtn(">", "Maju") {
            if (webBrowserView?.canGoForward() == true) webBrowserView?.goForward()
        }
        val reloadBtn = navBtn("↻", "Muat ulang") {
            webBrowserView?.reload()
        }

        toolbar.addView(backBtn, LinearLayout.LayoutParams(dpToPx(28), dpToPx(34)))
        toolbar.addView(fwdBtn, LinearLayout.LayoutParams(dpToPx(28), dpToPx(34)))
        toolbar.addView(reloadBtn, LinearLayout.LayoutParams(dpToPx(28), dpToPx(34)))
        toolbar.addView(
            urlBox,
            LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(6)
            },
        )
        toolbar.addView(scanPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(34)))

        val quickRowScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0D1320"))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(6))
        }
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun makeChip(title: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = title
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E6EDF7"))
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor("#161F30"))
                    setStroke(dpToPx(1), Color.parseColor("#25314A"))
                }
                setOnClickListener { onClick() }
            }
        }

        val chips = listOf(
            makeChip("Google") {
                prefs.preferGoogleAiMode = false
                loadUrlInFloatingWeb(OverlayPreferences.buildGoogleSearchUrl(lastScannedQuery, aiMode = false))
            },
            makeChip("Google AI") {
                prefs.preferGoogleAiMode = true
                loadUrlInFloatingWeb(OverlayPreferences.buildGoogleSearchUrl(lastScannedQuery, aiMode = true))
            },
            makeChip("Gemini") {
                loadUrlInFloatingWeb("https://gemini.google.com")
            },
            makeChip("Lens") {
                loadUrlInFloatingWeb("https://lens.google.com")
            },
        )
        chips.forEach { chip ->
            quickRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dpToPx(6) },
            )
        }
        quickRowScroll.addView(quickRow)

        val statusBanner = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#00E5A0"))
            setBackgroundColor(Color.parseColor("#162238"))
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        val browser = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                val defaultUa = WebSettings.getDefaultUserAgent(this@OverlayService)
                userAgentString = defaultUa.replace("; wv)", ")").replace("; wv", "")
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    return if (scheme == "http" || scheme == "https") {
                        false
                    } else {
                        true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        urlBox.setText(url)
                        prefs.lastFloatingUrl = url
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            loadUrl(prefs.lastFloatingUrl)
        }

        container.addView(
            toolbar,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            quickRowScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            statusBanner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            progress,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(2)),
        )
        container.addView(
            browser,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        floatingWebContainer = container
        webBrowserView = browser
        webUrlInput = urlBox
        webStatusBanner = statusBanner
        webProgressBar = progress
        return container
    }

    private fun showWebStatus(message: String, isError: Boolean = false) {
        mainHandler.post {
            webStatusBanner?.apply {
                if (message.isBlank()) {
                    visibility = View.GONE
                } else {
                    text = message
                    setTextColor(Color.parseColor(if (isError) "#FF5E7E" else "#00E5A0"))
                    setBackgroundColor(Color.parseColor(if (isError) "#291522" else "#162238"))
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun switchPanelTab(webTab: Boolean) {
        isWebTabActive = webTab
        val prismWv = webView
        val webCont = floatingWebContainer
        if (webTab) {
            prismWv?.visibility = View.INVISIBLE
            webCont?.visibility = View.VISIBLE
        } else {
            prismWv?.visibility = View.VISIBLE
            webCont?.visibility = View.INVISIBLE
        }

        tabPrismBtn?.apply {
            setTextColor(Color.parseColor(if (!webTab) "#00E5A0" else "#8A97AD"))
            setBackgroundColor(Color.parseColor(if (!webTab) "#161F30" else "#0D1320"))
        }
        tabWebBtn?.apply {
            setTextColor(Color.parseColor(if (webTab) "#B47EFF" else "#8A97AD"))
            setBackgroundColor(Color.parseColor(if (webTab) "#161F30" else "#0D1320"))
        }
    }

    private fun navigateFloatingWebInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return
        val looksLikeUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            (!trimmed.contains(' ') && trimmed.contains('.'))
        val target = if (looksLikeUrl) {
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
        } else {
            lastScannedQuery = trimmed
            OverlayPreferences.buildGoogleSearchUrl(trimmed, aiMode = prefs.preferGoogleAiMode)
        }
        loadUrlInFloatingWeb(target)
    }

    private fun loadUrlInFloatingWeb(url: String) {
        mainHandler.post {
            val safeUrl = url.ifBlank { OverlayPreferences.DEFAULT_GOOGLE_SEARCH_HOME }
            switchPanelTab(webTab = true)
            showPanel()
            webUrlInput?.setText(safeUrl)
            prefs.lastFloatingUrl = safeUrl
            webBrowserView?.loadUrl(safeUrl)
        }
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

    // ---- Continuous Screen Watching (3fps -> 1fps) & Single-Shot Box Scan ----
    private fun requestStartWatching() {
        mainHandler.post {
            if (isWatching.get()) return@post
            // Lock selection box so quiz taps underneath pass through cleanly during watching.
            lockSelectionBoxInternal()

            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                putExtra(EXTRA_SCAN_MODE, SCAN_MODE_CONTINUOUS)
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

    private fun requestScanBoxForWeb() {
        mainHandler.post {
            lockSelectionBoxInternal()
            hidePanel()
            showWebStatus("Menangkap isi kotak…")

            if (isWatching.get() && activeProjection != null && activeImageReader != null) {
                // Give WindowManager 220ms to remove the floating panel so we don't capture our own WebView.
                captureHandler?.postDelayed({
                    pendingSingleScanForWeb.set(true)
                    processCaptureTick()
                }, 220L)
                return@post
            }

            val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                putExtra(EXTRA_SCAN_MODE, SCAN_MODE_SINGLE_WEB)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start single-shot scan", e)
                showPanel()
                showWebStatus("Gagal memulai scan layar", isError = true)
                evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Gagal memulai scan layar')")
            }
        }
    }

    private fun captureSingleFrameForWeb(resultCode: Int, data: Intent) {
        releaseCaptureResources()

        try {
            startForegroundCompat(watching = true)
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
            if (projection == null) {
                startForegroundCompat(watching = false)
                showPanel()
                showWebStatus("Izin capture tidak tersedia", isError = true)
                evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Izin capture tidak tersedia')")
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

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!isWatching.get()) {
                        releaseCaptureResources()
                    }
                }
            }
            projectionCallback = callback
            projection.registerCallback(callback, captureHandler)

            activeVirtualDisplay = projection.createVirtualDisplay(
                "PrismSingleScan",
                captureW,
                captureH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )

            attemptSingleFrameGrab(remainingAttempts = 20)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing single scan frame", e)
            releaseCaptureResources()
            startForegroundCompat(watching = false)
            showPanel()
            showWebStatus("Gagal mengambil frame kotak", isError = true)
            evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Gagal mengambil frame kotak')")
        }
    }

    private fun attemptSingleFrameGrab(remainingAttempts: Int) {
        val delayMs = if (remainingAttempts == 20) 450L else 100L
        captureHandler?.postDelayed({
            val reader = activeImageReader
            if (reader == null) {
                releaseCaptureResources()
                mainHandler.post {
                    startForegroundCompat(watching = false)
                    showPanel()
                    showWebStatus("Reader capture ditutup", isError = true)
                    evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Reader ditutup')")
                }
                return@postDelayed
            }

            var image: Image? = null
            var croppedBitmap: Bitmap? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val bmp = copyImageToReusableBitmap(image)
                    val candidate = FrameChangeDetector.cropBoxBitmap(
                        fullBmp = bmp,
                        box = getCurrentBoxBounds(),
                        screenW = captureScreenW,
                        screenH = captureScreenH,
                        captureW = captureW,
                        captureH = captureH,
                        strokeInsetPx = dpToPx(2),
                    )
                    if (remainingAttempts > 4 && FrameChangeDetector.isBitmapMostlyBlank(candidate)) {
                        candidate.recycle()
                    } else {
                        croppedBitmap = candidate
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Single frame grab attempt failed", e)
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {
                }
            }

            if (croppedBitmap != null) {
                releaseCaptureResources()
                mainHandler.post {
                    startForegroundCompat(watching = false)
                }
                handleScannedBoxBitmap(croppedBitmap)
            } else if (remainingAttempts > 1) {
                attemptSingleFrameGrab(remainingAttempts - 1)
            } else {
                releaseCaptureResources()
                mainHandler.post {
                    startForegroundCompat(watching = false)
                    showPanel()
                    showWebStatus("Layar di kotak tidak terbaca, coba ulang", isError = true)
                    evaluateJs("window.onSingleScanCancelled && window.onSingleScanCancelled('Frame layar kosong, coba ulang')")
                }
            }
        }, delayMs)
    }

    private fun handleScannedBoxBitmap(croppedBitmap: Bitmap) {
        val base64 = FrameChangeDetector.encodeBitmapToBase64(croppedBitmap)

        mainHandler.post {
            switchPanelTab(webTab = true)
            showPanel()
            showWebStatus("Membaca teks di dalam kotak…")
        }

        BoxTextRecognizer.recognizeBitmap(croppedBitmap) { ocrText ->
            try {
                croppedBitmap.recycle()
            } catch (_: Exception) {
            }

            mainHandler.post {
                val quotedOcr = JSONObject.quote(ocrText)
                if (ocrText.isNotEmpty()) {
                    lastScannedQuery = ocrText
                    showWebStatus("Scan: $ocrText")
                    val targetUrl = OverlayPreferences.buildGoogleSearchUrl(
                        query = ocrText,
                        aiMode = prefs.preferGoogleAiMode,
                    )
                    loadUrlInFloatingWeb(targetUrl)
                } else {
                    showWebStatus("Teks tidak terdeteksi OCR — mencoba AI Vision…")
                }
                evaluateJs("window.onScanForWeb && window.onScanForWeb('$base64', $quotedOcr)")
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

            frameChangeDetector.reset()
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

    private fun copyImageToReusableBitmap(image: Image): Bitmap {
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
        return fullBmp
    }

    private fun analyzeCapturedImage(image: Image) {
        val fullBmp = copyImageToReusableBitmap(image)
        val box = getCurrentBoxBounds()

        if (pendingSingleScanForWeb.getAndSet(false)) {
            val scanBmp = FrameChangeDetector.cropBoxBitmap(
                fullBmp = fullBmp,
                box = box,
                screenW = captureScreenW,
                screenH = captureScreenH,
                captureW = captureW,
                captureH = captureH,
                strokeInsetPx = dpToPx(2),
            )
            handleScannedBoxBitmap(scanBmp)
        }

        val result = frameChangeDetector.evaluateFrame(
            fullBitmap = fullBmp,
            box = box,
            screenW = captureScreenW,
            screenH = captureScreenH,
            captureW = captureW,
            captureH = captureH,
            strokeInsetPx = dpToPx(2),
            sensitivity = sensitivityThreshold,
        )

        if (result.shouldEmit && result.base64Png != null) {
            lastChangeTimeMs = SystemClock.elapsedRealtime()
            val base64 = result.base64Png
            mainHandler.post {
                if (isWatching.get()) {
                    evaluateJs("window.onFrame && window.onFrame('$base64')")
                }
            }
        }
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
        frameChangeDetector.reset()
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
        fun getDefaultApiKey(): String {
            return resolveConfiguredApiKey(BuildConfig.GEMINI_API_KEY)
        }

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean {
            return PrismAccessibilityService.isAccessibilityEnabled()
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            mainHandler.post {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open Accessibility settings", e)
                }
            }
        }

        @JavascriptInterface
        fun setAutoClickEnabled(enabled: Boolean) {
            prefs.isAutoClickEnabled = enabled
        }

        @JavascriptInterface
        fun isAutoClickEnabled(): Boolean {
            return prefs.isAutoClickEnabled
        }

        @JavascriptInterface
        fun autoClickAnswer(answerText: String?, answerIndex: Int, optionsJson: String?) {
            val service = PrismAccessibilityService.getInstance()
            if (service == null) {
                evaluateJs(
                    "window.onAutoClickResult && window.onAutoClickResult(false, ${
                        JSONObject.quote("Aktifkan Prism Auto-Click di Accessibility Settings")
                    })",
                )
                return
            }

            val options = mutableListOf<String>()
            if (!optionsJson.isNullOrBlank()) {
                try {
                    val arr = JSONArray(optionsJson)
                    for (i in 0 until arr.length()) {
                        options.add(arr.optString(i, ""))
                    }
                } catch (_: Exception) {
                }
            }

            service.performAutoClickForAnswer(
                answerText = answerText.orEmpty(),
                answerIndex = answerIndex,
                options = options,
            ) { success, message ->
                evaluateJs("window.onAutoClickResult && window.onAutoClickResult($success, ${JSONObject.quote(message)})")
            }
        }

        @JavascriptInterface
        fun scanBoxToGoogleAi() {
            requestScanBoxForWeb()
        }

        @JavascriptInterface
        fun notifyWebScanStatus(message: String?, isError: Boolean) {
            showWebStatus(message.orEmpty(), isError)
        }

        @JavascriptInterface
        fun openGoogleAiSearch(query: String?) {
            val cleaned = query?.trim().orEmpty()
            if (cleaned.isNotEmpty()) {
                lastScannedQuery = cleaned
            }
            val targetUrl = OverlayPreferences.buildGoogleSearchUrl(
                query = cleaned,
                aiMode = prefs.preferGoogleAiMode,
            )
            showWebStatus(if (cleaned.isNotEmpty()) "Mencari: $cleaned" else "")
            loadUrlInFloatingWeb(targetUrl)
        }

        @JavascriptInterface
        fun openFloatingWeb(url: String?) {
            val target = url?.trim().orEmpty()
            if (target.isEmpty()) {
                loadUrlInFloatingWeb(prefs.lastFloatingUrl)
            } else {
                navigateFloatingWebInput(target)
            }
        }

        @JavascriptInterface
        fun log(message: String?) {
            Log.d(TAG, "JS: ${message ?: ""}")
        }
    }

    companion object {
        private const val TAG = "PrismOverlayService"
        private const val PLACEHOLDER_GEMINI_KEY = "MY_GEMINI_API_KEY"

        internal fun resolveConfiguredApiKey(rawKey: String?): String {
            val trimmed = rawKey?.trim().orEmpty()
            return if (trimmed.isEmpty() || trimmed == PLACEHOLDER_GEMINI_KEY) "" else trimmed
        }
        private const val CHANNEL_ID = "prism_overlay_channel"
        private const val NOTIFICATION_ID = 4101
        private const val JS_BRIDGE_NAME = "AndroidBridge"
        private const val OVERLAY_ASSET_URL = "file:///android_asset/overlay.html"

        private const val MAX_WIDTH_PX = 1280
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
        const val EXTRA_SCAN_MODE = "extra_scan_mode"

        const val SCAN_MODE_CONTINUOUS = "continuous"
        const val SCAN_MODE_SINGLE_WEB = "single_web"
    }
}

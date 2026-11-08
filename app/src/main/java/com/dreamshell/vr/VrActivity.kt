package com.dreamshell.vr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import com.dreamshell.vr.controller.ControllerState
import com.dreamshell.vr.controller.DaydreamController
import com.dreamshell.vr.controller.PointerMapper
import com.dreamshell.vr.gl.StereoRenderer
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The in-headset screen: a browser or a video player shown on a virtual cinema
 * screen, in stereo, driven by head tracking and the Daydream controller.
 *
 * Controller (Air pointer mode):
 *   point        move the cursor
 *   click pad    tap (hold and move to drag)
 *   swipe pad    scroll the page / scrub the video
 *   App button   back
 *   Home button  recenter view and pointer
 *   Volume +/-   media volume
 * Trackpad mode: the pad moves the cursor; click-and-drag scrolls.
 */
class VrActivity : Activity(), SensorEventListener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
        const val EXTRA_SBS = "sbs"
        const val EXTRA_SHOW_MENU = "show_menu"
        const val MODE_BROWSER = "browser"
        const val MODE_VIDEO = "video"
        /** Grid wall and alignment line for tuning the lens settings. */
        const val MODE_CALIBRATE = "calibrate"

        /** Target CSS density for pages: 1600 px / 1.6 = 1000 CSS px wide. */
        private const val CONTENT_DENSITY = 1.6f
        private const val TAG = "VrActivity"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: VrSettings
    private lateinit var renderer: StereoRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var sensorManager: SensorManager
    private lateinit var audio: AudioManager
    private var mode = MODE_BROWSER
    private var running = false

    // Browser
    private var offscreen: OffscreenLayout? = null
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var layoutScale = 1f
    private var homepageMenuView: HomepageMenuView? = null
    private var safetyDisclaimerView: SafetyDisclaimerView? = null
    private var vrKeyboardView: VrKeyboardView? = null

    // Video
    private var player: MediaPlayer? = null
    private var prepared = false
    private var sbs = false
    private var lastInteraction = 0L
    private var scrubbing = false
    private var scrubStartMs = 0
    private var scrubTargetMs = 0

    // Pointer + gestures
    private var prev = ControllerState()
    private val pointer = PointerMapper()
    private var lastGamepadAt = 0L
    private var hintUntil = 0L
    private var cursorU = 0.5f
    private var cursorV = 0.5f
    private var onScreen = false
    private var pressActive = false
    private var pressDownTime = 0L
    private var homeDownTime = 0L
    private var homeLongPressHandled = false
    private var pressLongPressHandled = false
    private var scrollActive = false
    private var scrollDownTime = 0L
    private var scrollStartX = 0f
    private var scrollStartY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var scrollMaxDist = 0f

    // Head tracking scratch
    private val rot = FloatArray(16)
    private val remapped = FloatArray(16)

    // ---- Lifecycle ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = VrSettings.load(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BROWSER
        sbs = intent.getBooleanExtra(EXTRA_SBS, false)
        sensorManager = getSystemService(SensorManager::class.java)
        audio = getSystemService(AudioManager::class.java)

        requestedOrientation = if (settings.reverseLandscape)
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        if (getSystemService(PowerManager::class.java)?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }

        val dm = resources.displayMetrics
        val pxPerM = (dm.xdpi + dm.ydpi) / 2f / 0.0254f
        renderer = StereoRenderer(settings, pxPerM) { surface, _ -> main.post { attachSurface(surface) } }
        renderer.sideBySide3d = sbs && mode == MODE_VIDEO
        renderer.calibration = mode == MODE_CALIBRATE
        renderer.cursorVisible = false
        settings.applyTo(pointer)

        val root = FrameLayout(this)
        if (mode == MODE_BROWSER) {
            layoutScale = (dm.density / CONTENT_DENSITY).coerceAtLeast(1f)
            val host = OffscreenLayout(this).also { it.layoutScale = layoutScale }
            val wv = createWebView()
            host.addView(wv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(host, FrameLayout.LayoutParams(
                (StereoRenderer.CONTENT_W * layoutScale).toInt(),
                (StereoRenderer.CONTENT_H * layoutScale).toInt()))
            offscreen = host
            webView = wv
            renderer.contentAspect = StereoRenderer.CONTENT_W.toFloat() / StereoRenderer.CONTENT_H
            wv.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "https://www.youtube.com")
            if (!settings.disclaimerAccepted) {
                showSafetyDisclaimer()
            } else {
                showHomepageMenu()
            }
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(glView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        running = true
        // Override system auto-brightness to prevent light sensor flickering inside VR headset
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        // Pick up anything changed on the start screen since last time.
        settings = VrSettings.load(this)
        renderer.settings = settings
        settings.applyTo(pointer)
        pointer.clearHome()
        hintUntil = SystemClock.uptimeMillis() + 6000
        hideSystemBars()
        glView.onResume()
        webView?.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        DaydreamController.addStateListener(controllerListener)
        DaydreamController.connect(this) // no-op if already connected
        Choreographer.getInstance().postFrameCallback(frameCallback)
        main.post(progressTick)
    }

    override fun onPause() {
        running = false
        sensorManager.unregisterListener(this)
        DaydreamController.removeStateListener(controllerListener)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        main.removeCallbacks(progressTick)
        player?.takeIf { prepared && it.isPlaying }?.pause()
        webView?.onPause()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        player?.release(); player = null
        webView?.destroy(); webView = null
        renderer.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Keeps redrawing the offscreen browser into its Surface every vsync. */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            offscreen?.postInvalidateOnAnimation()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun attachSurface(surface: Surface) {
        if (mode == MODE_BROWSER) {
            offscreen?.targetSurface = surface
        } else if (mode == MODE_VIDEO) {
            val p = player
            if (p == null) startVideo(surface) else p.setSurface(surface)
        }
    }

    // ---- Browser --------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onInputFocused() {
                main.post { showKeyboard() }
            }
        }, "AndroidKeyboard")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectKeyboardFocusListener(view)
            }
        }
        webChromeClient = object : WebChromeClient() {
            // Page video going fullscreen: show it filling the virtual screen.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView?.let { offscreen?.removeView(it) }
                customView = view
                customViewCallback = callback
                offscreen?.addView(view, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            override fun onHideCustomView() {
                customView?.let { offscreen?.removeView(it) }
                customView = null
                customViewCallback = null
            }
        }
    }

    private fun injectKeyboardFocusListener(wv: WebView?) {
        val js = """
            (function() {
                if (window.__vrKeyboardInjected) return;
                window.__vrKeyboardInjected = true;
                document.addEventListener('focusin', function(e) {
                    var t = e.target;
                    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) {
                        if (window.AndroidKeyboard) {
                            window.AndroidKeyboard.onInputFocused();
                        }
                    }
                }, true);
            })();
        """.trimIndent()
        wv?.evaluateJavascript(js, null)
    }

    private fun contentX() = cursorU * StereoRenderer.CONTENT_W * layoutScale
    private fun contentY() = cursorV * StereoRenderer.CONTENT_H * layoutScale

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val host = offscreen ?: return
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        host.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun handleBrowserInput(s: ControllerState) {
        val x = contentX()
        val y = contentY()

        // Click = tap; holding the click and moving drags; long press opens homepage menu.
        if (s.isClickDown && !prev.isClickDown) {
            pressLongPressHandled = false
            pressDownTime = SystemClock.uptimeMillis()
            if (scrollActive) endScroll(cancel = true)
            if (onScreen) {
                pressActive = true
                inject(MotionEvent.ACTION_DOWN, pressDownTime, x, y)
            }
        } else if (s.isClickDown && pressActive) {
            if (!pressLongPressHandled && SystemClock.uptimeMillis() - pressDownTime >= 800) {
                pressLongPressHandled = true
                inject(MotionEvent.ACTION_CANCEL, pressDownTime, x, y)
                toggleHomepageMenu()
            } else if (!pressLongPressHandled) {
                inject(MotionEvent.ACTION_MOVE, pressDownTime, x, y)
            }
        } else if (!s.isClickDown && pressActive) {
            if (!pressLongPressHandled) {
                inject(MotionEvent.ACTION_UP, pressDownTime, x, y)
            }
            pressActive = false
        }

        // Air-pointer mode: swiping the pad scrolls, like dragging a finger on the page.
        if (settings.airPointer && !s.isClickDown && homepageMenuView == null) {
            if (s.isTouching && !scrollActive && onScreen) {
                scrollActive = true
                scrollDownTime = SystemClock.uptimeMillis()
                scrollStartX = x; scrollStartY = y
                touchStartX = s.touchX; touchStartY = s.touchY
                scrollMaxDist = 0f
                inject(MotionEvent.ACTION_DOWN, scrollDownTime, x, y)
            } else if (s.isTouching && scrollActive) {
                val dir = if (settings.invertScroll) -1f else 1f
                val sens = settings.touchpadSensitivity
                val dx = dir * (s.touchX - touchStartX) * StereoRenderer.CONTENT_W * layoutScale * 1.2f * sens
                val dy = dir * (s.touchY - touchStartY) * StereoRenderer.CONTENT_H * layoutScale * 1.5f * sens
                scrollMaxDist = maxOf(scrollMaxDist, hypot(dx, dy))
                inject(MotionEvent.ACTION_MOVE, scrollDownTime, scrollStartX + dx, scrollStartY + dy)
            } else if (!s.isTouching && scrollActive) {
                endScroll(cancel = scrollMaxDist < 12f * layoutScale)
            }
        }
        renderer.cursorPressed = pressActive
    }

    // ---- Homepage Menu & Safety Disclaimer Overlays ---------------------------------------

    fun showSafetyDisclaimer() {
        if (mode != MODE_BROWSER) return
        val host = offscreen ?: return
        if (safetyDisclaimerView != null) return

        val view = SafetyDisclaimerView(
            context = this,
            onAccept = {
                settings = settings.copy(disclaimerAccepted = true)
                settings.save(this)
                hideSafetyDisclaimer()
                showHomepageMenu()
            }
        )
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        host.addView(view)
        safetyDisclaimerView = view
    }

    fun hideSafetyDisclaimer() {
        val view = safetyDisclaimerView ?: return
        offscreen?.removeView(view)
        safetyDisclaimerView = null
    }

    fun toggleHomepageMenu() {
        if (homepageMenuView != null) hideHomepageMenu() else showHomepageMenu()
    }

    // ---- Mini QWERTY Floating Keyboard ---------------------------------------------------

    fun toggleKeyboard() {
        if (vrKeyboardView != null) hideKeyboard() else showKeyboard()
    }

    fun showKeyboard() {
        if (mode != MODE_BROWSER) return
        val host = offscreen ?: return
        if (vrKeyboardView != null) return

        val kb = VrKeyboardView(
            context = this,
            onKeyInput = { str -> sendKeyInputToWebView(str) },
            onBackspace = { sendBackspaceToWebView() },
            onEnter = { sendEnterToWebView() },
            onDismiss = { hideKeyboard() }
        )
        kb.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        host.addView(kb)
        vrKeyboardView = kb
    }

    fun hideKeyboard() {
        val kb = vrKeyboardView ?: return
        offscreen?.removeView(kb)
        vrKeyboardView = null
    }

    private fun sendKeyInputToWebView(text: String) {
        val wv = webView ?: return
        val safeText = text.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                var el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                    var start = el.selectionStart || (el.value ? el.value.length : 0);
                    var end = el.selectionEnd || start;
                    var val = el.value || '';
                    if (typeof el.value !== 'undefined') {
                        el.value = val.substring(0, start) + '$safeText' + val.substring(end);
                        el.selectionStart = el.selectionEnd = start + '$safeText'.length;
                    } else if (el.isContentEditable) {
                        document.execCommand('insertText', false, '$safeText');
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)

        for (ch in text) {
            val upper = ch.uppercaseChar()
            val keyCode = when (upper) {
                in 'A'..'Z' -> KeyEvent.keyCodeFromString("KEYCODE_$upper")
                in '0'..'9' -> KeyEvent.keyCodeFromString("KEYCODE_$upper")
                ' ' -> KeyEvent.KEYCODE_SPACE
                '.' -> KeyEvent.KEYCODE_PERIOD
                else -> 0
            }
            if (keyCode != 0) {
                val t = SystemClock.uptimeMillis()
                wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
                wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
            }
        }
    }

    private fun sendBackspaceToWebView() {
        val wv = webView ?: return
        val js = """
            (function() {
                var el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    var start = el.selectionStart || 0;
                    var end = el.selectionEnd || start;
                    var val = el.value || '';
                    if (start > 0 && start === end) {
                        el.value = val.substring(0, start - 1) + val.substring(start);
                        el.selectionStart = el.selectionEnd = start - 1;
                    } else if (start !== end) {
                        el.value = val.substring(0, start) + val.substring(end);
                        el.selectionStart = el.selectionEnd = start;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } else if (el && el.isContentEditable) {
                    document.execCommand('delete', false, null);
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)

        val t = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
    }

    private fun sendEnterToWebView() {
        val wv = webView ?: return
        val js = """
            (function() {
                var el = document.activeElement;
                if (el && el.form) {
                    el.form.submit();
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)

        val t = SystemClock.uptimeMillis()
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        wv.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }

    fun showHomepageMenu() {
        if (mode != MODE_BROWSER) return
        val host = offscreen ?: return
        if (homepageMenuView != null) return

        val menu = HomepageMenuView(
            context = this,
            onOpenVrApp = { app ->
                hideHomepageMenu()
                webView?.loadUrl(app.url)
            },
            onLaunchNativeApp = { app ->
                hideHomepageMenu()
                if (!app.launchNativeApp(this)) {
                    webView?.loadUrl(app.url)
                }
            },
            onOpenSettings = {
                hideHomepageMenu()
                startActivity(Intent(this, MainActivity::class.java))
            },
            onOpenDisclaimer = {
                hideHomepageMenu()
                showSafetyDisclaimer()
            },
            onDismiss = {
                hideHomepageMenu()
            }
        )
        menu.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        host.addView(menu)
        homepageMenuView = menu
    }

    fun hideHomepageMenu() {
        val menu = homepageMenuView ?: return
        offscreen?.removeView(menu)
        homepageMenuView = null
    }

    private fun endScroll(cancel: Boolean) {
        if (!scrollActive) return
        scrollActive = false
        // Cancel instead of lifting when the finger barely moved, so a rest isn't a tap.
        inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,
            scrollDownTime, scrollStartX, scrollStartY)
    }

    private fun tapAtCursor() {
        if (mode != MODE_BROWSER || !onScreen) return
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, t, contentX(), contentY())
        inject(MotionEvent.ACTION_UP, t, contentX(), contentY())
    }

    // ---- Video ----------------------------------------------------------------------------

    private fun startVideo(surface: Surface) {
        val uri = intent.data ?: run { finish(); return }
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build())
        p.setSurface(surface)
        p.setOnPreparedListener { prepared = true; it.start() }
        p.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) renderer.contentAspect = if (sbs) w / 2f / h else w.toFloat() / h
        }
        p.setOnCompletionListener { lastInteraction = SystemClock.uptimeMillis() }
        p.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error $what/$extra for $uri")
            renderer.progress = 0f
            true
        }
        try {
            p.setDataSource(this, uri)
            p.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $uri", e)
            finish()
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.start()
        lastInteraction = SystemClock.uptimeMillis()
    }

    private fun handleVideoInput(s: ControllerState) {
        val p = player
        if (s.isClickDown && !prev.isClickDown) togglePlay()
        if (p == null || !prepared) return

        // Swipe left/right on the pad to scrub; the seek happens when you let go.
        if (s.isTouching && !prev.isTouching) {
            touchStartX = s.touchX
            scrubStartMs = p.currentPosition
            scrubbing = false
        } else if (s.isTouching && !s.isClickDown) {
            val dx = (s.touchX - touchStartX) * settings.touchpadSensitivity
            if (abs(dx) > 0.08f) scrubbing = true
            if (scrubbing) {
                val range = minOf(p.duration, 300_000).coerceAtLeast(1)
                scrubTargetMs = (scrubStartMs + dx * range).toInt().coerceIn(0, p.duration)
                lastInteraction = SystemClock.uptimeMillis()
            }
        } else if (!s.isTouching && prev.isTouching && scrubbing) {
            p.seekTo(scrubTargetMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
            scrubbing = false
            lastInteraction = SystemClock.uptimeMillis()
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            val p = player
            if (mode == MODE_VIDEO && p != null && prepared && p.duration > 0) {
                val show = !p.isPlaying || scrubbing || SystemClock.uptimeMillis() - lastInteraction < 3000
                val pos = if (scrubbing) scrubTargetMs else p.currentPosition
                renderer.progress = if (show) pos.toFloat() / p.duration else -1f
            }
            renderer.hudText = hudLine()
            if (!DaydreamController.isStreaming &&
                SystemClock.uptimeMillis() - lastGamepadAt > 5000) renderer.cursorVisible = false
            if (running) main.postDelayed(this, 100)
        }
    }

    /** One line under the screen: controller problems first, then a short hint. */
    private fun hudLine(): String {
        if (!DaydreamController.isStreaming && SystemClock.uptimeMillis() - lastGamepadAt > 5000) {
            return when (DaydreamController.status) {
                DaydreamController.Status.STREAMING, DaydreamController.Status.CONNECTED ->
                    "Controller connected but silent: press Home to wake it"
                DaydreamController.Status.PAIRING -> "Pairing: accept the prompt on the phone screen"
                DaydreamController.Status.SCANNING -> "Looking for controller: hold Home"
                DaydreamController.Status.CONNECTING, DaydreamController.Status.SUBSCRIBING ->
                    "Connecting to controller…"
                DaydreamController.Status.BLUETOOTH_OFF -> "Bluetooth is off"
                else -> "No controller: connect it on the start screen"
            }
        }
        if (mode == MODE_CALIBRATE) {
            val s = settings
            return "${s.viewer.label}  ·  lenses ${"%.1f".format(s.interLensMm)} mm  ·  " +
                "k1 ${"%.3f".format(s.k1)}  k2 ${"%.3f".format(s.k2)}  k3 ${"%.3f".format(s.k3)}"
        }
        if (SystemClock.uptimeMillis() < hintUntil) return "Point & tap screen · Hold Home for Menu · Press K for Mini Keyboard"
        return ""
    }

    // ---- Controller -----------------------------------------------------------------------

    private val controllerListener = DaydreamController.StateListener { onController(it) }

    private fun onController(s: ControllerState) {
        val q = s.orientationQuat()

        // Home button: tap recentres; hold (>700ms) opens Homepage Menu.
        if (s.isHomeDown && !prev.isHomeDown) {
            homeDownTime = SystemClock.uptimeMillis()
            homeLongPressHandled = false
        } else if (s.isHomeDown && !homeLongPressHandled) {
            if (SystemClock.uptimeMillis() - homeDownTime >= 700) {
                homeLongPressHandled = true
                toggleHomepageMenu()
            }
        } else if (!s.isHomeDown && prev.isHomeDown) {
            if (!homeLongPressHandled) {
                recenter(q)
                hintUntil = 0L
                pointer.setHome(q)
            }
        }

        if (s.isAppDown && !prev.isAppDown) goBack()
        if (s.isVolPlusDown && !prev.isVolPlusDown) volume(AudioManager.ADJUST_RAISE)
        if (s.isVolMinusDown && !prev.isVolMinusDown) volume(AudioManager.ADJUST_LOWER)

        updatePointer(s, q)
        when (mode) {
            MODE_VIDEO -> handleVideoInput(s)
            MODE_BROWSER -> handleBrowserInput(s)
        }
        prev = s
    }

    private fun updatePointer(s: ControllerState, q: FloatArray) {
        if (mode != MODE_BROWSER) { renderer.cursorVisible = false; return }
        val (w, h) = StereoRenderer.screenSize(settings, renderer.contentAspect)
        if (settings.airPointer) {
            val uv = pointer.screenUv(q, w, h, settings.screenDistanceM)
            if (uv != null) {
                cursorU = uv[0]
                cursorV = uv[1]
                onScreen = cursorU in 0f..1f && cursorV in 0f..1f
            } else {
                onScreen = false
            }
        } else if (s.isTouching && prev.isTouching) {
            val sens = settings.touchpadSensitivity
            cursorU = (cursorU + (s.touchX - prev.touchX) * 1.0f * sens).coerceIn(0f, 1f)
            cursorV = (cursorV + (s.touchY - prev.touchY) * 1.0f * sens).coerceIn(0f, 1f)
            onScreen = true
        } else {
            onScreen = true
        }
        renderer.cursorU = cursorU.coerceIn(0f, 1f)
        renderer.cursorV = cursorV.coerceIn(0f, 1f)
        renderer.cursorVisible = onScreen
    }

    private fun recenter(q: FloatArray? = null) {
        renderer.recenter()
        if (q != null) pointer.setHome(q)
        cursorU = 0.5f; cursorV = 0.5f
        renderer.cursorU = cursorU; renderer.cursorV = cursorV
    }

    private fun volume(direction: Int) {
        // No FLAG_SHOW_UI: the system volume panel would be unreadable in the headset.
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    private fun goBack() {
        when {
            safetyDisclaimerView != null -> hideSafetyDisclaimer()
            homepageMenuView != null -> hideHomepageMenu()
            customView != null -> {
                val cb = customViewCallback
                if (cb != null) cb.onCustomViewHidden()
                else { offscreen?.removeView(customView); customView = null }
            }
            mode == MODE_BROWSER && webView?.canGoBack() == true -> webView?.goBack()
            else -> finish()
        }
    }

    // ---- Keyboard / gamepad fallback --------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = 0.03f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (mode == MODE_VIDEO) togglePlay() else tapAtCursor()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> goBack()
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_Y -> recenter()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X ->
                toggleHomepageMenu()
            KeyEvent.KEYCODE_K -> toggleKeyboard()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun nudge(du: Float, dv: Float) {
        if (mode == MODE_VIDEO) {
            val p = player ?: return
            if (prepared && du != 0f) {
                p.seekTo((p.currentPosition + (if (du > 0) 10_000 else -10_000)).toLong().coerceAtLeast(0),
                    MediaPlayer.SEEK_CLOSEST_SYNC)
                lastInteraction = SystemClock.uptimeMillis()
            }
            return
        }
        if (mode != MODE_BROWSER) return
        lastGamepadAt = SystemClock.uptimeMillis()
        cursorU = (cursorU + du).coerceIn(0f, 1f)
        cursorV = (cursorV + dv).coerceIn(0f, 1f)
        onScreen = true
        renderer.cursorU = cursorU; renderer.cursorV = cursorV; renderer.cursorVisible = true
    }

    // ---- Head tracking --------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        // Remap from the phone's natural (portrait) axes to the landscape screen axes.
        when (display?.rotation ?: Surface.ROTATION_90) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rot, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rot, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            else -> System.arraycopy(rot, 0, remapped, 0, 16)
        }
        // Read row-major as column-major = world->device (a view matrix); then turn the
        // sensor world (Z up) into GL world (Y up).
        Matrix.rotateM(remapped, 0, 90f, 1f, 0f, 0f)
        renderer.setHeadView(remapped)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

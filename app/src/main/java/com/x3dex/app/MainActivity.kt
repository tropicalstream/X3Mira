package com.x3dex.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The phone's screen, on the glasses, driven by the temple trackpad.
 *
 * THE TRACKPAD IS A MOUSE, NOT A TOUCHSCREEN. This is the whole interaction
 * design and it is not a detail. The pad under the wearer's finger is a few
 * centimetres wide and sits nowhere near the picture floating in front of
 * them, so absolute mapping — "touch here, tap there" — is unusable. It
 * moves a CURSOR relatively, the way a laptop trackpad does, and the tap
 * lands wherever the cursor already is. That also matches every other app
 * in this suite, so the gesture vocabulary the wearer already has carries
 * over.
 *
 * TextureView, not SurfaceView, and the reason is the glasses: the display
 * is one logical viewport duplicated into two eyes by [BinocularSbsLayout],
 * which works by drawing its child twice inside dispatchDraw. A SurfaceView
 * owns a separate hardware layer that the view hierarchy cannot draw twice,
 * so it would appear in one eye only. TextureView renders in the hierarchy
 * and duplicates for free.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var video: TextureView
    private lateinit var cursor: View
    private lateinit var status: TextView
    private lateinit var settings: SettingsPanel

    private var link: DexLink? = null
    private var discovery: Discovery? = null
    private val ui = Handler(Looper.getMainLooper())

    // Cursor lives in VIEWPORT pixels; taps convert to a fraction of the
    // video rectangle at the moment of the tap.
    private var cx = VIEW_W / 2f
    private var cy = VIEW_H / 2f

    private var lastTapUp = 0L
    private var tapStreak = 0
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val content = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(VIEW_W, VIEW_H)
            setBackgroundColor(Color.BLACK)
        }
        video = TextureView(this).apply {
            // Sized to the FRAME's aspect once geometry is known, never
            // MATCH_PARENT. TextureView scales its content to whatever
            // bounds it is given and preserves nothing: filling a 640x480
            // landscape viewport with a 720x1544 portrait phone squashes
            // the picture wide and squat. See fitVideoToFrame.
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
        }
        content.addView(video)

        // No visible cursor, per the wearer: the bright dot floating over
        // the phone read as clutter. The pointer POSITION still exists —
        // taps land where the trackpad last moved it — it simply is not
        // drawn. The phone's own focus highlight is the feedback instead.
        cursor = View(this)   // never attached; kept so moveCursor is a no-op-safe

        status = TextView(this).apply {
            setTextColor(0xFF7FDBFF.toInt())
            textSize = 11f
            setPadding(6, 4, 6, 4)
            text = "starting"
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        }
        content.addView(status)

        settings = SettingsPanel(this) { applySettings() }
        settings.bindGlobal { action -> link?.global(action) }
        content.addView(settings.view)

        root = FrameLayout(this)
        val sbs = BinocularSbsLayout(this)
        sbs.addView(content)
        root.addView(sbs)
        setContentView(root)

        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                connect(Surface(st))
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                link?.stop(); return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        moveCursor(0f, 0f)
    }

    private fun connect(surface: Surface) {
        link?.stop()
        if (discovery == null) discovery = Discovery(this).also { it.start() }
        // Discovered address wins; the settings IP is the fallback for
        // networks that block mDNS. Resolved lazily on each reconnect so an
        // address change is picked up without restarting anything.
        val hostProvider = { discovery?.host ?: Prefs.host(this) }
        link = DexLink(
            hostProvider = hostProvider,
            onState = { s -> ui.post { status.text = "${hostProvider()} — $s" } },
            onGeometry = { w, h, input ->
                ui.post {
                    Log.i(DexLink.TAG, "geometry ${w}x$h input=$input")
                    fitVideoToFrame(w, h)
                    if (!input) status.text = "${hostProvider()} — connected (enable DexProbe input on the phone to click)"
                }
            },
            onStats = { fps, lat ->
                ui.post { status.text = "%s — %.0f fps  %.0f ms".format(hostProvider(), fps, lat) }
            }
        ).also { it.start(surface) }
    }

    /**
     * Letterbox the video to the phone's real aspect ratio.
     *
     * The viewport is 640x480 landscape and the phone is a tall portrait
     * rectangle, so the fit is height-limited: 480/1544 gives 0.311, and
     * the picture lands at 224x480 centred, with black either side. That
     * black is not wasted space — it is the difference between reading the
     * phone and looking at a funhouse mirror.
     *
     * Doing it in LAYOUT rather than with a TextureView transform keeps
     * [videoRect] honest: the cursor maps against the view's real bounds,
     * so what the wearer points at is what the phone gets tapped on. The
     * previous code letterboxed in the click math but stretched on screen,
     * which meant every click landed somewhere the wearer had not aimed.
     */
    private fun fitVideoToFrame(fw: Int, fh: Int) {
        if (fw <= 0 || fh <= 0) return
        val scale = minOf(VIEW_W.toFloat() / fw, VIEW_H.toFloat() / fh)
        val w = (fw * scale).toInt().coerceAtLeast(1)
        val h = (fh * scale).toInt().coerceAtLeast(1)
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h) return
        lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
        video.layoutParams = lp
        Log.i(DexLink.TAG, "fit ${fw}x$fh -> ${w}x$h (scale %.3f)".format(scale))
    }

    private fun applySettings() {
        status.text = "reconnecting…"
        video.surfaceTexture?.let { connect(Surface(it)) }
    }

    // ── Trackpad ─────────────────────────────────────────────────────

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (settings.isShowing) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis()
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && kotlin.math.hypot(dx, dy) > SLOP) dragging = true
                if (dragging) {
                    moveCursor(dx * Prefs.speed(this), dy * Prefs.speed(this))
                    downX = ev.x; downY = ev.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && SystemClock.uptimeMillis() - downAt < TAP_MS) onPadTap()
                dragging = false
            }
        }
        return true
    }

    /**
     * The right-arm click arrives as a KEY on this hardware, so it is the
     * primary "mouse button" — and a triple press is the settings gesture
     * the rest of the suite uses, so it means the same thing here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTap = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!isTap) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) onPadTap()
        return true
    }

    private fun onPadTap() {
        val now = SystemClock.uptimeMillis()
        tapStreak = if (now - lastTapUp < MULTI_MS) tapStreak + 1 else 1
        lastTapUp = now
        ui.removeCallbacksAndMessages(TOKEN)
        if (tapStreak >= 3) {
            tapStreak = 0
            settings.toggle()
            return
        }
        // Wait out the window before committing a single or double, so the
        // third tap of a settings gesture never lands on the phone first.
        val streak = tapStreak
        ui.postAtTime({
            when (streak) {
                1 -> sendTap(false)
                2 -> sendTap(true)   // double = long press: context menus
            }
            tapStreak = 0
        }, TOKEN, SystemClock.uptimeMillis() + MULTI_MS)
    }

    private fun sendTap(long: Boolean) {
        val l = link ?: return
        val r = videoRect() ?: return
        val fx = ((cx - r[0]) / r[2]).coerceIn(0f, 1f)
        val fy = ((cy - r[1]) / r[3]).coerceIn(0f, 1f)
        if (long) l.longPress(fx, fy) else l.tap(fx, fy)
    }

    /**
     * Where the phone's picture actually sits inside the viewport, letterboxed
     * to its aspect. Without this the cursor would be right on screen and
     * wrong on the phone by the width of the black bars.
     */
    /**
     * Where the picture actually sits, taken from the view itself rather
     * than recomputed. Two places deriving the same rectangle is how the
     * display and the click math drifted apart in the first place.
     */
    private fun videoRect(): FloatArray? {
        val w = video.width.toFloat()
        val h = video.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        return floatArrayOf((VIEW_W - w) / 2f, (VIEW_H - h) / 2f, w, h)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        // Position only — nothing is drawn. The tap reads cx/cy to place the
        // click; the wearer aims by the phone's response, not a dot.
        cx = (cx + dx).coerceIn(0f, VIEW_W - 1f)
        cy = (cy + dy).coerceIn(0f, VIEW_H - 1f)
    }

    override fun onDestroy() { link?.stop(); discovery?.stop(); super.onDestroy() }

    companion object {
        const val VIEW_W = 640
        const val VIEW_H = 480
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val SLOP = 3f
        private const val TAP_MS = 300L
        private const val MULTI_MS = 260L
        private val TOKEN = Any()
    }
}

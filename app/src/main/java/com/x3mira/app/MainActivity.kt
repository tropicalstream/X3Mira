package com.x3mira.app

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
    private lateinit var hud: MirrorHud
    private lateinit var notice: TextView
    private lateinit var settings: SettingsPanel

    private var link: DexLink? = null
    private var discovery: Discovery? = null
    private val ui = Handler(Looper.getMainLooper())
    // Last stream geometry, kept so a HUD-config change can refit the video
    // into the new bands without waiting for a reconnect.
    private var lastFrameW = 0
    private var lastFrameH = 0

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
    private var lastX = 0f
    private var lastY = 0f
    private var totalDx = 0f
    private var totalDy = 0f
    private var heldFired = false
    private var agent: PageAgent? = null
    /** Phone-side opt-in; today the glasses only report it, vision is the eye. */
    private var agentA11y = false

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

        // The HUD is the landing screen and the whole not-connected state:
        // opening the icon should show the wearer something alive — the time,
        // the battery, and what the link is doing — not a black rectangle.
        // The video is laid over it and the HUD hidden the instant frames
        // arrive. No visible cursor exists any more; taps land where the pad
        // last aimed and the phone's own focus highlight is the feedback.
        hud = MirrorHud(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        content.addView(hud)

        // A transient line for the one thing the HUD cannot say once the
        // mirror is live: "your taps won't land until you enable input on the
        // phone." It fades on its own; it is not standing chrome.
        notice = TextView(this).apply {
            setTextColor(0xFFFFC65A.toInt())
            textSize = 11f
            setPadding(10, 6, 10, 6)
            gravity = Gravity.CENTER
            // Translucent card: the notice normally sits above the readout
            // band, but if the wearer has turned the readout OFF the video
            // reaches the bottom edge and this 6-second transient overlays it
            // briefly — the card keeps it legible there. Standing HUD never
            // overlaps; this is the one deliberate transient exception.
            setBackgroundColor(0xB0000814.toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        content.addView(notice)

        // The page agent: a tap asks it about whatever the mirror is showing.
        // Its eye is the decoded frame, grabbed on the UI thread at ask time;
        // everything after that runs off it. Its state drives the avatar and
        // its words take the top band, so both live in space the video was
        // already fitted around.
        agent = PageAgent(
            context = this,
            // At DECODE resolution, not view resolution. TextureView.bitmap
            // (no args) returns a copy the size of the VIEW — in portrait
            // that is ~186x397 after letterboxing, where a phone's body text
            // is about three pixels tall and no model can read it. Asking for
            // the stream's own dimensions copies the decoder's output at
            // 720x1536, where the same text is legible.
            frameProvider = {
                val fw = link?.frameW ?: 0
                val fh = link?.frameH ?: 0
                if (!video.isAvailable || fw <= 0 || fh <= 0) null
                else runCatching { video.getBitmap(fw, fh) }.getOrNull()
            },
            onState = { st -> ui.post { if (hud.setAgentState(st)) refit() } },
            onText = { t -> ui.post { if (hud.setAgentLine(t)) refit() } },
            // The agent's hands. Coordinates are already a fraction of the
            // phone's screen, which is what the return channel takes, so the
            // tap goes straight through with no letterbox math.
            onTap = { fx, fy -> link?.tap(fx, fy) },
            onScroll = { dir -> sendAgentScroll(dir) },
            onOpenUrl = { url -> link?.openUrl(url) }
        ).also { it.init() }

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
        // Until the socket says otherwise we are either hunting for the phone
        // (no mDNS answer yet) or dialling the fallback — the HUD says which.
        showHud(
            if (discovery?.host != null) MirrorHud.Phase.CONNECTING else MirrorHud.Phase.SEARCHING,
            hostProvider()
        )
        link = DexLink(
            hostProvider = hostProvider,
            onState = { s -> ui.post { onLinkState(s, hostProvider()) } },
            onGeometry = { w, h, input ->
                ui.post {
                    Log.i(DexLink.TAG, "geometry ${w}x$h input=$input")
                    fitVideoToFrame(w, h)
                    goLive()
                    if (!input) flashNotice("Enable X3Mira input on the phone to click")
                }
            },
            onStats = { _, _ -> ui.post { goLive() } },
            onNotif = { s -> ui.post { hud.setNotif(s) } },
            onHudCfg = { lines, readout, pct, agentOn, a11y ->
                ui.post {
                    // The wearer changed HUD or agent settings on the phone:
                    // apply, and if the reserved bands changed size, refit the
                    // video so mirror and HUD still never overlap.
                    var changed = hud.applyConfig(lines, readout, pct)
                    if (hud.setAgentEnabled(agentOn)) changed = true
                    if (!agentOn) agent?.exit()
                    agentA11y = a11y
                    if (changed) refit()
                }
            }
        ).also { it.start(surface) }
    }

    /** Translate the socket's words into what the HUD should be showing. */
    private fun onLinkState(s: String, host: String) {
        when {
            s.startsWith("connected") -> goLive()
            s.startsWith("connecting") -> showHud(MirrorHud.Phase.CONNECTING, host)
            s.startsWith("disconnected") -> showHud(MirrorHud.Phase.RECONNECTING, host)
        }
    }

    private fun showHud(phase: MirrorHud.Phase, detail: String) {
        hud.setMode(MirrorHud.Mode.LANDING)
        hud.setState(phase, detail)
        if (hud.visibility != View.VISIBLE) hud.visibility = View.VISIBLE
    }

    /**
     * Frames are flowing: shrink the HUD from the full landing screen to the
     * persistent compact readout — time + battery stay on top of the mirror,
     * which is the "HUD info on top" the wearer asked for. The HUD view stays
     * VISIBLE the whole session; the trackpad never reaches it because the
     * activity intercepts input in dispatchTouchEvent.
     */
    private fun goLive() {
        hud.setMode(MirrorHud.Mode.OVERLAY)
        if (hud.visibility != View.VISIBLE) hud.visibility = View.VISIBLE
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }
    private fun flashNotice(msg: String) {
        notice.text = msg
        // Sit just above the readout band, not on it — the two share the
        // bottom centre otherwise.
        (notice.layoutParams as FrameLayout.LayoutParams).bottomMargin = hud.bottomBandPx() + 4
        notice.visibility = View.VISIBLE
        ui.removeCallbacks(hideNotice)
        ui.postDelayed(hideNotice, 6000L)
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
        lastFrameW = fw; lastFrameH = fh
        // The HUD owns full-width bands at the top (notification) and bottom
        // (readout); the video is fitted into what remains, so HUD text can
        // never sit on the mirror — in portrait OR landscape, because a
        // full-width band clears both.
        val top = hud.topBandPx()
        val bottom = hud.bottomBandPx()
        val availH = (VIEW_H - top - bottom).coerceAtLeast(1)
        val scale = minOf(VIEW_W.toFloat() / fw, availH.toFloat() / fh)
        val w = (fw * scale).toInt().coerceAtLeast(1)
        val h = (fh * scale).toInt().coerceAtLeast(1)
        // TOP gravity + an explicit computed margin, NOT CENTER-with-margins:
        // FrameLayout's CENTER applies the margin DIFFERENCE at full value
        // (childTop = (H-h)/2 + topMargin - bottomMargin) — it does not
        // centre between the margins, so asymmetric bands would shove the
        // video into a band. With TOP the margin IS the y position, exactly.
        val y = top + (availH - h) / 2
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h && lp.topMargin == y) return
        lp.width = w; lp.height = h
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = y; lp.bottomMargin = 0
        video.layoutParams = lp
        Log.i(DexLink.TAG, "fit ${fw}x$fh -> ${w}x$h (scale %.3f, bands $top/$bottom, y=$y)".format(scale))
    }

    private fun applySettings() {
        // connect() puts the HUD back up itself; just re-dial with the new prefs.
        video.surfaceTexture?.let { connect(Surface(it)) }
    }

    /** Re-fit the video after anything changed the reserved HUD bands. */
    private fun refit() {
        if (lastFrameW > 0) fitVideoToFrame(lastFrameW, lastFrameH)
    }

    // ── Trackpad ─────────────────────────────────────────────────────

    /**
     * The pad, remapped around the page agent.
     *
     *   tap            ask the agent about what is on screen
     *   double tap     stop the agent / dismiss what it said
     *   triple tap     settings (unchanged — the suite gesture)
     *   swipe up/down  scroll the PHONE
     *   swipe sideways slide the aim point
     *   press & hold   click at the aim point
     *
     * A tap no longer clicks, because the agent took that gesture; hold does
     * it instead, so the wearer has not lost the ability to press something.
     * Vertical and horizontal are split rather than shared so a scroll can
     * never be read as an aim nudge halfway through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // The temple pad is a TOUCH device on this hardware, so taps arrive
        // HERE as motion events, not as key presses. Track the gesture in BOTH
        // states — over the settings page too — so the triple-tap that OPENED
        // the page can also CLOSE it.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis()
                lastX = ev.x; lastY = ev.y
                totalDx = 0f; totalDy = 0f
                dragging = false; heldFired = false
                if (!settings.isShowing) ui.postDelayed(holdClick, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                totalDx += dx; totalDy += dy
                lastX = ev.x; lastY = ev.y
                if (!dragging && kotlin.math.hypot(totalDx, totalDy) > SLOP) {
                    dragging = true
                    ui.removeCallbacks(holdClick)   // a moving finger is not a hold
                }
                // Sideways only slides the aim; vertical is saved for the
                // scroll committed on release.
                if (!settings.isShowing && dragging &&
                    kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
                ) {
                    moveCursor(dx * Prefs.speed(this), 0f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(holdClick)
                val heldAlready = heldFired
                val wasDragging = dragging
                val vertical = kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                val tap = !wasDragging && !heldAlready &&
                    SystemClock.uptimeMillis() - downAt < TAP_MS
                dragging = false; heldFired = false
                if (!settings.isShowing && wasDragging && vertical &&
                    kotlin.math.abs(totalDy) >= SCROLL_MIN
                ) {
                    sendScroll(totalDy)
                    return true
                }
                // onPadTap returns true when the third tap toggled the page;
                // swallow that one so the panel underneath doesn't also act on it.
                if (tap && onPadTap()) return true
            }
        }
        // Over the settings page, still deliver the touch to the panel so its
        // rows and nav buttons keep working — the tap was already counted above.
        return if (settings.isShowing) super.dispatchTouchEvent(ev) else true
    }

    /** Press-and-hold: the click that tap used to be. */
    private val holdClick = Runnable {
        if (dragging || settings.isShowing) return@Runnable
        heldFired = true
        sendTap(false)
        flashNotice("click")
    }

    /**
     * The AGENT's scroll, which is deliberately not the wearer's.
     *
     * A pad flick is a fling: a short, fast gesture that hands the phone
     * momentum and keeps coasting long after the finger stops. That is what
     * a person wants and exactly what an agent must not have — searching a
     * page by flinging it means sailing past the thing you were looking for
     * and never seeing it in any frame. So this is a SLOW DRAG: two thirds of
     * a screen over 700ms, which Android treats as a deliberate scroll with
     * no velocity, leaving a third of the previous view still on screen as
     * overlap so nothing can slip between two looks.
     */
    private fun sendAgentScroll(dir: Int) {
        val l = link ?: return
        val frac = 0.62f                       // travelled, with ~38% overlap kept
        val mid = 0.5f
        val from = if (dir > 0) mid + frac / 2f else mid - frac / 2f
        val to = if (dir > 0) mid - frac / 2f else mid + frac / 2f
        l.swipe(0.5f, from, 0.5f, to, 700)     // slow enough not to fling
    }

    /**
     * One pad swipe becomes one scroll on the phone. Sent as a normalized
     * drag down the middle of the picture: swiping UP on the pad pushes the
     * content up, which is what the same finger would do on the phone itself.
     */
    private fun sendScroll(padDy: Float) {
        val l = link ?: return
        // Pad travel maps to a fraction of the screen, capped so one flick
        // cannot throw the page a dozen screens away.
        val frac = (kotlin.math.abs(padDy) / VIEW_H.toFloat() * Prefs.speed(this) * 1.6f)
            .coerceIn(0.18f, 0.62f)
        val mid = 0.5f
        val from = if (padDy < 0) mid + frac / 2f else mid - frac / 2f
        val to = if (padDy < 0) mid - frac / 2f else mid + frac / 2f
        l.swipe(0.5f, from, 0.5f, to, 260)
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

    /** Returns true if this tap completed the triple and toggled the settings page. */
    private fun onPadTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        tapStreak = if (now - lastTapUp < MULTI_MS) tapStreak + 1 else 1
        lastTapUp = now
        ui.removeCallbacksAndMessages(TOKEN)
        if (tapStreak >= 3) {
            tapStreak = 0
            settings.toggle()
            return true
        }
        // While settings is up, a single or double does nothing itself — it is
        // forwarded to the panel by dispatchTouchEvent — but the streak keeps
        // counting, so a third tap still closes the page.
        if (settings.isShowing) return false
        // Wait out the window before committing a single or double, so the
        // third tap of a settings gesture never reaches the agent first.
        val streak = tapStreak
        ui.postAtTime({
            when (streak) {
                1 -> agent?.activate()   // ask about this screen
                2 -> agent?.exit()       // stop / dismiss
            }
            tapStreak = 0
        }, TOKEN, SystemClock.uptimeMillis() + MULTI_MS)
        return false
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
        // Under TOP gravity the top margin IS the laid-out y — the click math
        // reads the exact same number fitVideoToFrame wrote, so display and
        // taps can never disagree again.
        val lp = video.layoutParams as FrameLayout.LayoutParams
        return floatArrayOf((VIEW_W - w) / 2f, lp.topMargin.toFloat(), w, h)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        // Position only — nothing is drawn. The tap reads cx/cy to place the
        // click; the wearer aims by the phone's response, not a dot.
        cx = (cx + dx).coerceIn(0f, VIEW_W - 1f)
        cy = (cy + dy).coerceIn(0f, VIEW_H - 1f)
    }

    override fun onDestroy() {
        hud.stop(); ui.removeCallbacks(hideNotice); ui.removeCallbacks(holdClick)
        agent?.destroy(); agent = null
        link?.stop(); discovery?.stop(); super.onDestroy()
    }

    companion object {
        const val VIEW_W = 640
        const val VIEW_H = 480
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val SLOP = 3f
        private const val TAP_MS = 300L
        private const val MULTI_MS = 260L
        /** Hold this long without moving and the pad clicks at the aim point. */
        private const val HOLD_MS = 550L
        /** Pad travel that counts as a scroll rather than a wobble. */
        private const val SCROLL_MIN = 14f
        private val TOKEN = Any()
    }
}

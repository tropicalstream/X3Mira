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
    private var p2p: P2pClient? = null
    private val ui = Handler(Looper.getMainLooper())
    // Last stream geometry, kept so a HUD-config change can refit the video
    // into the new bands without waiting for a reconnect.
    private var lastFrameW = 0
    private var lastFrameH = 0

    // Cursor lives in VIEWPORT pixels; taps convert to a fraction of the
    // video rectangle at the moment of the tap.
    private var cx = VIEW_W / 2f
    private var cy = VIEW_H / 2f

    private lateinit var cursor: PadCursor

    /**
     * Mouse mode: the pointer is up and the right pad drives it instead of
     * panning. Temporary by design — it times out, because a pointer that
     * stayed forever would mean the pad had two permanent meanings and the
     * wearer would have to remember which one is current with nothing on
     * screen to tell them.
     */
    private var mouseMode = false
    private var lastMouseAt = 0L
    private var lastEdgePullAt = 0L

    // The held-back pointer sample — see ACTION_MOVE.
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendHeld = false

    /**
     * Pointer speed, chosen on the PHONE and pushed over the link.
     *
     * Separate from Prefs.speed, which stays with panning. They were one
     * number and should not have been: a pan wants to cover ground in a
     * flick, a pointer wants to settle on a target, and tuning one to taste
     * always made the other worse.
     */
    private var pointerPct = 80

    private fun pointerSpeed() = POINTER_BASE * (pointerPct / 100f)

    private val mouseTimeout = Runnable {
        if (mouseMode && SystemClock.uptimeMillis() - lastMouseAt >= MOUSE_IDLE_MS) exitMouseMode()
        else if (mouseMode) armMouseTimeout()
    }

    private fun armMouseTimeout() {
        ui.removeCallbacks(mouseTimeout)
        ui.postDelayed(mouseTimeout, MOUSE_IDLE_MS)
    }

    private fun enterMouseMode() {
        mouseMode = true
        lastMouseAt = SystemClock.uptimeMillis()
        // Summon it where the wearer is LOOKING, not where it was abandoned
        // minutes ago on some other page. Centre of the picture is the one
        // place that is always on screen and always meaningful.
        videoRect()?.let { r -> cx = r[0] + r[2] / 2f; cy = r[1] + r[3] / 2f }
        placeCursor()
        cursor.visibility = View.VISIBLE
        flashNotice("cursor")
        armMouseTimeout()
    }

    private fun exitMouseMode() {
        mouseMode = false
        ui.removeCallbacks(mouseTimeout)
        cursor.visibility = View.GONE
    }

    /** Keep the pointer alive while it is being used. */
    private fun touchedMouse() {
        lastMouseAt = SystemClock.uptimeMillis()
        armMouseTimeout()
    }

    private fun placeCursor() {
        val lp = cursor.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (cx - PadCursor.SIZE / 2f).toInt()
        lp.topMargin = (cy - PadCursor.SIZE / 2f).toInt()
        cursor.layoutParams = lp
    }

    /**
     * Which temple pad an event came from.
     *
     * The two arms are SEPARATE input devices on this hardware — verified with
     * getevent: the right arm is cyttsp5 (/dev/input/event2) and the left is
     * cyttsp6 (/dev/input/event4), each a 639x197 multitouch pad — so an event
     * carries the arm in its device id and nothing extra is needed to tell
     * them apart.
     *
     * Resolved by NAME rather than by the raw id. Android hands out input
     * device ids at enumeration time and they are not stable across reboots or
     * a hot-plug, so pinning arm = id 4 would work perfectly today and quietly
     * swap the wearer's arms some morning after a restart.
     */
    private val armCache = HashMap<Int, Int>()

    private fun armOf(deviceId: Int): Int = armCache.getOrPut(deviceId) {
        val name = runCatching {
            android.view.InputDevice.getDevice(deviceId)?.name.orEmpty()
        }.getOrDefault("")
        val arm = when {
            name.startsWith("cyttsp5") -> ARM_RIGHT
            name.startsWith("cyttsp6") -> ARM_LEFT
            else -> ARM_UNKNOWN
        }
        Log.i(TAG, "pad device $deviceId = '$name' -> ${armName(arm)}")
        arm
    }

    private fun armName(arm: Int) = when (arm) {
        ARM_RIGHT -> "RIGHT"; ARM_LEFT -> "LEFT"; else -> "UNKNOWN"
    }

    // Left-pad gesture state, tracked apart from the right pad's: the two arms
    // can be touched at once, and one shared set of down-coordinates would let
    // a finger resting on one arm cancel the tap being made on the other.
    private var lDownAt = 0L
    private var lDownX = 0f
    private var lDownY = 0f
    private var lMoved = false

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

        // Above the video on purpose — see PadCursor. Hidden until a double
        // tap summons it, so the resting mirror is still an uncluttered
        // picture rather than a desktop with a pointer parked on it.
        cursor = PadCursor(this).apply {
            layoutParams = FrameLayout.LayoutParams(PadCursor.SIZE, PadCursor.SIZE)
            visibility = View.GONE
        }
        content.addView(cursor)

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
            onOpenUrl = { url -> link?.openUrl(url) },
            onType = { text, submit -> link?.typeText(text, submit) },
            onOpenApp = { name -> link?.openApp(name) },
            // Tiny on purpose: this answers "has the picture changed?", not
            // "what does it say", and it is sampled several times a second.
            probeProvider = {
                if (!video.isAvailable) null
                else runCatching { video.getBitmap(48, 96) }.getOrNull()
            }
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
        // Wi-Fi Direct discovery returns an empty list without this grant
        // rather than failing, so it is asked for up front — an unexplained
        // "cannot find the phone" is the worst way to learn about it.
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) runCatching {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 4711)
        }
    }

    private fun connect(surface: Surface) {
        link?.stop()
        if (discovery == null) discovery = Discovery(this).also { it.start() }
        // Opt-in, and read fresh on every reconnect so toggling it in settings
        // takes effect without a restart.
        if (!Prefs.p2p(this)) { p2p?.stop(); p2p = null }
        else if (p2p == null) p2p = P2pClient(this) { addr ->
            // NOTHING to do but note it. The link's own retry loop re-reads
            // hostProvider on every attempt, so the group address is picked up
            // within a retry period by itself. The previous version "helped"
            // by calling link.stop() to reconnect immediately — but nothing
            // restarts a stopped link except a new surface, so the moment the
            // group formed the mirror died for good, three lines after the
            // log celebrated the address it would never dial.
            Log.i(TAG, "p2p host $addr — the link will dial it on its next retry")
        }.also { it.start() }
        // ORDER MATTERS. A formed Wi-Fi Direct group is the most specific
        // answer there is — the phone is right there and owns the address —
        // so it outranks mDNS, which can only speak when a router is carrying
        // multicast. The settings IP stays last, for networks that block mDNS
        // and pairs that are not using P2P. Resolved lazily on each reconnect
        // so a change is picked up without restarting anything.
        val hostProvider = { p2p?.host ?: discovery?.host ?: Prefs.host(this) }
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
                    // Report which channel our router association occupies, so
                    // the phone can put the next group on it. After geometry
                    // on purpose: the hello has been fully consumed, so this
                    // is the first safe moment to speak on the return channel.
                    val hz = runCatching {
                        @Suppress("DEPRECATION")
                        (getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                            .connectionInfo?.frequency ?: 0
                    }.getOrDefault(0)
                    link?.reportStaFreq(if (hz > 0) hz else 0)
                }
            },
            onStats = { _, _ -> ui.post { goLive() } },
            onNotif = { s -> ui.post { hud.setNotif(s) } },
            onHudCfg = { lines, readout, pct, agentOn, a11y, pointer ->
                ui.post {
                    pointerPct = pointer.coerceIn(20, 300)
                    // The wearer changed HUD or agent settings on the phone:
                    // apply, and if the reserved bands changed size, refit the
                    // video so mirror and HUD still never overlap.
                    var changed = hud.applyConfig(lines, readout, pct)
                    if (hud.setAgentEnabled(agentOn)) changed = true
                    if (!agentOn) agent?.exit()
                    // The wire's fifth flag is now "may the agent type" — the
                    // old read-screen-text flag it replaced was assigned here
                    // and read nowhere, so the setting did nothing whichever
                    // way it was set.
                    agent?.typingEnabled = a11y
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
        if (armOf(ev.deviceId) == ARM_LEFT) {
            // EXCEPT over the settings page, where the left pad has to reach
            // the panel like any other touch. Its job out here is "cancel",
            // which has nothing to cancel on a settings screen — so swallowing
            // it there just leaves the wearer holding a page they cannot
            // navigate with the arm they happen to be using.
            if (settings.isShowing) return super.dispatchTouchEvent(ev)
            return leftArm(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis()
                lastX = ev.x; lastY = ev.y
                totalDx = 0f; totalDy = 0f
                dragging = false; heldFired = false
                // No hold timer any more: press-and-hold belongs to the X3
                // OS, and two owners for one gesture is how it ends up
                // firing unpredictably. Clicking is mouse mode's job now.
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                totalDx += dx; totalDy += dy
                lastX = ev.x; lastY = ev.y
                if (!dragging && kotlin.math.hypot(totalDx, totalDy) > SLOP) dragging = true
                // In mouse mode the pad IS the pointer, in both axes — which
                // is new: the aim point used to move only sideways, so it was
                // pinned to one horizontal line across the middle of the
                // screen and could never reach anything above or below it.
                // Outside mouse mode nothing happens until release, where the
                // whole gesture is read at once as a pan.
                if (!settings.isShowing && dragging && mouseMode) {
                    // ONE SAMPLE BEHIND, on purpose. A capacitive pad reports
                    // the centroid of the contact patch, and as a finger lifts
                    // that patch shrinks unevenly — so the last sample before
                    // release is a lurch in whatever direction the fingertip
                    // rolled, and the pointer jumped exactly when the wearer
                    // stopped moving it. Holding each delta until the NEXT one
                    // arrives means the final, dirty sample is simply never
                    // applied: it is discarded on release. Costs one sample of
                    // latency, around 8 ms, which is not perceptible.
                    val s = pointerSpeed()
                    if (pendHeld) moveCursor(pendDx * s, pendDy * s)
                    // A real fingertip cannot cross the pad between two
                    // samples; anything that big is a sensor glitch, not a
                    // gesture, so it is dropped rather than smoothed.
                    val glitch = kotlin.math.hypot(dx, dy) > MAX_STEP
                    pendDx = if (glitch) 0f else dx
                    pendDy = if (glitch) 0f else dy
                    pendHeld = true
                    touchedMouse()
                    edgePull()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                val tap = !wasDragging && SystemClock.uptimeMillis() - downAt < TAP_MS
                dragging = false; heldFired = false
                pendHeld = false        // drop the lift-off sample unapplied
                // A drag pans the phone — in WHICHEVER direction it went. The
                // horizontal axis used to be spent nudging an invisible aim
                // point; panning is what the wearer actually reaches for.
                // In mouse mode the same drag already moved the pointer, so it
                // must not also throw the page around underneath it.
                if (!settings.isShowing && wasDragging && !mouseMode &&
                    kotlin.math.hypot(totalDx, totalDy) >= SCROLL_MIN
                ) {
                    sendPan(totalDx, totalDy)
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

    /**
     * The LEFT pad. One job: cancel.
     *
     * Only the tap is bound, and deliberately so — a left-arm SWIPE is the X3
     * OS volume control, and the app still receives it (verified: the event
     * arrives here even while the OS acts on it). Binding anything to that
     * gesture would mean the wearer silently changes their volume every time
     * they use it, so the left pad's swipe is left strictly alone.
     *
     * Cancel is deliberately one gesture for everything rather than one per
     * thing to cancel: whatever is running — a pointer, an errand, both — this
     * stops it, and there is nothing to remember at the moment you most want
     * something to just stop.
     */
    private fun leftArm(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lDownAt = SystemClock.uptimeMillis(); lDownX = ev.x; lDownY = ev.y
                lMoved = false
            }
            MotionEvent.ACTION_MOVE ->
                if (kotlin.math.hypot(ev.x - lDownX, ev.y - lDownY) > SLOP) lMoved = true
            MotionEvent.ACTION_UP -> {
                if (!lMoved && SystemClock.uptimeMillis() - lDownAt < TAP_MS) {
                    val had = mouseMode
                    if (had) exitMouseMode()
                    agent?.exit()
                    flashNotice(if (had) "cursor off" else "stop")
                }
            }
        }
        return true
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
    /**
     * One pad swipe becomes one pan of the phone, on whichever axis the
     * gesture was mostly along.
     *
     * DIRECT MANIPULATION, matching what the finger would do on the glass:
     * swipe up and the page goes up (so the view scrolls down), swipe right
     * and the page goes right. The vertical half already behaved this way, so
     * nothing about scrolling changes — the horizontal half simply follows the
     * same rule instead of being spent on an invisible aim point.
     *
     * The synthesized swipe is kept away from the screen edges. Android reads
     * a horizontal drag that STARTS at an edge as the system back gesture, so
     * a pan left near the border would navigate back instead of moving the
     * page — which looks like a random bug rather than a boundary.
     */
    private fun sendPan(padDx: Float, padDy: Float) {
        val l = link ?: return
        val horizontal = kotlin.math.abs(padDx) > kotlin.math.abs(padDy)
        val travel = if (horizontal) padDx else padDy
        val extent = (if (horizontal) VIEW_W else VIEW_H).toFloat()
        val frac = (kotlin.math.abs(travel) / extent * Prefs.speed(this) * 1.6f)
            .coerceIn(0.18f, 0.62f)
        val mid = 0.5f
        // Travel NEGATIVE means up or left on the pad; the content should go
        // the same way, so the drag runs from the far side back toward it.
        val from = (if (travel < 0) mid + frac / 2f else mid - frac / 2f)
            .coerceIn(EDGE_SAFE, 1f - EDGE_SAFE)
        val to = (if (travel < 0) mid - frac / 2f else mid + frac / 2f)
            .coerceIn(EDGE_SAFE, 1f - EDGE_SAFE)
        if (horizontal) l.swipe(from, mid, to, mid, 260) else l.swipe(mid, from, mid, to, 260)
    }

    /**
     * Pointer at the border pulls the page along with it.
     *
     * Without this the pointer simply stops at the edge of a screen that may
     * be a page long, and the wearer has to leave mouse mode, pan, and come
     * back — three gestures to continue one movement. Throttled, because a
     * move event arrives every few milliseconds and each pull is a real drag
     * on the phone.
     */
    private fun edgePull() {
        val r = videoRect() ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgePullAt < EDGE_PULL_MS) return
        val dx = when {
            cx <= r[0] + EDGE_PULL_PX -> -1f
            cx >= r[0] + r[2] - EDGE_PULL_PX -> 1f
            else -> 0f
        }
        val dy = when {
            cy <= r[1] + EDGE_PULL_PX -> -1f
            cy >= r[1] + r[3] - EDGE_PULL_PX -> 1f
            else -> 0f
        }
        if (dx == 0f && dy == 0f) return
        lastEdgePullAt = now
        // A modest, fixed nudge rather than a gesture-sized throw: this fires
        // repeatedly while the pointer rests at the border, and a full-size
        // pan each time would rocket the page away.
        sendPan(dx * EDGE_PULL_STEP, dy * EDGE_PULL_STEP)
    }

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
        Log.i(TAG, "pad KEY code=${event.keyCode} action=${event.action} " +
            "arm=${armName(armOf(event.deviceId))} dev=${event.deviceId}")
        if (!isTap) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) onPadTap()
        return true
    }

    /** Returns true if this tap completed the triple and toggled the settings page. */
    private fun onPadTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastTapUp
        tapStreak = if (gap < MULTI_MS) tapStreak + 1 else 1
        lastTapUp = now
        // gap is the number that decides whether two taps are a double. If
        // real taps land just outside MULTI_MS the wearer gets two singles and
        // no cursor, so measure it rather than guess at the constant.
        Log.i(TAG, "pad tap gap=${gap}ms streak=$tapStreak (window=${MULTI_MS}ms) mouse=$mouseMode")
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
                // In mouse mode a single tap CLICKS. Stopping the agent moved
                // to the left pad, which frees the double tap for the pointer
                // — and cancel is better placed on its own arm anyway, since
                // it is the gesture you reach for when something is going
                // wrong and you do not want to think about counting taps.
                1 -> if (mouseMode) {
                    Log.i(TAG, "commit: click at cursor"); touchedMouse(); sendTap(false)
                } else {
                    Log.i(TAG, "commit: agent"); agent?.activate()
                }
                2 -> if (mouseMode) {
                    Log.i(TAG, "commit: cursor off"); exitMouseMode()
                } else {
                    Log.i(TAG, "commit: cursor ON"); enterMouseMode()
                }
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
        // Clamped to the PICTURE, not to the viewport. The letterbox bars are
        // not part of the phone's screen, so a pointer parked out there would
        // map to the nearest edge pixel and click something the wearer is not
        // pointing at.
        val r = videoRect()
        if (r != null) {
            cx = (cx + dx).coerceIn(r[0], r[0] + r[2] - 1f)
            cy = (cy + dy).coerceIn(r[1], r[1] + r[3] - 1f)
        } else {
            cx = (cx + dx).coerceIn(0f, VIEW_W - 1f)
            cy = (cy + dy).coerceIn(0f, VIEW_H - 1f)
        }
        placeCursor()
    }

    override fun onDestroy() {
        hud.stop(); ui.removeCallbacks(hideNotice); ui.removeCallbacks(mouseTimeout)
        agent?.destroy(); agent = null
        link?.stop(); discovery?.stop(); p2p?.stop(); super.onDestroy()
    }

    companion object {
        const val VIEW_W = 640
        const val VIEW_H = 480
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val SLOP = 3f
        private const val TAP_MS = 300L
        private const val MULTI_MS = 260L
        /** Mouse mode goes away after this long untouched. */
        private const val MOUSE_IDLE_MS = 4_000L
        /**
         * Pointer speed at 100%. This is the old shared Prefs.speed default,
         * so 100% reproduces exactly how the aim point used to travel and the
         * phone's setting reads as a change from a known feel rather than
         * from an arbitrary one. The default there is 80% — the pointer was
         * too jumpy at full rate.
         */
        private const val POINTER_BASE = 2.5f
        /** Bigger than this between two samples is a sensor glitch, not a finger. */
        private const val MAX_STEP = 55f
        /** Pointer this close to the picture's border starts pulling the page. */
        private const val EDGE_PULL_PX = 12f
        /** Least time between two edge pulls, so resting at the border is not a flood. */
        private const val EDGE_PULL_MS = 420L
        /** Pad-equivalent travel one edge pull is worth. */
        private const val EDGE_PULL_STEP = 60f
        /**
         * Keep synthesized swipes this far in from the screen edge. A
         * horizontal drag STARTING at the border is Android's back gesture,
         * not a pan.
         */
        private const val EDGE_SAFE = 0.12f
        /** Pad travel that counts as a scroll rather than a wobble. */
        private const val SCROLL_MIN = 14f

        /**
         * The temple pads. Verified on the reference pair with getevent:
         * cyttsp5 = /dev/input/event2 = the RIGHT arm, cyttsp6 =
         * /dev/input/event4 = the LEFT. Matched on the controller's name
         * because Android's device ids are not stable across a reboot.
         */
        private const val ARM_UNKNOWN = 0
        private const val ARM_RIGHT = 1
        private const val ARM_LEFT = 2

        private const val TAG = "X3MiraPad"
        private val TOKEN = Any()
    }
}

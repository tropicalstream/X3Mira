package com.x3mira.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.Date

/**
 * The landing screen — and everything the wearer sees whenever the mirror is
 * NOT live. Modelled on x3hub's dim readout: a neon time + battery line low
 * on the black, because that pair answers the two questions glasses get asked
 * while worn as glasses. Above it, the app's wordmark and one line of
 * connection state, so "where is my phone" is answered without a log.
 *
 * The whole point of a HUD here is that opening the icon should never dump the
 * wearer onto a dead black rectangle wondering whether anything is happening.
 * The moment frames arrive the video is laid over this and [stop] halts the
 * ticker, so it costs nothing during a session — it only breathes while it is
 * the thing actually on screen.
 */
class MirrorHud(context: Context) : View(context) {

    /** What the connection is doing, in the wearer's terms, not the socket's. */
    enum class Phase(val label: String) {
        SEARCHING("Looking for your phone"),
        CONNECTING("Connecting"),
        RECONNECTING("Reconnecting")
    }

    /** LANDING = full screen (not connected); OVERLAY = compact readout over live video. */
    enum class Mode { LANDING, OVERLAY }
    @Volatile var mode: Mode = Mode.LANDING
        private set
    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        invalidate()
    }

    private var phase = Phase.SEARCHING
    private var detail = ""      // the host address once one is known
    private var dots = 0         // animated ellipsis, 0..3

    // ── Paints ───────────────────────────────────────────────────────────
    // Neon is a bright near-white core inside a saturated halo, so every
    // glowing element is drawn twice: the wide faint coloured stroke first,
    // the near-white fill on top.

    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDFF6FF.toInt(); textAlign = Paint.Align.CENTER
        letterSpacing = 0.22f; isFakeBoldText = true
    }
    private val markGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9926C6FF.toInt(); textAlign = Paint.Align.CENTER
        letterSpacing = 0.22f; isFakeBoldText = true
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC9FE8FF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val timeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFDFF6FF.toInt(); textAlign = Paint.Align.CENTER
    }
    private val timeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8826C6FF.toInt(); textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    // The cast emblem: hot magenta, the suite accent, so the identity on the
    // landing screen matches the launcher icon the wearer just tapped.
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF2D9B.toInt(); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val arcGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FF2D9B.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    // The notification banner: the most recent phone notification, shown in
    // full at the top of the HUD, over a translucent card so it reads over the
    // live mirror as easily as over the black landing screen.
    private val notifText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEAF6FF.toInt(); textAlign = Paint.Align.LEFT
    }
    private val notifBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD40A0E1A.toInt() }
    private val notifAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2D9B.toInt() }
    /** Cyan bar: the glasses talking, vs magenta for the phone's notifications. */
    private val agentAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26C6FF.toInt() }
    /** AssistantFigure reconfigures and leaves this dirty — it is hers alone. */
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dateFmt = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
    @Volatile private var notif: String = ""

    // ── Wearer's HUD choices, pushed from the PHONE over the link ────────
    // notifLines: 0 = banner hidden, else max wrapped lines (1..3).
    // readoutMode: 0 = off, 1 = time, 2 = time+battery, 3 = date+time+battery.
    // fontScale: 0.8 / 1.0 / 1.2 from the phone's Small/Medium/Large.
    @Volatile var notifLines = 1; private set
    @Volatile var readoutMode = 3; private set
    @Volatile private var fontScale = 1f

    /** Applied when the phone pushes MAGIC_HUDCFG. Returns true if bands changed. */
    fun applyConfig(lines: Int, readout: Int, fontPct: Int): Boolean {
        val before = topBandPx() to bottomBandPx()
        notifLines = lines.coerceIn(0, 3)
        readoutMode = readout.coerceIn(0, 3)
        fontScale = (fontPct.coerceIn(50, 200)) / 100f
        invalidate()
        return before != (topBandPx() to bottomBandPx())
    }

    // ── Page agent: avatar + what she is saying ──────────────────────────
    // The avatar rides in the BOTTOM band beside the readout and the agent's
    // line rides in the TOP band, so both are inside space the video was
    // already fitted around — she can never sit on the mirror. The bands
    // grow to hold her, and MainActivity refits when these return true.

    @Volatile var agentState = AssistantState.IDLE; private set
    @Volatile private var agentLine: String = ""
    /** Avatar is shown whenever the agent is set up, so the wearer knows it is there. */
    @Volatile var agentEnabled = true; private set

    private var animPhase = 0f

    fun setAgentEnabled(on: Boolean): Boolean {
        if (agentEnabled == on) return false
        val before = bottomBandPx()
        agentEnabled = on
        invalidate()
        return before != bottomBandPx()
    }

    /** Returns true when the reserved bands changed and the video must refit. */
    fun setAgentState(state: Int): Boolean {
        if (agentState == state) return false
        val before = bottomBandPx()
        agentState = state
        invalidate()
        return before != bottomBandPx()
    }

    /** Returns true when the reserved bands changed and the video must refit. */
    fun setAgentLine(text: String): Boolean {
        if (agentLine == text) return false
        val before = topBandPx()
        agentLine = text
        invalidate()
        return before != topBandPx()
    }

    /** Her box in the bottom band — square, band-height, right-aligned. */
    private fun avatarPx(h: Float): Float =
        if (!agentEnabled) 0f else readoutTextPx(h) * 1.9f

    /**
     * The bands the HUD owns, in view pixels. The video is fitted BETWEEN
     * them by MainActivity, so HUD text and the mirror can never overlap —
     * in either phone orientation, because the bands span the full width.
     */
    fun topBandPx(): Int {
        val h = (if (height > 0) height else 480).toFloat()
        val size = notifTextPx(h)
        // The agent's line lives in the top band too. It is allowed the same
        // wrapped height as a 3-line banner so a two-sentence answer fits
        // without ever spilling onto the picture.
        val agentPx =
            if (agentLine.isEmpty()) 0
            else (h * 0.02f + size * (1.30f * agentTextLines(h) + 0.7f)).toInt()
        val notifPx =
            if (notifLines <= 0) 0
            else (h * 0.02f + size * (1.30f * notifLines + 0.7f)).toInt()
        return maxOf(agentPx, notifPx)
    }
    fun bottomBandPx(): Int {
        val h = (if (height > 0) height else 480).toFloat()
        val readoutPx = if (readoutMode <= 0) 0f else readoutTextPx(h) * 2.1f
        // She sets the floor when she is on: the band is never shorter than
        // the face it has to hold, even with the readout switched off.
        val avatarPx = if (agentEnabled) avatarPx(h) * 1.12f else 0f
        return maxOf(readoutPx, avatarPx).toInt()
    }

    /** How many wrapped lines the agent's current line needs (1..3). */
    private fun agentTextLines(h: Float): Int {
        if (agentLine.isEmpty()) return 0
        val w = (if (width > 0) width else 640).toFloat()
        val size = notifTextPx(h)
        notifText.textSize = size
        val pad = h * 0.02f
        val boxW = w * 0.94f
        val maxTextW = boxW - pad * 2 - size * 0.8f
        return wrap(agentLine, notifText, maxTextW).size.coerceIn(1, 3)
    }
    private fun notifTextPx(h: Float) = h * 0.028f * fontScale
    private fun readoutTextPx(h: Float) = h * 0.0304f * fontScale

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // One beat a second: advances the ellipsis and re-reads the clock.
            // Nothing pulses faster, so an idle HUD is close to free.
            dots = (dots + 1) % 4
            invalidate()
            ui.postDelayed(this, 1000L)
        }
    }

    fun setState(phase: Phase, detail: String) {
        this.phase = phase
        this.detail = detail
        invalidate()
    }

    /** The most recent phone notification, shown in full at the top of the HUD. */
    fun setNotif(text: String) {
        if (text == notif) return
        notif = text
        postInvalidate()
    }

    /** Called when the mirror goes live: video covers us, so stop breathing. */
    fun stop() { ui.removeCallbacks(tick) }
    fun startTicking() { ui.removeCallbacks(tick); ui.post(tick) }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startTicking() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (mode == Mode.OVERLAY) {
            // Live mirror: no black fill so the video shows through — just the
            // notification banner at the top and the readout at the bottom.
            // The agent's line takes the top band when she has something to
            // say, because it is the thing the wearer just asked for.
            if (agentLine.isNotEmpty()) drawAgentLine(canvas, w, h) else drawNotif(canvas, w, h)
            drawReadout(canvas, w, h, compact = true)
            drawAvatar(canvas, w, h)
            return
        }

        canvas.drawColor(Color.BLACK)
        drawCastEmblem(canvas, w * 0.5f, h * 0.30f, h * 0.085f)

        // Wordmark, centred a touch above the middle.
        val markSize = h * 0.115f
        markFill.textSize = markSize
        markGlow.textSize = markSize
        markGlow.strokeWidth = markSize * 0.10f
        val markBaseline = h * 0.52f
        canvas.drawText(WORDMARK, w * 0.5f, markBaseline, markGlow)
        canvas.drawText(WORDMARK, w * 0.5f, markBaseline, markFill)

        // Connection state, one line, with an animated ellipsis so a wearer
        // can tell a live search from a hung one.
        statusPaint.textSize = h * 0.040f
        val ell = ".".repeat(dots)
        val host = detail.takeIf { it.isNotEmpty() && phase != Phase.SEARCHING }?.let { "  ·  $it" } ?: ""
        canvas.drawText(phase.label + ell + host, w * 0.5f, markBaseline + h * 0.085f, statusPaint)

        if (agentLine.isNotEmpty()) drawAgentLine(canvas, w, h) else drawNotif(canvas, w, h)
        drawReadout(canvas, w, h, compact = false)
        drawAvatar(canvas, w, h)
    }

    /**
     * The agent's line: same card geometry as the notification banner so it
     * occupies band space that already exists, but cyan-barred rather than
     * magenta — the wearer can tell "the glasses are telling me something"
     * from "the phone is telling me something" without reading either.
     */
    private fun drawAgentLine(canvas: Canvas, w: Float, h: Float) {
        val size = notifTextPx(h)
        notifText.textSize = size
        val pad = h * 0.02f
        val boxW = w * 0.94f
        val boxLeft = (w - boxW) / 2f
        val textLeft = boxLeft + pad + size * 0.4f
        val maxTextW = boxLeft + boxW - pad - textLeft
        val lines = wrap(agentLine, notifText, maxTextW).let { all ->
            if (all.size <= 3) all
            else all.take(3).toMutableList().also {
                it[2] = ellipsize(it[2] + "…", notifText, maxTextW)
            }
        }
        val lineH = size * 1.30f
        val boxTop = h * 0.02f
        val boxH = lineH * lines.size + size * 0.6f
        val box = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(box, size * 0.4f, size * 0.4f, notifBg)
        canvas.drawRect(boxLeft, boxTop, boxLeft + size * 0.18f, boxTop + boxH, agentAccent)
        var y = boxTop + size * 0.4f + size * 0.9f
        for (ln in lines) {
            canvas.drawText(ln, textLeft, y, notifText)
            y += lineH
        }
    }

    /**
     * The assistant, in the bottom band at the right — x3hub's face, at the
     * end of the readout the way she sits there. She is inside the band the
     * video was fitted around, so she never covers the mirror; the band grows
     * for her when the readout alone would be shorter than her face.
     */
    private fun drawAvatar(canvas: Canvas, w: Float, h: Float) {
        if (!agentEnabled) return
        val size = avatarPx(h)
        if (size <= 0f) return
        val band = bottomBandPx().toFloat()
        // Beside the readout, not out on the rim. She belongs to that line —
        // x3hub sits her at the end of it — and parked against the far edge
        // she read as a separate widget with a gulf of black between them.
        // Measured, not guessed, so she follows the text as it changes length
        // and as the wearer changes readout mode or font size.
        val cx = if (readoutMode > 0) {
            // drawReadout has already left timeFill at the readout's size for
            // this frame, but set it anyway so ordering can never bite.
            timeFill.textSize = readoutTextPx(h)
            val textHalf = timeFill.measureText(readoutLine()) * 0.5f
            (w * 0.5f + textHalf + size * 0.62f).coerceAtMost(w - size * 0.55f)
        } else {
            // Nothing to sit beside: centre her where the line would have been.
            w * 0.5f
        }
        val cy = h - band * 0.5f
        // Idle sits dim; an active state brightens her, so a glance at the
        // corner answers "is she listening / thinking / talking".
        val brightness = if (agentState == AssistantState.IDLE) 0.35f else 0.85f
        animPhase = (animPhase + 0.05f) % 1f
        // CLIPPED TO HER BAND. AssistantFigure's ink runs past the nominal
        // size — she has a keyboard below the face and thinking-dots above —
        // so a band sized from the text alone would let her spill onto the
        // mirror. The clip makes "never covers the picture" a property of the
        // canvas rather than a sum of glyph measurements that could drift.
        canvas.save()
        canvas.clipRect(0f, h - band, w, h)
        runCatching {
            AssistantFigure.draw(
                canvas = canvas,
                cx = cx,
                cy = cy,
                size = size,
                state = agentState,
                phase = animPhase,
                level = 0f,
                agentLevel = 0f,
                paint = avatarPaint,
                brightness = brightness
            )
        }
        canvas.restore()
    }

    /**
     * The readout in the BOTTOM band: what it shows is the wearer's choice,
     * pushed from the phone — time alone, time+battery, or date+time+battery —
     * at their chosen text size. Drawn centred inside the band the video was
     * fitted around, so it can never sit on the mirror.
     */
    /** The readout as it will be drawn — one source of truth, so the avatar
     *  can measure the very string the wearer is looking at. */
    private fun readoutLine(): String {
        val now = Date()
        val time = android.text.format.DateFormat.getTimeFormat(context).format(now)
        val batt = batteryPercent()?.let { "$it%" }
        return when (readoutMode) {
            1 -> time
            2 -> if (batt != null) "$time  ·  $batt" else time
            else -> "${dateFmt.format(now)}  ·  $time" + (batt?.let { "  ·  $it" } ?: "")
        }
    }

    private fun drawReadout(canvas: Canvas, w: Float, h: Float, compact: Boolean) {
        if (readoutMode <= 0) return
        val line = readoutLine()
        val size = if (compact) readoutTextPx(h) else h * 0.04f * fontScale
        timeFill.textSize = size
        timeGlow.textSize = size
        timeGlow.strokeWidth = size * (if (compact) 0.10f else 0.12f)
        val baseline = if (compact) h - bottomBandPx() * 0.5f + size * 0.36f
        else h - h * 0.06f
        val fa = timeFill.alpha; val ga = timeGlow.alpha
        if (compact) { timeFill.alpha = 170; timeGlow.alpha = 100 }
        canvas.drawText(line, w * 0.5f, baseline, timeGlow)
        canvas.drawText(line, w * 0.5f, baseline, timeFill)
        timeFill.alpha = fa; timeGlow.alpha = ga
    }

    /**
     * The most recent phone notification in the TOP band, wrapped to the
     * wearer's chosen number of lines (their font size too), over a
     * translucent card with a magenta bar. If it doesn't fit the last line
     * ends in an ellipsis; the card never grows past its band, so it never
     * touches the mirror below.
     */
    private fun drawNotif(canvas: Canvas, w: Float, h: Float) {
        val raw = notif
        if (raw.isEmpty() || notifLines <= 0) return
        val text = raw.replace("\n", "   ·   ")
        val size = notifTextPx(h)
        notifText.textSize = size
        val pad = h * 0.02f
        val boxW = w * 0.94f
        val boxLeft = (w - boxW) / 2f
        val textLeft = boxLeft + pad + size * 0.4f
        val maxTextW = boxLeft + boxW - pad - textLeft
        var lines = wrap(text, notifText, maxTextW)
        if (lines.size > notifLines) {
            lines = lines.take(notifLines).toMutableList().also {
                it[it.lastIndex] = ellipsize(it.last() + "…", notifText, maxTextW)
            }
        }
        val lineH = size * 1.30f
        val boxTop = h * 0.02f
        val boxH = lineH * lines.size + size * 0.6f
        val box = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(box, size * 0.4f, size * 0.4f, notifBg)
        canvas.drawRect(boxLeft, boxTop, boxLeft + size * 0.18f, boxTop + boxH, notifAccent)
        var y = boxTop + size * 0.4f + size * 0.9f
        for (ln in lines) {
            canvas.drawText(ln, textLeft, y, notifText)
            y += lineH
        }
    }

    /** Greedy word-wrap to a pixel width, hard-breaking any word too long to fit. */
    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.split(" ")) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(trial) <= maxW) {
                cur = StringBuilder(trial)
            } else {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur = StringBuilder() }
                if (paint.measureText(word) <= maxW) {
                    cur = StringBuilder(word)
                } else {
                    var seg = StringBuilder()
                    for (ch in word) {
                        if (seg.isNotEmpty() && paint.measureText("$seg$ch") > maxW) {
                            out.add(seg.toString()); seg = StringBuilder()
                        }
                        seg.append(ch)
                    }
                    cur = seg
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return if (out.isEmpty()) listOf("") else out
    }

    /** Trim to fit one line at [paint]'s size, appending an ellipsis if cut. */
    private fun ellipsize(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxW) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    /**
     * The broadcast/cast glyph: a source dot at lower-left with three arcs
     * fanning up and to the right. Universal shorthand for "throwing a screen
     * somewhere", and the same mark the launcher icon carries.
     */
    private fun drawCastEmblem(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val ox = cx - r * 0.8f          // the source corner
        val oy = cy + r * 0.9f
        arcPaint.strokeWidth = r * 0.16f
        arcGlow.strokeWidth = r * 0.42f
        canvas.drawCircle(ox, oy, r * 0.14f, arcPaint)
        for (i in 1..3) {
            val rad = r * (0.55f * i)
            val box = RectF(ox - rad, oy - rad, ox + rad, oy + rad)
            // Sweep the top-right quadrant: from straight up (-90°) to the right (0°).
            canvas.drawArc(box, -90f, 90f, false, arcGlow)
            canvas.drawArc(box, -90f, 90f, false, arcPaint)
        }
    }

    private fun batteryPercent(): Int? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100f / scale).toInt()
    }

    companion object { private const val WORDMARK = "X3MIRA" }
}

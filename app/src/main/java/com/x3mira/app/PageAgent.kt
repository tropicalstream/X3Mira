package com.x3mira.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * x3hub's page agent, rebuilt for a mirror instead of a browser.
 *
 * WHY IT IS NOT A PORT. x3hub's PageAgentController injects a JS bundle into
 * a WebView, reads the DOM and clicks elements by selector. X3Mira has no
 * WebView — what the wearer is looking at is decoded H.264 of the PHONE'S
 * screen, and the DOM lives in an app on another device. So the agent keeps
 * the SHAPE of x3hub's (spoken task in, LLM decides, answer spoken back,
 * status on the HUD) and swaps the eye: instead of the DOM it sends the
 * frame the wearer is actually looking at.
 *
 * That trade buys something the DOM agent never had: this works on ANY app
 * on the phone — a map, a photo, a receipt, a game — not only web pages.
 * What it gives up is exact element targeting, which is why acting is
 * deliberately limited to scrolling here and the wearer keeps the pad.
 *
 * PRIVACY. The frame is the wearer's own phone screen and goes to the model
 * only on an explicit tap, one still image per question. Nothing is sent
 * while the agent is idle, and nothing is recorded until the wearer asks.
 * The alternative design — having the phone read its own screen through the
 * accessibility service — is more accurate on text and is deliberately NOT
 * the default, because that service is documented as one that "must not be
 * able to read" the screen.
 *
 * THREADING. getBitmap() must happen on the UI thread (the caller supplies
 * it); JPEG encoding, transcription and the model call all run on a worker;
 * every callback is posted back to main. Nothing here blocks the render.
 */
class PageAgent(
    private val context: Context,
    /** Called on the UI thread; returns the current mirror frame, if any. */
    private val frameProvider: () -> Bitmap?,
    /** AssistantState bits for the avatar. */
    private val onState: (Int) -> Unit,
    /** One line for the HUD — question echo, status, or the answer. */
    private val onText: (String) -> Unit,
    /**
     * The agent DOING something, not just saying it. Coordinates are a
     * fraction of the phone's screen (0..1), which is exactly what the return
     * channel wants, because the picture the model looked at IS the whole
     * phone screen. [dir] is -1 for scroll up, +1 for scroll down.
     */
    private val onTap: (fx: Float, fy: Float) -> Unit = { _, _ -> },
    private val onScroll: (dir: Int) -> Unit = { },
    /** Open a web address on the phone — the one thing gestures cannot do. */
    private val onOpenUrl: (String) -> Unit = { },
    /** Type into the phone's focused field. Only offered when the wearer allows it. */
    private val onType: (text: String, submit: Boolean) -> Unit = { _, _ -> },
    /** Launch an installed app by name — "open Spotify" is not a URL. */
    private val onOpenApp: (String) -> Unit = { },
    /**
     * A TINY copy of the mirror, for "has the screen changed yet?".
     *
     * Deliberately not [frameProvider]: that returns the decoder's full
     * 720x1536 so the model can read labels, and polling one of those every
     * couple of hundred milliseconds would copy megabytes to answer a yes/no
     * question.
     */
    private val probeProvider: () -> Bitmap? = { null }
) {
    /**
     * Whether "type" is in the model's vocabulary at all.
     *
     * Set from the phone's setting rather than always offered, because an
     * action the phone will refuse is worse than one that does not exist: the
     * model spends a hop on it, sees nothing change, and — reasonably — tries
     * again.
     */
    @Volatile var typingEnabled = false
    private val main = Handler(Looper.getMainLooper())
    private val recorder = AgentVoice.Recorder(context)

    @Volatile private var busy = false
    @Volatile private var listening = false
    /** Bumped on exit/new errand; a hop loop whose generation moved on stops. */
    @Volatile private var generation = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    /** Auto-stop: a spoken question is a sentence, not a monologue. */
    private val autoStop = Runnable { if (listening) finishListening() }

    val isActive: Boolean get() = listening || busy

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    /**
     * The pad tap. First tap opens the mic; a further tap while listening
     * sends early rather than making the wearer wait out the timer.
     */
    fun activate() {
        if (busy) { onText("Working on the last one…"); return }
        if (listening) { finishListening(); return }
        if (!AgentVoice.hasKey(context)) {
            onText("No speech key set — see X3Mira settings on the phone")
            onState(AssistantState.IDLE)
            return
        }
        if (!recorder.start()) {
            onText("Microphone unavailable")
            onState(AssistantState.IDLE)
            return
        }
        listening = true
        onState(AssistantState.LISTENING)
        // Not "ask about this screen": that was true when the agent could only
        // answer, and it stopped being true once it could press things, scroll
        // and open a site or app of its own. A prompt that undersells what it
        // will do means the wearer never tries the half that matters.
        onText("Listening… ask or give me a task")
        main.postDelayed(autoStop, AgentVoice.MAX_RECORD_MS)
    }

    /** Double tap: stop everything and go quiet, whatever stage it is at. */
    fun exit() {
        main.removeCallbacks(autoStop)
        main.removeCallbacks(clearLine)
        generation++            // abandons any hop loop still in flight
        if (listening) { recorder.cancel(); listening = false }
        busy = false
        runCatching { tts?.stop() }
        onState(AssistantState.IDLE)
        onText("")
    }

    fun destroy() {
        exit()
        runCatching { tts?.shutdown() }
        tts = null
    }

    // ── the loop ────────────────────────────────────────────────────────

    private fun finishListening() {
        main.removeCallbacks(autoStop)
        listening = false
        val audio = recorder.stop()
        if (audio == null) {
            onText("Didn't catch that")
            onState(AssistantState.IDLE)
            return
        }
        busy = true
        generation++
        main.removeCallbacks(clearLine)
        onState(AssistantState.THINKING)
        onText("Transcribing…")
        AgentVoice.transcribe(context, audio) { text, err ->
            runCatching { audio.delete() }
            val q = text?.trim().orEmpty()
            if (q.isEmpty()) {
                busy = false
                onText(err ?: "Didn't catch that")
                onState(AssistantState.IDLE)
                return@transcribe
            }
            // The frame must be grabbed on the UI thread, and as late as
            // possible: the wearer may have scrolled while speaking, and the
            // screen they mean is the one in front of them NOW.
            main.post {
                val shot = runCatching { frameProvider() }.getOrNull()
                onText(q)
                onState(AssistantState.THINKING or AssistantState.AGENT)
                thread(name = "x3mira-agent", isDaemon = true) { ask(q, shot) }
            }
        }
    }

    /**
     * The errand loop — x3hub's "hops", which is the part that makes this an
     * agent rather than a describer.
     *
     * "Scroll down and look for Spider-Man" is not one action, it is however
     * many it takes: scroll, look, scroll, look, and then say what was found.
     * The first cut performed exactly one action per spoken request and
     * stopped, which reads as giving up half way. So each hop acts, lets the
     * screen settle, takes a FRESH frame, and asks again with the same errand
     * until the model reports it is done (action "none") or the budget runs
     * out. Every hop is a new look at reality, so a scroll that did nothing
     * or a page that moved unexpectedly is self-correcting.
     */
    private fun ask(question: String, frame: Bitmap?) {
        val key = ApiKeyStore.resolve(context).orEmpty().trim()
        if (key.isBlank()) {
            finish("No Gemini key set — see X3Mira settings on the phone", ok = false)
            return
        }
        val mine = generation
        tapped.clear()                     // a fresh errand may press anywhere
        opened.clear()                     // ...and revisit a site it visited last time
        typed.clear()
        var shot = frame
        var hop = 0
        while (hop < MAX_HOPS) {
            if (mine != generation) { runCatching { shot?.recycle() }; return }   // exited
            val obj = askOnce(key, question, shot, hop)
            runCatching { shot?.recycle() }
            shot = null
            if (mine != generation) return
            if (obj == null) {
                // Was invisible before: a model turn that failed and one that
                // politely declined both ended the errand through finish()
                // with nothing logged, so "the agent did nothing" looked
                // identical to "the agent never ran".
                Log.w(TAG, "hop $hop FAILED: $lastFail")
                finish(lastFail, ok = false); return
            }
            val say = obj.optString("say").trim().ifEmpty { "Done." }
            val action = obj.optString("action")
            Log.i(TAG, "hop $hop -> action='$action' say='${say.take(90)}'")
            if (action == "none" || action.isEmpty()) { finish(say, ok = true); return }
            // Fingerprint BEFORE acting: "has it changed" needs the thing it
            // changed from, and after the action it is already too late to ask.
            val beforeSig = probe()
            when (perform(action, obj, say)) {
                Act.SETTLED -> { finish(say, ok = true); return }
                Act.CANNOT -> { finish(say, ok = false); return }
                Act.ACTED -> Unit
            }
            hop++
            if (hop >= MAX_HOPS) break
            // Let the phone actually move before looking again, then take a
            // fresh frame — the whole point of a hop. Waited out by WATCHING
            // rather than by sleeping a guess: a page load or an app cold start
            // is a far bigger event than a tap, and one constant cannot be
            // right for both without being wrong for one.
            awaitSettled(
                beforeSig,
                if (action == "open_url" || action == "open_app") LOAD_MS else TRANSITION_MS
            )
            if (mine != generation) return
            shot = grabFrame()
            if (shot == null) { finish("I lost sight of the screen.", ok = false); return }
        }
        finish("I tried a few steps and could not finish that.", ok = false)
    }

    /** Blocking frame grab — getBitmap must run on the UI thread. */
    private fun grabFrame(): Bitmap? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Bitmap? = null
        main.post {
            out = runCatching { frameProvider() }.getOrNull()
            latch.countDown()
        }
        return if (latch.await(2, TimeUnit.SECONDS)) out else null
    }

    /** A cheap fingerprint of what is on screen. Equal means "looks the same". */
    private fun probe(): Long? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: Bitmap? = null
        main.post {
            out = runCatching { probeProvider() }.getOrNull()
            latch.countDown()
        }
        if (!latch.await(1, TimeUnit.SECONDS)) return null
        val bmp = out ?: return null
        var h = 1125899906842597L
        runCatching {
            for (y in 0 until bmp.height step 2) {
                for (x in 0 until bmp.width step 2) h = h * 31 + bmp.getPixel(x, y)
            }
        }
        runCatching { bmp.recycle() }
        return h
    }

    /**
     * Wait for the phone to FINISH moving, rather than sleeping a fixed guess.
     *
     * The fixed sleep was the cause of the worst class of failure this agent
     * had. 900ms is right for a scroll and far too short for a screen
     * transition — tapping a search bar and looking that soon returns the
     * screen from BEFORE the tap, so the model concludes its tap missed and
     * taps again, and the repeat guard then ends the errand as "already done".
     * The wearer sees an agent that gives up on step two of three, and nothing
     * in the log says why, because from the agent's side every action
     * succeeded.
     *
     * So: wait for the picture to change, then wait for it to hold still. The
     * hold matters as much as the change — a screen mid-transition is a half
     * drawn menu, and asking a vision model to act on that is how it presses
     * something that is still sliding into place.
     *
     * Capped, and a cap that expires is not an error: some actions genuinely
     * change nothing visible, and the errand should carry on and let the model
     * judge from the frame.
     */
    private fun awaitSettled(before: Long?, capMs: Long) {
        if (before == null) { Thread.sleep(SETTLE_MS); return }
        val start = SystemClock.uptimeMillis()
        var changed = false
        var lastSig = before
        var stillSince = 0L
        while (SystemClock.uptimeMillis() - start < capMs) {
            Thread.sleep(PROBE_MS)
            val sig = probe() ?: continue
            if (!changed) {
                if (sig != before) { changed = true; lastSig = sig; stillSince = SystemClock.uptimeMillis() }
                continue
            }
            if (sig != lastSig) { lastSig = sig; stillSince = SystemClock.uptimeMillis(); continue }
            if (SystemClock.uptimeMillis() - stillSince >= STILL_MS) {
                Log.i(TAG, "settled after ${SystemClock.uptimeMillis() - start}ms")
                return
            }
        }
        Log.i(TAG, "settle cap hit after ${capMs}ms (changed=$changed)")
    }

    /**
     * Every place this errand has pressed, so it cannot press one twice.
     *
     * Cycling controls are the trap: a row that reads "Medium" becomes
     * "Large" the moment it is tapped, the next hop sees a screen that no
     * longer matches what was asked for, and the model presses again — four
     * times in a row, in the case that prompted this. Pressing the SAME place
     * twice is never progress, so the second attempt ends the errand instead.
     */
    private val tapped = ArrayList<FloatArray>()

    /** Sites already opened this errand, compared by [siteOf]. */
    private val opened = HashSet<String>()

    /** Text already typed this errand. */
    private val typed = HashSet<String>()

    /**
     * The identity of a destination, for "have I already been here?".
     * The model does not spell an address the same way twice — youtube.com,
     * https://www.youtube.com and https://youtube.com/ are one site, and a
     * guard that compared raw strings would miss the loop it exists to catch.
     */
    private fun siteOf(url: String): String = url.trim().lowercase()
        .substringAfter("://")
        .removePrefix("www.")
        .trimEnd('/')

    /** ACTED = carry on hopping; SETTLED = the errand is finished; CANNOT = give up. */
    private enum class Act { ACTED, SETTLED, CANNOT }

    private fun perform(action: String, obj: JSONObject, say: String): Act = when (action) {
        "tap" -> {
            val box = obj.optJSONArray("box_2d")
            if (box == null || box.length() < 4) Act.CANNOT
            else {
                val ymin = box.optInt(0); val xmin = box.optInt(1)
                val ymax = box.optInt(2); val xmax = box.optInt(3)
                val fx = ((xmin + xmax) / 2f) / 1000f
                val fy = ((ymin + ymax) / 2f) / 1000f
                // ANY spot already pressed this errand, not merely the last
                // one: an agent that cannot tell two cycling rows apart will
                // ping-pong between them, and comparing only against the
                // previous tap never catches that.
                val repeat = tapped.any {
                    kotlin.math.abs(fx - it[0]) < SAME_SPOT &&
                        kotlin.math.abs(fy - it[1]) < SAME_SPOT
                }
                when {
                    fx !in 0f..1f || fy !in 0f..1f -> Act.CANNOT
                    repeat -> {
                        Log.i(TAG, "hop tap repeated at ($fx,$fy) — errand already done")
                        Act.SETTLED
                    }
                    else -> {
                        Log.i(TAG, "hop tap box=[$ymin,$xmin,$ymax,$xmax] -> ($fx,$fy)")
                        tapped.add(floatArrayOf(fx, fy))
                        main.post { onText(say); onTap(fx, fy) }
                        Act.ACTED
                    }
                }
            }
        }
        "scroll_down" -> { Log.i(TAG, "hop scroll down: $say"); main.post { onText(say); onScroll(1) }; Act.ACTED }
        "scroll_up" -> { Log.i(TAG, "hop scroll up: $say"); main.post { onText(say); onScroll(-1) }; Act.ACTED }
        "type" -> {
            val text = obj.optString("text")
            if (text.isEmpty()) Act.CANNOT
            else {
                // Typing the SAME thing twice means the first attempt did not
                // land — no field focused, or the setting is off — and asking
                // again will fail identically. Same reasoning as the tap and
                // open guards: repetition is never progress.
                val submit = obj.optBoolean("submit", true)
                if (!typed.add(text)) {
                    Log.i(TAG, "hop type repeated — already sent that text")
                    Act.SETTLED
                } else {
                    Log.i(TAG, "hop type ${text.length} chars submit=$submit")
                    main.post { onText(say); onType(text, submit) }
                    Act.ACTED
                }
            }
        }
        "open_app" -> {
            val app = obj.optString("app").trim()
            if (app.isEmpty()) Act.CANNOT
            else if (!opened.add("app:" + app.lowercase())) {
                Log.i(TAG, "hop open_app repeated $app — already there")
                Act.SETTLED
            } else {
                Log.i(TAG, "hop open_app $app")
                tapped.clear()          // new app, new screen, new targets
                main.post { onText(say); onOpenApp(app) }
                Act.ACTED
            }
        }
        "open_url" -> {
            val url = obj.optString("url").trim()
            val same = siteOf(url)
            when {
                url.isEmpty() -> Act.CANNOT
                // The same-spot rule, for navigation. Asked again while the
                // browser is still coming up, the model cannot see that it
                // already succeeded and simply answers "open it" a second
                // time — which relaunches the page and puts it back where it
                // started, forever. Opening a site you have already opened is
                // never progress, so treat it as arrival and stop.
                opened.contains(same) -> {
                    Log.i(TAG, "hop open repeated $url — already there")
                    Act.SETTLED
                }
                else -> {
                    Log.i(TAG, "hop open $url")
                    opened.add(same)
                    // A new page is a new screen: a coordinate that was already
                    // pressed here means nothing over there, so the same-spot
                    // guard starts again rather than blocking a fresh target.
                    tapped.clear()
                    main.post { onText(say); onOpenUrl(url) }
                    Act.ACTED
                }
            }
        }
        else -> Act.CANNOT
    }

    @Volatile private var lastFail = "I couldn't reach the model."

    /** One model turn. Null on failure; [lastFail] carries why. */
    private fun askOnce(key: String, question: String, frame: Bitmap?, hop: Int): JSONObject? {
        val jpeg = frame?.let { bmp ->
            // A uniform frame means a protected surface (DRM video) or a
            // decoder that has not produced anything yet. Send nothing rather
            // than let the model narrate a black rectangle with confidence.
            if (isUniform(bmp)) {
                Log.w(TAG, "frame is uniform — asking without a picture")
                null
            } else runCatching {
                ByteArrayOutputStream().use { out ->
                    // 85% of a half-native frame: small enough to be quick on
                    // a phone hotspot, sharp enough to read UI labels.
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            }.getOrNull()
        }
        val parts = org.json.JSONArray()
        val hopNote = if (hop == 0) "" else
            "\n\nYou have already taken $hop step(s) on this errand and this image is " +
            "the screen AS IT IS NOW. If the goal is reached, or you can now answer, use " +
            "action \"none\" and give the answer in say. Otherwise take the next step."
        val typingNote = if (!typingEnabled) "" else
            "\n\nYou CAN type. To search or fill a box: \"tap\" it first so it has the " +
            "cursor, then on the next turn use action \"type\" with the words in text — " +
            "it goes into whatever field is focused, so the tap has to land first. Set " +
            "submit true to press Search/Go afterwards, which is almost always what a " +
            "search wants; false only if there is another field to fill first. Type the " +
            "whole phrase at once. Never tap letters on the on-screen keyboard: that is " +
            "one turn per character and it will not finish."
        parts.put(
            JSONObject().put("text", PROMPT + typingNote + hopNote + "\n\nErrand: " + question)
        )
        if (jpeg != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        // A SCHEMA, not prose. Asked in plain text the model answers "you can
        // click that", which is useless to someone whose hands are not on the
        // phone — the whole point is that the agent does it. Forcing a typed
        // response makes acting the default shape of an answer.
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("say", JSONObject().put("type", "STRING"))
                    .put(
                        "action", JSONObject().put("type", "STRING")
                            .put("enum", org.json.JSONArray()
                                .put("none").put("tap").put("scroll_down")
                                .put("scroll_up").put("open_url").put("open_app")
                                .apply { if (typingEnabled) put("type") })
                    )
                    .put("app", JSONObject().put("type", "STRING"))
                    // Going to a named site is not a gesture. Without this the
                    // model does the only thing its vocabulary allows — taps
                    // the address bar — and then stalls in front of a keyboard
                    // it has no way to use.
                    .put("url", JSONObject().put("type", "STRING"))
                    .put("text", JSONObject().put("type", "STRING"))
                    .put("submit", JSONObject().put("type", "BOOLEAN"))
                    // box_2d, in Gemini's own detection convention:
                    // [ymin, xmin, ymax, xmax] on a 0-1000 grid. The field
                    // NAME and the y-first order matter — asked for a generic
                    // "x" and "y" the model has no grounding to hang the
                    // request on and simply answers 500,500, which is the
                    // middle of the screen and taps whatever happens to be
                    // there. With box_2d it actually locates the element.
                    .put(
                        "box_2d", JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject().put("type", "INTEGER"))
                    )
            )
            .put("required", org.json.JSONArray().put("say").put("action"))
        val body = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    // 400 was set when the reply was say + action + box_2d.
                    // Every action added since put another optional field in
                    // the schema, and the model fills them whether or not the
                    // chosen action wants them — so a "type" answer arrived
                    // carrying a whole invented url and ran out of budget
                    // mid-string. A truncated reply is not partial, it is
                    // unparseable: it fell to the raw-text fallback and the
                    // wearer was told their answer came back garbled.
                    .put("maxOutputTokens", 1024)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
            )
            .toString()

        // Walk the model list rather than trusting one. Full Flash answers
        // best but returns 503 "high demand" often enough that a wearer would
        // meet it on their first question; the lite models are the ones that
        // are actually always there. x3hub learned the same thing.
        var answer: String? = null
        lastFail = "I couldn't reach the model."
        for (model in MODELS) {
            val outcome = runCatching {
                // Through LinkNet, which prefers to hand this to the PHONE:
                // the frame is a JPEG of the wearer's screen and the phone has
                // the connection that is actually reliable outdoors. No
                // credential goes over the link — the phone attaches its own,
                // so the glasses never carry a usable key.
                LinkNet.execute(
                    url = "$GEMINI_BASE/models/$model:generateContent",
                    method = "POST",
                    headers = mapOf("content-type" to "application/json"),
                    body = body.toString().toByteArray(Charsets.UTF_8)
                ).let { resp ->
                    val text = resp.text()
                    if (!resp.ok) {
                        Log.w(TAG, "vision $model HTTP ${resp.code} via=${resp.viaPhone}: ${text.take(200)}")
                        // Busy or rate-limited is worth another model; a 400 or
                        // a 403 is our own request and retrying cannot help.
                        lastFail = when (resp.code) {
                            503, 429, 500, 502, 504 -> "The model is busy — try again."
                            401, 403 -> "The API key was rejected."
                            else -> "The model returned an error."
                        }
                        return@let null
                    }
                    // EVERY part, joined — not parts[0]. Gemini is free to
                    // split one reply across several parts, and when it does
                    // the first is a fragment like {"say": . That parses as
                    // nothing, falls through to the raw-text fallback below,
                    // and the wearer hears their agent read out a piece of its
                    // own JSON before the errand quietly stops.
                    JSONObject(text)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")
                        ?.let { arr ->
                            buildString {
                                for (i in 0 until arr.length()) {
                                    append(arr.optJSONObject(i)?.optString("text").orEmpty())
                                }
                            }
                        }?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.getOrElse { Log.w(TAG, "vision $model failed: ${it.message}"); null }
            if (outcome != null) { answer = outcome; break }
        }

        if (answer == null) return null
        runCatching { JSONObject(answer) }.getOrNull()?.let { return it }
        // Not parseable. If it STARTS like JSON it is a broken schema reply,
        // not prose, and reading it out would speak braces and quote marks at
        // someone wearing the thing. Say something a person can act on and log
        // the real text for whoever has to debug it.
        if (answer.trimStart().startsWith("{")) {
            Log.w(TAG, "unparseable schema reply: ${answer.take(160)}")
            return JSONObject()
                .put("say", "The model's reply came back garbled — ask me again.")
                .put("action", "none")
        }
        // Genuine prose: treat the whole thing as the answer.
        return JSONObject().put("say", answer).put("action", "none")
    }

    /** Cheap sample grid: true when every probe pixel is the same colour. */
    private fun isUniform(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val first = bmp.getPixel(w / 2, h / 2)
        for (i in 1..6) {
            for (j in 1..6) {
                val x = (w * i / 7).coerceIn(0, w - 1)
                val y = (h * j / 7).coerceIn(0, h - 1)
                if (bmp.getPixel(x, y) != first) return false
            }
        }
        return true
    }

    /**
     * Clears the agent's line so the band goes back to the notification
     * banner. Without this the last thing she said stayed on the HUD for the
     * rest of the session, which reads as the agent being stuck mid-errand
     * long after it finished.
     */
    private val clearLine = Runnable { onText("") }

    private fun finish(message: String, ok: Boolean) {
        main.post {
            busy = false
            onText(message)
            onState(if (ok) AssistantState.TALKING else AssistantState.IDLE)
            // Long enough to read and hear, then the band is the phone's again.
            main.removeCallbacks(clearLine)
            main.postDelayed(clearLine, LINE_MS)
            if (ok && ttsReady) {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTT)
                // No progress listener wiring for one utterance: clear the
                // talking state on a duration estimate instead, so a failed
                // callback can never leave her mouthing forever.
                main.postDelayed(
                    { onState(AssistantState.IDLE) },
                    (1500L + message.length * 55L).coerceAtMost(20_000L)
                )
            } else {
                main.postDelayed({ onState(AssistantState.IDLE) }, 4000L)
            }
        }
    }

    companion object {
        private const val TAG = "X3MiraAgent"
        private const val UTT = "x3mira-agent"
        /** An errand gets this many act-and-look steps before it gives up. */
        private const val MAX_HOPS = 10
        /** Two taps closer than this (fraction of the screen) are the same press. */
        private const val SAME_SPOT = 0.04f
        /** Fallback wait when the mirror cannot be probed at all. */
        private const val SETTLE_MS = 900L
        /** How often to re-fingerprint the screen while waiting for it to settle. */
        private const val PROBE_MS = 150L
        /** Unchanged for this long counts as "finished moving". */
        private const val STILL_MS = 350L
        /**
         * Longest to wait on an ordinary action. Generous because the cost of
         * waiting is a pause, and the cost of NOT waiting is the model acting
         * on the previous screen — which reads as the agent giving up.
         */
        private const val TRANSITION_MS = 4_000L
        /**
         * A page load needs longer than a gesture: browser cold start, DNS,
         * render. Generous on purpose — look too soon and the model is handed
         * a half-drawn screen, cannot tell that it arrived, and answers "open
         * it" all over again.
         */
        private const val LOAD_MS = 6_000L
        /** How long a finished answer stays on the HUD before the band clears. */
        private const val LINE_MS = 15_000L
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
        /**
         * Tried in order. All three read images; they differ in how reliably
         * they answer at all. Full Flash gives the best description and is the
         * one that 503s under load, so it leads and the lite models catch it.
         */
        private val MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-flash-lite-latest"
        )

        private const val PROMPT =
            "You are a page agent for smart glasses. The image is the wearer's own phone " +
            "screen, mirrored into their glasses. THE WEARER'S HANDS ARE NOT ON THE PHONE " +
            "— you are the one who touches it, so when they ask for something to be done, " +
            "DO IT. Never reply that they can tap something themselves.\n" +
            "\n" +
            "If they ask you to press, click, tap, open, choose or select something, set " +
            "action to \"tap\" and give box_2d as the bounding box of that exact element, " +
            "as [ymin, xmin, ymax, xmax] normalised to 0-1000 over the image. Locate the " +
            "element precisely — a box around the whole screen or the middle of the image " +
            "is wrong and will press the wrong thing. Put a short confirmation in say, " +
            "phrased as something you have just done — for example \"Tapping the 9:30 PM " +
            "showtime.\"\n" +
            "If they name an APP to open — \"open Spotify\", \"go to Maps\" — use action " +
            "\"open_app\" and put the name they said in app, spelled as it appears under " +
            "the icon. This launches the real app on the phone. Do NOT send an app to " +
            "open_url: spotify.com is the web player, which is not what they asked for, " +
            "and do not answer that you cannot open apps, because you can.\n" +
            "If they name a WEBSITE to go to — \"open youtube.com\", \"go to Wikipedia\" — " +
            "use action \"open_url\" and put the full address in url, such as " +
            "\"https://www.youtube.com\". Do NOT tap the address bar: you cannot type, so " +
            "that only raises a keyboard you cannot use. open_url works from any app, not " +
            "just a browser, and you will be shown the loaded page afterwards and can carry " +
            "on with the rest of the errand there. Open a given site ONCE: if the screen " +
            "you are now shown is that site, or is still loading it, you have already " +
            "arrived — never open it a second time. Use \"none\" if nothing is left to do, " +
            "or get on with the rest of the errand.\n" +
            "If they ask to scroll, to see more, or to FIND something that is not on " +
            "screen yet, use \"scroll_down\" or \"scroll_up\". You will be shown the new " +
            "screen afterwards and can keep going — searching a page usually takes " +
            "several scrolls, so do not stop after one unless you have found it.\n" +
            "BEFORE scrolling again, look at THIS screen for what you are hunting. If it " +
            "is already here, stop scrolling and do the thing. If you can see you have " +
            "gone PAST it — the item was on the previous screen and is now above you — " +
            "use \"scroll_up\" to come back to it rather than carrying on down.\n" +
            "When an errand is \"find X and press its time/button\", press it as soon as X " +
            "and its button are both visible; do not keep scrolling to look for a better " +
            "one.\n" +
            "If they are only asking a question, use \"none\" and put the answer in say.\n" +
            "If the thing they named is not visible, use \"none\" and say so in one " +
            "sentence rather than guessing at a position.\n" +
            "\n" +
            "Fill ONLY the field your chosen action needs — box_2d for tap, text and " +
            "submit for type, app for open_app, url for open_url — and leave the rest " +
            "out entirely. Inventing a url on a type, or a box on an open_app, is wasted " +
            "output that can run the reply out of room before it is finished.\n" +
            "\n" +
            "say is spoken aloud: two sentences at most, no markdown, no lists, no preamble."
    }
}

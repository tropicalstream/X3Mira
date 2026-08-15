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
    /**
     * Type into the phone's focused field and WAIT for the verdict:
     * (accepted, reason). Only offered when the wearer allows it.
     */
    private val onTypeConfirmed: (text: String, submit: Boolean) -> Pair<Boolean, String>? =
        { _, _ -> null },
    /** Launch an installed app by name — "open Spotify" is not a URL. */
    private val onOpenApp: (String) -> Unit = { },
    /**
     * The phone's own buttons, by code: 1 back, 2 home, 3 recents, and 4+ the
     * media transport keys. Both travel the 'G' verb, and neither is a thing
     * the agent could reliably find by looking — Home is a gesture bar on this
     * phone, not a button at all.
     */
    private val onGlobal: (Int) -> Unit = { },
    /** Start turn-by-turn navigation, or ask the map a question. */
    private val onNavigate: (destination: String, mode: String) -> Unit = { _, _ -> },
    /**
     * Flip the GLASSES' own mirror between full-screen and a small corner
     * window. Returns the new state (true = compact). The one verb that
     * never touches the phone: the layout being changed is ours.
     */
    private val onHudToggle: () -> Boolean = { false },
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
        done.clear()                       // and remembers nothing from last time
        turns.clear()                      // ...including the conversation itself
        calls = 0                          // and a fresh model-call budget
        refusals = 0                       // and gets its full budget back
        scrollRun = 0; lastScrollDir = 0   // a fresh errand scrolls from scratch
        tapped.clear()                     // a fresh errand may press anywhere
        opened.clear()                     // ...and revisit a site it visited last time
        typed.clear()
        lastActionNote = null
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
            if (action == "none" || action.isEmpty()) {
                // "none" WITH AN ACTION-SHAPED SENTENCE IS NOT AN ENDING.
                // The last Sonos run died at 'none' + "Opening the search in
                // Sonos." — a step it was ABOUT to take, spoken as if done.
                // An outcome reads "X is playing"; an intention reads "Opening
                // / Tapping / Searching ...". Catch the intention and bounce it
                // back as one more look instead of finishing the errand there.
                val intention = say.trim().let {
                    it.startsWith("opening", true) || it.startsWith("tapping", true) ||
                    it.startsWith("searching", true) || it.startsWith("typing", true) ||
                    it.startsWith("scrolling", true) || it.startsWith("selecting", true) ||
                    it.startsWith("playing the", true) && !done.any { d -> d.startsWith("tapped") }
                }
                if (intention && refusals < MAX_REFUSALS) {
                    refusals++
                    Log.w(TAG, "hop none with an intention ('${say.take(48)}') — not an ending; re-asking")
                    lastActionNote = "You answered \"none\" — which ENDS the errand — while " +
                        "saying you were about to do something (\"${say.take(60)}\"). If " +
                        "there is a step left, DO it: choose the action that does it. Only " +
                        "answer none when the thing has actually happened."
                    if (mine != generation) return
                    shot = grabFrame() ?: shot
                    continue
                }
                finish(say, ok = true); return
            }
            // Fingerprint BEFORE acting: "has it changed" needs the thing it
            // changed from, and after the action it is already too late to ask.
            val beforeSig = probe()
            val tapsBefore = tapped.size
            when (perform(action, obj, say)) {
                Act.SETTLED -> { finish(say, ok = true); return }
                Act.CANNOT -> { finish(say, ok = false); return }
                Act.DONE -> return          // the branch already spoke for itself
                Act.ACTED -> Unit
                Act.REFUSED -> {
                    // A REFUSAL COSTS NOTHING. Nothing was done, the screen is
                    // unchanged, and charging a hop for it is how an errand
                    // starves: five refused open_apps ate half the budget while
                    // Sonos sat there already open, and the search never
                    // happened. Re-ask immediately, with the steering note, but
                    // bound the dithering separately so a model that will only
                    // ever repeat itself still ends rather than spinning.
                    refusals++
                    Log.i(TAG, "refusal $refusals/$MAX_REFUSALS — hop not spent")
                    if (refusals >= MAX_REFUSALS) {
                        finish("I keep going round in circles on that one.", ok = false)
                        return
                    }
                    if (mine != generation) return
                    shot = grabFrame() ?: shot
                    continue
                }
            }
            hop++
            if (hop >= MAX_HOPS) break
            // Let the phone actually move before looking again, then take a
            // fresh frame — the whole point of a hop. Waited out by WATCHING
            // rather than by sleeping a guess: a page load or an app cold start
            // is a far bigger event than a tap, and one constant cannot be
            // right for both without being wrong for one.
            val moved = awaitSettled(
                beforeSig,
                if (action == "open_url" || action == "open_app") LOAD_MS else TRANSITION_MS
            )
            // Remember whether THAT press did anything, against the spot that
            // made it. It is the only honest way to answer "was this already
            // done?" if the model reaches for the same control a second time.
            if (tapped.size > tapsBefore) tapped.last()[2] = if (moved) 1f else 0f
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
    private fun awaitSettled(before: Long?, capMs: Long): Boolean {
        if (before == null) { Thread.sleep(SETTLE_MS); return false }
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
                return true
            }
        }
        Log.i(TAG, "settle cap hit after ${capMs}ms (changed=$changed)")
        return changed
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

    /**
     * How many times in a row the same direction has been scrolled, and
     * which way. A run of these means the thing is not in this list at all
     * — see the scroll branch, where the run steers to search instead.
     */
    /**
     * A short account of what this errand has actually done, in order,
     * fed back into every prompt. Without it each hop re-reads the
     * errand from the top and repeats its first step forever.
     */
    private val done = ArrayList<String>()
    /**
     * The errand's conversation so far, as Gemini turns. Text only — the
     * newest frame rides the live user turn — and trimmed to the last few
     * exchanges so a long errand cannot grow its own prompt without bound.
     */
    private val turns = ArrayList<JSONObject>()
    /** HTTP requests this errand has made. */
    private var calls = 0
    /** When the newest request went out, for the inter-call floor. */
    private var lastCallAt = 0L
    /** Set within one askOnce when a 429 landed, to stop the model walk. */
    private var quotaHit = false

    /** Guard refusals this errand — bounded so dithering still ends. */
    private var refusals = 0

    private var scrollRun = 0
    private var lastScrollDir = 0

    /** Sites already opened this errand, compared by [siteOf]. */
    private val opened = HashSet<String>()

    /** Text already typed this errand — SUCCESSFULLY. */
    private val typed = HashSet<String>()

    /**
     * What went wrong with the last action, told to the model on the next hop.
     *
     * Without this the loop could only show the model a picture and hope it
     * inferred the failure. A screen that did not change is ambiguous — the
     * tap might have missed, the page might be slow, the text might have gone
     * nowhere — and the model reliably guessed "try the same thing again".
     */
    @Volatile private var lastActionNote: String? = null

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
    /**
     * ACTED   did something; look again.
     * SETTLED done, and the loop speaks the model's own confirmation.
     * CANNOT  cannot be done, and the loop says why.
     * DONE    the branch has ALREADY finished the errand in its own words —
     *         used where the right answer is a question back to the wearer
     *         rather than an action, so the loop must not speak over it.
     */
    private enum class Act { ACTED, SETTLED, CANNOT, DONE, REFUSED }

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
                        // A repeat on its own says NOTHING about success. Read
                        // as "done" it announces plays that never happened;
                        // read as "stuck" it contradicts a press that worked
                        // and sends the agent round again. Both were guesses.
                        // The screen already answered the question the first
                        // time — awaitSettled watched whether that press moved
                        // anything — so decide on that instead.
                        val prior = tapped.firstOrNull {
                            kotlin.math.abs(fx - it[0]) < SAME_SPOT &&
                                kotlin.math.abs(fy - it[1]) < SAME_SPOT
                        }
                        if (prior != null && prior[2] > 0.5f) {
                            // The press WORKED — but that is all it proves. As
                            // an immediate "the errand landed" shortcut this
                            // fired two hops into a Sonos errand, declaring
                            // victory at the search page. So a working press
                            // still means look at what it produced and take the
                            // NEXT step — the FIRST time. What follows is the
                            // other half of that lesson, learned since: never
                            // ending on our own account is what let an errand
                            // that had already arrived spin until it died.
                            //
                            // CONVERGENCE IS TERMINAL, NOT RETRYABLE. Steering
                            // the model off a spot it keeps choosing works or it
                            // does not; asking a third time has never once
                            // produced a different answer, and each ask is
                            // another ~96KB upload. Count refusals PER SPOT and
                            // treat the third as the errand's answer: the press
                            // worked, the screen in front of the wearer is the
                            // result, so say so rather than dying with "I keep
                            // going round in circles" on an errand that landed.
                            prior[3] = prior[3] + 1f
                            if (prior[3] >= SPOT_REFUSALS) {
                                Log.i(TAG, "hop tap settled at ($fx,$fy) after " +
                                    "${prior[3].toInt()} repeats — calling it done")
                                // DONE means the branch speaks for itself, so say
                                // the model's own line: it describes what the
                                // press achieved, which is what the wearer is
                                // now looking at.
                                finish(say.ifBlank { "That's done." }, ok = true)
                                Act.DONE
                            } else {
                                Log.i(TAG, "hop tap repeated at ($fx,$fy) — press already " +
                                    "worked; steering to the next step")
                                lastActionNote = "You already pressed that exact spot and it " +
                                    "WORKED — the screen you are looking at is the result. Do " +
                                    "not press it again. Take the NEXT step toward the goal, " +
                                    "or answer none only if the goal is truly visible as done."
                                Act.REFUSED
                            }
                        } else {
                            Log.w(TAG, "hop tap REPEATED at ($fx,$fy) — first press changed " +
                                "nothing; steering")
                            lastActionNote = "You already pressed that exact spot earlier " +
                                "and the screen did not react at all, so it is the wrong " +
                                "control and pressing it again cannot help. Choose a " +
                                "DIFFERENT element. If you are on an album or playlist and " +
                                "the only play button in view is the mini player's, that " +
                                "one belongs to the previous track: \"scroll_down\" first " +
                                "to bring this page's own play button into view."
                            main.post { onText("That did nothing — trying another way") }
                            Act.ACTED
                        }
                    }
                    else -> {
                        Log.i(TAG, "hop tap box=[$ymin,$xmin,$ymax,$xmax] -> ($fx,$fy)")
                        // Third slot: did the screen answer this press? Unknown
                        // until the settle watch below fills it in.
                        // Fourth slot: refusals charged to THIS spot.
                        tapped.add(floatArrayOf(fx, fy, -1f, 0f))
                        done.add("tapped ${say.take(48)}")
                        main.post { onText(say); onTap(fx, fy) }
                        Act.ACTED
                    }
                }
            }
        }
        "hud" -> {
            // Glasses-local and instant — but it moves VIEWS, and layout only
            // happens on the main thread. The first cut called through from
            // the hop worker for the sake of the return value and
            // requestLayout threw, killing the app mid-errand. The toggle now
            // rides main.post like every other UI callback; the model's own
            // say is the confirmation and MainActivity flashes the new state.
            main.post {
                val compact = onHudToggle()
                Log.i(TAG, "hop hud toggle -> ${if (compact) "compact" else "full"}")
            }
            Act.SETTLED
        }
        "wait" -> {
            // "Still loading" is not "finished", and the model had no way to
            // say so: it kept answering none — which ends the errand — while
            // an app was still coming up, so "play X on Sonos" died at
            // "Waiting for Sonos to load". This does nothing on purpose. The
            // loop's own settle-and-look-again is the whole point, and
            // MAX_HOPS still bounds how long it can dither.
            Log.i(TAG, "hop wait: ${say.take(60)}")
            main.post { onText(say) }
            // Waiting changes nothing, so it must not spend a hop — three
            // waits in a cold-start errand starved the budget with the goal
            // in sight. Give the screen a moment, then re-ask on the free
            // path; MAX_REFUSALS still bounds an errand that only ever waits.
            Thread.sleep(1400)
            Act.REFUSED
        }
        "navigate" -> {
            val dest = obj.optString("destination").trim()
            val travel = obj.optString("travel").trim().lowercase()
            when {
                dest.isEmpty() -> Act.CANNOT
                // NO DEFAULT TRAVEL MODE, deliberately. Guessing "drive" for
                // someone who meant to walk sends them onto roads with no
                // pavement; guessing "walk" for a long drive is merely absurd.
                // The wearer says which, or they are asked.
                travel !in setOf("drive", "bicycle", "walk", "search") -> {
                    Log.i(TAG, "hop navigate '$dest' with no travel mode — asking instead")
                    finish("Do you want to drive, bicycle or walk there?", ok = true)
                    Act.DONE
                }
                else -> {
                    Log.i(TAG, "hop navigate $travel -> '$dest'")
                    main.post { onText(say); onNavigate(dest, travel) }
                    Act.SETTLED
                }
            }
        }
        "window" -> {
            // The little floating video window. Handled by name on the phone
            // rather than aimed at: it is a few hundred pixels in a corner and
            // its controls are not even drawn until it is touched, so a tap
            // here is the least reliable thing the agent could attempt.
            val code = when (obj.optString("window").trim().lowercase()) {
                "fullscreen" -> 11
                "close" -> 12
                else -> 0
            }
            if (code == 0) Act.CANNOT
            else {
                Log.i(TAG, "hop window ${obj.optString("window")} -> code $code")
                main.post { onText(say); onGlobal(code) }
                Act.SETTLED
            }
        }
        "nav" -> {
            // Back, Home and Recents as the phone's real buttons. On this
            // phone Home is a gesture bar, not a button, so there is often
            // nothing on screen to tap even when the wearer asks plainly to
            // "go home" — and hunting for one is how the agent ends up
            // pressing something else entirely.
            val code = when (obj.optString("nav").trim().lowercase()) {
                "back" -> 1
                "home" -> 2
                "recents" -> 3
                // Closing is not the same as leaving. "home" backgrounds an
                // app and it keeps running; this throws its card away.
                "close" -> 14
                else -> 0
            }
            if (code == 0) Act.CANNOT
            else {
                Log.i(TAG, "hop nav ${obj.optString("nav")} -> code $code")
                main.post { onText(say); onGlobal(code) }
                Act.SETTLED
            }
        }
        "media" -> {
            // Transport goes as a key, never as a press on a picture of a
            // button. Every playback failure so far came from hunting the
            // right circle: two of them on screen, either one under the fold,
            // and the icon showing the next action rather than the state.
            val code = when (obj.optString("media").trim().lowercase()) {
                "play" -> 5
                "pause" -> 6
                "next" -> 7
                "previous" -> 8
                "rewind" -> 9
                "forward" -> 10
                else -> 0
            }
            if (code == 0) Act.CANNOT
            else {
                Log.i(TAG, "hop media ${obj.optString("media")} -> code $code")
                main.post { onText(say); onGlobal(code) }
                // The picture barely changes for a media key, so there is
                // nothing to look at afterwards and nothing to second-guess:
                // the phone either has a session to take it or it does not.
                Act.SETTLED
            }
        }
        "scroll_down", "scroll_up" -> {
            val dir = if (action == "scroll_down") 1 else -1
            // A SCROLL SPIRAL IS EVIDENCE THE LIST IS THE WRONG ROUTE. Hunting
            // one album down an artist page cost six hops and played nothing,
            // twice. Two fruitless scrolls in the same direction is enough to
            // conclude the thing is not simply "further down"; a third is just
            // the hop budget draining. Say so, once, and point at the search
            // box — the model cannot see how many times it has already tried.
            if (dir == lastScrollDir) scrollRun++ else { scrollRun = 1; lastScrollDir = dir }
            if (scrollRun >= SCROLL_LIMIT) {
                Log.w(TAG, "hop scroll x$scrollRun in one direction — steering to search")
                lastActionNote = "You have now scrolled $scrollRun times without finding " +
                    "it, so it is not simply further down this list. STOP SCROLLING. Tap " +
                    "the app's search box, type the exact title you are looking for with " +
                    "submit true, and pick it from the results."
                scrollRun = 0
                main.post { onText("Not in this list — searching instead") }
                Act.ACTED
            } else {
                Log.i(TAG, "hop scroll ${if (dir == 1) "down" else "up"} ($scrollRun): $say")
                main.post { onText(say); onScroll(dir) }
                Act.ACTED
            }
        }
        "type" -> {
            val text = obj.optString("text")
            if (text.isEmpty()) Act.CANNOT
            else {
                val submit = obj.optBoolean("submit", true)
                // Re-sending text that ALREADY LANDED is never progress. Text
                // that failed is a different matter entirely, and the two used
                // to be indistinguishable here — which is why a type into an
                // unfocused page ended the errand instead of correcting it.
                if (text in typed) {
                    Log.i(TAG, "hop type repeated — already typed that successfully")
                    Act.SETTLED
                } else {
                    Log.i(TAG, "hop type ${text.length} chars submit=$submit")
                    main.post { onText(say) }
                    val reply = onTypeConfirmed(text, submit)
                    val ok = reply != null && reply.first
                    val why = reply?.second.orEmpty()
                    if (ok) {
                        typed.add(text)
                        Log.i(TAG, "hop type accepted")
                        done.add("typed \"${text.take(40)}\" into the box")
                        if (submit) {
                            // SUBMITTING IS A ONE-WAY DOOR, and the screen does
                            // not say so. The tool chip disappears once the
                            // request goes, and the answer takes seconds to
                            // draw, so the very next look is a bare chat — from
                            // which the model concluded, repeatedly, that it had
                            // never picked the tool and opened the menu again.
                            // That re-pick is destructive: the app offers to
                            // start a NEW CHAT and throws the request away. The
                            // static prompt could not reach it here; a note
                            // timed to this exact moment can.
                            lastActionNote = "You have JUST SUBMITTED that request, so the " +
                                "menu step is finished — do not open the + or re-pick a " +
                                "tool now, which would discard it and start a new chat. " +
                                "The app is working and its answer takes a few seconds to " +
                                "appear. Look at the screen as it is: if there is a button " +
                                "that begins or opens the result — \"Start research\", " +
                                "\"Generate\", \"Open\" — press that. If it is still " +
                                "working, answer none and say so."
                        }
                        Act.ACTED
                    } else {
                        // NOT settled, and not remembered: the model gets told
                        // what went wrong and can fix it — which for the usual
                        // cause means tapping the field first.
                        Log.w(TAG, "hop type REFUSED: $why")
                        lastActionNote = if (why.isNotEmpty()) {
                            "Your last \"type\" did NOT go in: $why. Tap the text " +
                                "field first so it has the cursor, then type again."
                        } else {
                            "Your last \"type\" did not reach the phone. Tap the " +
                                "field first, then type again."
                        }
                        main.post { onText("Nothing was focused — tapping first") }
                        Act.ACTED
                    }
                }
            }
        }
        "open_app" -> {
            val app = obj.optString("app").trim()
            if (app.isEmpty()) Act.CANNOT
            else if (!opened.add("app:" + app.lowercase())) {
                // ARRIVING IS NOT FINISHING. Settling here ended errands at
                // their halfway point: "open YouTube and play the first video"
                // opened YouTube, asked for it again to do the playing part,
                // and was told the errand was complete with nothing played.
                // The repeat is still worth catching — relaunching helps
                // nobody — but the answer is to push on from here, not to stop.
                Log.i(TAG, "hop open_app REFUSED (already there) — no hop spent")
                lastActionNote = "You are already in $app — it is the screen in front of " +
                    "you, and opening it again does nothing. Carry on with the REST of " +
                    "what was asked from this screen: find the thing on it and tap that."
                Act.ACTED
            } else {
                Log.i(TAG, "hop open_app $app")
                done.add("opened $app")
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
                // started, forever. So the repeat is still refused. But
                // arriving somewhere is not the same as finishing the errand
                // that sent you there, and settling here ended half-done
                // journeys as successes, so steer instead of stopping.
                opened.contains(same) -> {
                    Log.i(TAG, "hop open repeated $url — already there; steering on")
                    lastActionNote = "That page is already open and in front of you — " +
                        "loading it again would only start it over. Continue the errand " +
                        "from this screen instead."
                    Act.ACTED
                }
                else -> {
                    Log.i(TAG, "hop open $url")
                    opened.add(same)
                    // The one state-changing action that recorded nothing in
                    // `done` — so the next prompt could not see that the page
                    // it asked for is the page it is now looking at.
                    done.add("opened $same")
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
                // SCALED AND SQUEEZED before upload. This image travels over
                // whatever uplink the phone has, and on cellular that upload
                // IS the hop time: at 85% quality and full decode size a frame
                // ran ~200KB, and hops measured 30-50 seconds on a
                // deprioritized MVNO uplink. Three-quarter scale at 60%
                // quality reads the same to the model — UI labels survive —
                // at roughly a third of the bytes.
                val scaled = if (bmp.height > VISION_MAX_SIDE) {
                    val f = VISION_MAX_SIDE.toFloat() / bmp.height
                    Bitmap.createScaledBitmap(
                        bmp, (bmp.width * f).toInt().coerceAtLeast(1), VISION_MAX_SIDE, true
                    )
                } else bmp
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, VISION_QUALITY, out)
                    if (scaled !== bmp) runCatching { scaled.recycle() }
                    out.toByteArray().also {
                        Log.i(TAG, "vision frame ${it.size / 1024}KB")
                    }
                }
            }.getOrNull()
        }
        val parts = org.json.JSONArray()
        // WHAT HAS ALREADY BEEN DONE, not merely how many times. A count told
        // the model nothing it could act on: every hop re-read the errand
        // ("In the Sonos app, play ...") and executed its first clause again,
        // so half of a ten-hop budget went on opening an app that was already
        // open. Given the list, the opening step is visibly behind it.
        val doneNote = if (done.isEmpty()) "" else
            "\n\nSTEPS YOU HAVE ALREADY TAKEN, in order: " + done.joinToString("; ") +
            ". Those are DONE — do not do them again, and in particular do not " +
            "re-open an app you have already opened. Carry on from where that leaves you."
        val hopNote = if (hop == 0) "" else
            "\n\nYou have already taken $hop step(s) on this errand and this image is " +
            "the screen AS IT IS NOW. If the goal is reached, or you can now answer, use " +
            "action \"none\" and give the answer in say. Otherwise take the next step." +
            doneNote
        val typingNote = if (!typingEnabled) "" else
            "\n\nYou CAN type. ALWAYS tap the field FIRST — a type with nothing focused " +
            "goes nowhere and is wasted. To search or fill a box: \"tap\" it first so it has the " +
            "cursor, then on the next turn use action \"type\" with the words in text — " +
            "it goes into whatever field is focused, so the tap has to land first. Set " +
            "submit true to press Search/Go afterwards, which is almost always what a " +
            "search wants; false only if there is another field to fill first. Type the " +
            "whole phrase at once. Never tap letters on the on-screen keyboard: that is " +
            "one turn per character and it will not finish."
        val failNote = lastActionNote?.let { "\n\nIMPORTANT: $it" }.orEmpty()
        lastActionNote = null
        parts.put(
            JSONObject().put(
                "text", PROMPT + typingNote + hopNote + failNote + "\n\nErrand: " + question
            )
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
                                .put("media").put("nav").put("window").put("navigate").put("wait").put("hud")
                                .apply { if (typingEnabled) put("type") })
                    )
                    .put("app", JSONObject().put("type", "STRING"))
                    // Transport as a named verb rather than a coordinate. The
                    // phone turns each of these into a real media key, so it
                    // lands on whatever is actually playing.
                    .put(
                        "media", JSONObject().put("type", "STRING")
                            .put("enum", org.json.JSONArray()
                                .put("play").put("pause").put("next")
                                .put("previous").put("rewind").put("forward"))
                    )
                    .put(
                        "nav", JSONObject().put("type", "STRING")
                            .put("enum", org.json.JSONArray()
                                .put("back").put("home").put("recents").put("close"))
                    )
                    .put(
                        "window", JSONObject().put("type", "STRING")
                            .put("enum", org.json.JSONArray()
                                .put("fullscreen").put("close"))
                    )
                    .put("destination", JSONObject().put("type", "STRING"))
                    .put(
                        "travel", JSONObject().put("type", "STRING")
                            .put("enum", org.json.JSONArray()
                                .put("drive").put("bicycle").put("walk").put("search"))
                    )
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
        // ITS OWN ANSWERS, BACK AS TURNS. This was ONE user turn — the
        // model saw a screenshot and a prompt and nothing else, including
        // nothing it had itself said a moment earlier. That makes each hop a
        // pure function of the screen, so a hop whose action does not visibly
        // change the screen is re-derived verbatim, forever: the guards then
        // read that identical answer as stubbornness when it is arithmetic.
        // Measured: eight consecutive requests for the same tap, each a fresh
        // ~96KB upload, on an errand that had ALREADY succeeded.
        //
        // Replies are appended as "model" turns, so the model can see that it
        // has already asked for this and what came of it. Only the newest
        // frame is carried; prior turns are text alone, because a history of
        // screenshots would cost more upload than the loop it prevents.
        val contents = org.json.JSONArray()
        for (t in turns) contents.put(t)
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject()
            .put("contents", contents)
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
        quotaHit = false
        lastFail = "I couldn't reach the model."
        // A QUOTA PAUSE OUTLIVES THE ERRAND THAT EARNED IT. A 429 says the
        // project is over its limit; starting a new errand ten seconds later
        // and firing again just re-earns it, which is what made the wearer
        // meet "the model is busy" over and over rather than once.
        val waitMs = quotaUntil - SystemClock.elapsedRealtime()
        if (waitMs > 0) {
            Log.w(TAG, "quota pause: ${waitMs / 1000}s left")
            lastFail = "I am over the model's rate limit — about " +
                "${(waitMs / 1000).coerceAtLeast(1)} seconds to go."
            return null
        }
        for (model in MODELS) {
            // Counted in REQUESTS, which is what a quota counts. Hops and
            // refusals bound decisions; neither bounds the thing that is
            // actually scarce, and the model walk spends three per hop.
            if (calls >= MAX_CALLS) {
                Log.w(TAG, "model-call ceiling ($MAX_CALLS) reached — stopping")
                lastFail = "That took more looking than I am allowed for one job."
                return null
            }
            // Never two uploads back to back: on a link already carrying the
            // mirror, a burst is what turns a slow call into a timed-out one.
            val since = SystemClock.elapsedRealtime() - lastCallAt
            if (since in 0 until MIN_CALL_GAP_MS) Thread.sleep(MIN_CALL_GAP_MS - since)
            lastCallAt = SystemClock.elapsedRealtime()
            calls++
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
                        // 429 IS NOT 503, AND TREATING THEM ALIKE IS WHY THIS
                        // CASCADES. 503 means THIS model is busy, so trying the
                        // next one is exactly right. 429 means the PROJECT is
                        // over quota — every model on the list bills the same
                        // quota, so walking them fires three requests into a
                        // limit that is already breached and pushes the reset
                        // further away. Back off instead, honouring the delay
                        // Gemini itself names.
                        lastFail = when (resp.code) {
                            429 -> {
                                val delay = retryDelayMs(text)
                                quotaUntil = SystemClock.elapsedRealtime() + delay
                                Log.w(TAG, "429 quota — pausing ${delay / 1000}s, not walking models")
                                quotaHit = true
                                "I am over the model's rate limit — try again in " +
                                    "${delay / 1000} seconds."
                            }
                            503, 500, 502, 504 -> "The model is busy — try again."
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
            // A breached quota is not this model's fault and the next one
            // shares it — stop walking.
            if (quotaHit) break
        }

        if (answer == null) return null
        recordTurn(answer)
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

    /**
     * Keep the model's own reply, and the note the guards wrote about it, in
     * the conversation — trimmed, because only the recent past steers.
     *
     * lastActionNote already says things like "you pressed that and it
     * worked, do not press it again". It was being handed over as prompt
     * text with no indication of WHICH answer it judged; as a reply to the
     * model's actual turn it reads as what it is — a result.
     */
    /**
     * The pause Gemini asks for on a 429, from error.details[].retryDelay
     * ("27s"). Falls back to a sane wait: guessing short re-earns the limit.
     */
    private fun retryDelayMs(body: String): Long = runCatching {
        val details = JSONObject(body).optJSONObject("error")?.optJSONArray("details")
            ?: return@runCatching QUOTA_PAUSE_MS
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            val secs = d.optString("retryDelay").removeSuffix("s").toLongOrNull()
            if (secs != null && secs > 0) return@runCatching secs * 1000
        }
        QUOTA_PAUSE_MS
    }.getOrDefault(QUOTA_PAUSE_MS)

    private fun recordTurn(reply: String) {
        turns.add(
            JSONObject().put("role", "model")
                .put("parts", org.json.JSONArray().put(JSONObject().put("text", reply)))
        )
        lastActionNote?.let { note ->
            turns.add(
                JSONObject().put("role", "user")
                    .put("parts", org.json.JSONArray().put(JSONObject().put("text", note)))
            )
        }
        while (turns.size > MAX_TURNS) turns.removeAt(0)
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

        /** Fruitless scrolls in one direction before steering to search. */
        private const val SCROLL_LIMIT = 3

        /** Refusals before an errand is called circular and stopped. */
        private const val MAX_REFUSALS = 8
        /** Repeats at ONE spot before the errand accepts that press as its answer. */
        private const val SPOT_REFUSALS = 2
        /** Model/user turns carried per errand — recent past only. */
        private const val MAX_TURNS = 8
        /** HTTP requests one errand may make, whatever the hops and refusals do. */
        private const val MAX_CALLS = 14
        /** Floor between vision calls, so a spin cannot become a burst. */
        private const val MIN_CALL_GAP_MS = 1200L
        /** Default quota pause when the 429 body names no delay. */
        private const val QUOTA_PAUSE_MS = 30_000L
        /** Shared across errands: a breached quota does not reset on a new one. */
        @Volatile private var quotaUntil = 0L
        /**
         * Vision upload budget: tallest side and JPEG quality. 1152x540-ish at
         * 60 keeps a phone's UI labels legible to the model at about a third
         * of the former bytes — and the bytes are the hop time on cellular.
         */
        private const val VISION_MAX_SIDE = 1152
        private const val VISION_QUALITY = 60

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
        // LITE FIRST. Full Flash gives the best answer when it answers, but on
        // a slow cellular uplink it was observed holding a request for 47
        // seconds before returning 503 — the fallback chain then starts over,
        // and one hop costs a minute. The lite models answer in seconds and
        // have not been seen to 503 here; the same ordering lesson is already
        // written on the phone's provider list.
        private val MODELS = listOf(
            "gemini-flash-lite-latest",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash"
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
            "\"none\" ENDS THE ERRAND, so never use it to mean \"hang on\". If the app is " +
            "still opening, a list is still loading, or a result is still being fetched, " +
            "answer \"wait\" and say what you are waiting for. You will be shown the " +
            "screen again a moment later and can carry on from there. Answering \"none\" " +
            "at that point stops everything with the job half done — \"waiting for it to " +
            "load\" is how an errand quietly dies one step in.\n" +
            "\n" +
            "AN ERRAND DOES NOT HAVE TO START WHERE YOU ARE. If what they asked for lives " +
            "somewhere the current screen cannot reach — they want a song and you are " +
            "looking at a browser, a video and you are in a music app, a page in an app " +
            "that is not open — then go and get to it: \"open_app\" for an app, " +
            "\"open_url\" for a web address. You will be shown that app once it is up and " +
            "can carry on with the rest of the errand there. Standing in the wrong app is " +
            "not a reason to give up; opening the right one is simply the first step.\n" +
            "So when the thing they named is not visible, work out WHY before you answer " +
            "that you cannot see it: it may be further down this same page (scroll), or " +
            "inside an app nobody has opened yet (open_app), or at an address you know " +
            "(open_url). Only when you are already in the right place and it genuinely is " +
            "not there should you use \"none\" and say so in one sentence — never guess at " +
            "a position for something you cannot see.\n" +
            "\n" +
            "Pages BURIED inside an app often have their own address, and going straight " +
            "there beats working through the menus: it is one step instead of three, and " +
            "there is nothing to mis-tap on the way. These open inside the YouTube app " +
            "itself, already signed in as the wearer:\n" +
            "  watch history      https://www.youtube.com/feed/history\n" +
            "  subscriptions      https://www.youtube.com/feed/subscriptions\n" +
            "  Watch Later        https://www.youtube.com/playlist?list=WL\n" +
            "  liked videos       https://www.youtube.com/playlist?list=LL\n" +
            "So \"show me my YouTube history\" is a single open_url, not a trip through the " +
            "account picture. Where you do NOT know an address for the place they want, " +
            "get there by tapping the way the wearer would — in YouTube that means the " +
            "account picture at the BOTTOM RIGHT, under \"You\", and then the History row.\n" +
            "\n" +
            "WHEN A SMALL VIDEO WINDOW IS FLOATING IN A CORNER, USE \"window\". The " +
            "wearer calls it the MINI WINDOW, the PIP WINDOW or the LITTLE WINDOW, and " +
            "may also say picture-in-picture, minimised player, the small video, or the " +
            "corner video — every one of those means this same thing, so treat them " +
            "alike. Set window to \"fullscreen\" to put it back to the whole screen, or " +
            "\"close\" to get rid of it. Do not aim at it yourself: it is a few hundred " +
            "pixels in a corner and its X is not drawn until the window has been touched, " +
            "so the phone taps it and presses the X for you, exactly.\n" +
            "\"TOGGLE HUD MODE\" IS THE GLASSES' OWN DISPLAY, NOT THE PHONE. When they " +
            "say toggle hud mode, hud mode, compact mode, small window mode, or ask to " +
            "— and HUD is one word said aloud, so the transcript may spell it hud, " +
            "HUD, H U D, or even \"had\"/\"hood\" misheard before \"mode\"; any of " +
            "those next to the word mode means this — " +
            "make the mirror small or full screen again, use action \"hud\" — one step, " +
            "done. It flips THIS display between the full-screen mirror and a small " +
            "window in the top left that leaves the world visible; saying it again flips " +
            "back. Nothing on the phone changes, so do not open any app, press anything, " +
            "or reach for \"window\" — that verb is for the phone's own floating video, " +
            "which is a different thing entirely.\n" +
            "TO USE THE PHONE'S OWN BUTTONS, USE \"nav\": set nav to \"home\" to go back " +
            "to the phone's home screen, \"back\" to go back one step, \"recents\" for the " +
            "app switcher. This works from inside any app. Do not look for a home button " +
            "to tap — on this phone Home is a gesture bar rather than a button, so there " +
            "is usually nothing on screen to press, and whatever you pressed instead was " +
            "something else.\n" +
            "CLOSING AN APP IS NOT THE SAME AS LEAVING IT, and the difference matters to " +
            "the wearer. \"home\" only puts an app into the background: it keeps running, " +
            "keeps playing, and is still there when they look again. When they say CLOSE " +
            "the app, quit it, shut it, or get rid of it, use nav \"close\" — the phone " +
            "throws the app's card out of the switcher, which genuinely ends it, and " +
            "leaves them on the home screen. Use \"home\" only when they actually asked to " +
            "go home.\n" +
            "TO CONTROL WHAT IS ALREADY PLAYING, USE \"media\" — never a tap. Set media to " +
            "\"pause\", \"play\", \"next\", \"previous\", \"rewind\" or \"forward\" and the " +
            "phone sends the real key, which lands on whatever is playing whether or not " +
            "any button for it is on the screen. It is right in every app — Spotify, " +
            "YouTube, YouTube Music, Pocket Casts — and in either orientation. Reaching " +
            "for a round button by eye is what goes wrong: there are often two of them, " +
            "one belongs to something else, either can be scrolled out of sight, and the " +
            "icon shows the action available NEXT rather than the state it is in, so " +
            "pressing what looks like \"not yet paused\" is how you un-pause it.\n" +
            "WHEN THEY DESCRIBE IT INSTEAD OF NAMING IT, WORK OUT THE NAME FIRST. \"the " +
            "first Led Zeppelin album\", \"their latest episode\", \"the one about the " +
            "moon landing\" — none of those is a title, and the app cannot match a " +
            "description. Decide what it actually is, say that title in your say so the " +
            "wearer can correct you, and then go and find THAT. An ordinal is the usual " +
            "case: first, latest, newest, the one before this. You know these; use what " +
            "you know rather than taking whichever item happens to be on screen.\n" +
            "NEVER SWAP ONE APP FOR ANOTHER. If they named the app — \"on Sonos\", \"in " +
            "Pocket Casts\" — that app is part of the errand, not a detail. Where they " +
            "said it plays matters as much as what plays: Sonos drives the speakers in " +
            "their house, Spotify plays out of the phone in their pocket, and quietly " +
            "using the second when they asked for the first is the wrong outcome however " +
            "right the music is. If the name you heard matches no app on the phone — it " +
            "may have been misheard, \"Sonos\" arriving as \"solos\" — do NOT reach for " +
            "the nearest similar app. Look for one whose name is close (Sonos, Sonos S1), " +
            "and if nothing fits, use \"none\" and ask which app they meant.\n" +
            "TYPE THE TITLE INTO THE SEARCH BOX. Not \"go to search and look\" — TYPE it. " +
            "The steps, in order, every time:\n" +
            "  1. tap the search box\n" +
            "  2. \"type\" the TITLE OF THE THING ITSELF, with submit true\n" +
            "  3. pick it from the results\n" +
            "So \"the first album by The Cure\" is: work out that it is Three Imaginary " +
            "Boys, tap search, TYPE \"Three Imaginary Boys\", and choose it from the " +
            "results. Type the title you decided on — not the band's name, which only " +
            "lands you back on a list of everything they ever made.\n" +
            "SEARCH RESULTS COME IN TABS, AND THE FIRST TAB IS USUALLY THE WRONG ONE. " +
            "Sonos shows Artists, Songs, Albums, Playlists, Stations across the top of " +
            "its results and OPENS ON ARTISTS — so after typing an album title the rows " +
            "in front of you are artists, and tapping one takes you into an artist page, " +
            "not the record. After typing an album title, tap the ALBUMS tab, then the " +
            "album in that list. Looking for a song, tap Songs. The tab that matches what " +
            "you are after, then the row.\n" +
            "SCROLLING IS NOT SEARCHING. An artist page is an arbitrarily long list, and " +
            "scrolling it burns the few steps an errand has: six scrolls hunting one album " +
            "ends with nothing played. If you have scrolled twice and still cannot see " +
            "what you named, stop scrolling — go to the search box and type the title. " +
            "Browsing is only for apps with no search at all.\n" +
            "THE RIGHT ARTIST IS NOT THE RIGHT ALBUM. Having found the artist, do not " +
            "press the first of their records you can see — that is how \"the first Led " +
            "Zeppelin album\" becomes Led Zeppelin III. Settle on the exact title BEFORE " +
            "you press anything, put that title in your say, and then match it against " +
            "what is on screen.\n" +
            "A NAME THAT MERELY LOOKS RIGHT IS NOT A MATCH. An album sharing the band's " +
            "name usually is not their first: The Cure's self-titled album is their " +
            "twelfth, and their first is Three Imaginary Boys. So a self-titled record is " +
            "evidence of nothing on its own — it is the TITLE YOU DECIDED ON that has to " +
            "match, not a coincidence of naming. The same goes for a remaster, a live " +
            "version, a deluxe edition or a greatest-hits with a similar name.\n" +
            "If you cannot find that exact title, say which title you were looking for and " +
            "that you cannot see it, rather than playing something else. A wrong record " +
            "playing confidently is worse than an honest miss, because the wearer has to " +
            "work out for themselves that you got it wrong.\n" +
            "BEFORE YOU ANSWER \"none\" ON A PLAY ERRAND, READ THE SCREEN AND CHECK THE " +
            "NAME. The player shows what is actually on — in the now-playing bar, or on " +
            "the full player under the artwork. Compare it, word for word, with the title " +
            "you decided on. If they differ, you pressed the wrong thing: the errand is " +
            "NOT done, however sure you felt when you pressed it, so find the right title " +
            "and press that instead. Saying \"Playing X\" while the screen says Y is the " +
            "single worst thing you can do, because the wearer trusts you and hears the " +
            "wrong record. Also check it is actually PLAYING — a paused player with the " +
            "right name still needs its play pressed (that one press IS \"media\" play, " +
            "since the right thing is already loaded).\n" +
            "KNOW WHAT SUCCESS LOOKS LIKE FOR AN ALBUM: the mini player names the TRACK, " +
            "never the album — an album's own title does not appear there. Playing Three " +
            "Imaginary Boys looks like \"10.15 Saturday Night • The Cure\" with pause " +
            "bars: its first track, by the right artist, playing. That IS success. Do not " +
            "keep pressing Play hunting for the album's name, because every extra press " +
            "only starts the record over.\n" +
            "NEVER USE \"media\" TO START SOMETHING THE WEARER NAMED. A media key cannot " +
            "choose: \"play\" resumes whatever the app last had loaded, which is the " +
            "previous album or the previous podcast — so asking for one show and pressing " +
            "media play gives them a different show, playing confidently, with nothing on " +
            "screen to say it is wrong. If they named an album, a playlist, a show, an " +
            "episode, a track or a video, you must FIND that thing and press ITS OWN play " +
            "control, and the errand is not done until the name now showing is the name " +
            "they asked for. Check it before you finish.\n" +
            "\"media\" is only for what is ALREADY playing AND already the right thing: " +
            "pause it, resume it after pausing it, skip, rewind. \"Pause\", \"carry on\", " +
            "\"next one\" — those are media. \"Play <name>\" never is.\n" +
            "IN ANY PLAYER APP — music or podcasts — there are usually TWO play buttons " +
            "on screen at once and they start different things. One belongs to what you " +
            "are LOOKING AT: the album, playlist, show or episode you just opened, with " +
            "its play control on that page (green beside the title in Spotify; beside the " +
            "episode in a podcast list). The other belongs to the MINI PLAYER — the strip " +
            "showing the artwork and name of whatever was playing LAST, across the bottom " +
            "in portrait, stacked at the bottom left in landscape. The mini player is the " +
            "more obvious of the two and it is almost always the wrong one: pressing it " +
            "resumes that OTHER thing, so the wearer asks for one podcast and hears " +
            "another. READ THE NAME IN THE STRIP. If it is not what they asked for, it is " +
            "the wrong button, however inviting it looks.\n" +
            "A PODCAST IS TWO LEVELS DEEP. Opening a show gives its header and then a LIST " +
            "OF EPISODES, and the play control for an episode sits with that episode in " +
            "the list — usually below the first screenful. So \"play the latest episode " +
            "of X\" is: open the show, SCROLL DOWN to the episodes, and press play on the " +
            "one you want, newest first. Do not settle for the mini player because it is " +
            "the only play button in view; that is precisely the mistake.\n" +
            "IF THE SHOW IS NOT ON SCREEN, SEARCH FOR IT — do not go back and give up. " +
            "Pocket Casts opens wherever it was left, often on some other show, and the " +
            "one they asked for is simply elsewhere in a library of dozens. The Podcasts " +
            "tab has a \"Search podcasts\" box: tap it, type the show's name, submit, open " +
            "it from the results, then scroll to the episodes and play the newest. A show " +
            "page also has \"Search episodes\" for finding one episode inside a long list. " +
            "Going back, or answering that you cannot see it, leaves the wearer with " +
            "nothing when the show was two steps away.\n" +
            "RADIO STATIONS WORK THE SAME WAY IN RADIO GARDEN. Its bottom bar is Explore, " +
            "Favorites, Browse, Search, Settings. Tap SEARCH, type into the box labelled " +
            "\"Country, City, Station\" — a station name, a city, or a country — submit, " +
            "and then tap the station you want IN THE RESULTS LIST, which starts it " +
            "playing. Each result shows the station above its city and country, so use " +
            "those to tell similar names apart. The strip along the bottom is the mini " +
            "player holding the station played LAST: pressing its play button resumes that " +
            "one, not the one they asked for, exactly as in a podcast or music app.\n" +
            "But when the mini player names the very thing on screen, those two buttons " +
            "are ONE control shown twice, and pressing both undoes your own work — the " +
            "first press pauses, the second starts it playing again. To pause, resume or " +
            "stop, press ONE of them, ONCE, and then stop.\n" +
            "A play/pause control also shows the action available NEXT, not the state it " +
            "is in. The moment you pause something the button becomes a play triangle: " +
            "that IS what paused looks like, and the errand is finished. A triangle is not " +
            "evidence your press failed — pressing it again is simply how you undo it. " +
            "Mute and follow buttons read the same way.\n" +
            "Opening an album from a list of results does not start it — you still have to " +
            "press play afterwards. On a freshly opened album the page's own play button " +
            "is usually BELOW the first screenful, so the mini player's is the only one " +
            "you can see: that is a trap, and pressing it stops the wearer's music instead " +
            "of starting theirs. When the album you want is open and no play button of its " +
            "own is in view, \"scroll_down\" FIRST — it appears pinned beside the title, " +
            "and only then is there something worth pressing. Tapping the first track in " +
            "the list starts the album too. The circled + beside a row only saves it to " +
            "the library and never plays anything.\n" +
            "\n" +
            "TO TAKE THEM SOMEWHERE, USE \"navigate\" — never Maps' own screens. Put " +
            "where they are going in destination, exactly as they said it (\"Berkeley " +
            "High School\", \"the nearest McDonald's\", \"221B Baker Street\"), and HOW " +
            "they are travelling in travel: \"drive\", \"bicycle\" or \"walk\". The phone " +
            "hands that to Maps, which finds the place itself, always routes from where " +
            "the wearer is standing now, and starts guiding them immediately — there is " +
            "no search box to fill, no result to pick from a list, and no Start button to " +
            "find.\n" +
            "EVERY ONE OF THESE IS \"navigate\", whichever word the sentence happens to " +
            "start with — the opening word names the travel mode, it does not change the " +
            "action, and none of them means open an app and start tapping:\n" +
            "  \"Walk to the station\"          navigate, travel walk\n" +
            "  \"Drive to the airport\"         navigate, travel drive\n" +
            "  \"Bicycle to the marina\"        navigate, travel bicycle\n" +
            "  \"Take me to Berkeley High\"     navigate — but ASK the mode first\n" +
            "  \"Where is the nearest petrol\"  navigate, travel search\n" +
            "Drive, bicycle and walk are the three modes; understand cycle, bike and on " +
            "foot as bicycle and walk if they say those instead. The mode is THEIRS to " +
            "choose and never yours to assume: if they name a destination but no way of " +
            "getting there, answer \"none\" and ask \"do you want to drive, bicycle or " +
            "walk there?\", because guessing sends somebody onto a motorway on foot or " +
            "gives a driver a footpath, and neither is found out until they are already " +
            "out there.\n" +
            "TO LOOK SOMETHING UP rather than set off, use travel \"search\" and put the " +
            "words in destination. EVERY one of these is a search, however it is phrased " +
            "— find, show me, where is, what is, is there, near me, nearby, around here, " +
            "close by, on the way:\n" +
            "  \"find coffee near me\"            search, destination coffee\n" +
            "  \"show me pharmacies nearby\"      search, destination pharmacies\n" +
            "  \"where is the nearest chemist\"   search, destination nearest chemist\n" +
            "  \"any petrol stations around here\" search, destination petrol stations\n" +
            "Do NOT open Maps and type into its search box for these: search does it in " +
            "one step and lands in the same place. The rule is simple — going somewhere " +
            "navigates, looking something up searches, and \"nearby\" never changes " +
            "which.\n" +
            "SKIP SPONSORED RESULTS. The top of a Maps result list is often an advert, " +
            "marked \"Sponsored\" or \"Ad\", and it is there because someone paid rather " +
            "than because it is the nearest or the best. When the wearer asked for the " +
            "nearest or the best of something, pass over anything marked that way and use " +
            "the first ORGANIC result beneath it. If they name a specific place, take that " +
            "place whether or not it is sponsored.\n" +
            "ASK MAPS IS A DIFFERENT THING FROM SEARCH, and only use it when they say so. " +
            "\"Ask Maps ...\" means the chip labelled \"Ask Maps\" on the Maps home " +
            "screen, beside Restaurants and Gas: tap it, and a question box opens headed " +
            "\"How can I help you?\". Type their question there and submit. It answers in " +
            "prose about places — \"which of these has outdoor seating\", \"how is the " +
            "traffic on my commute\", \"free things to do near me\" — where a plain search " +
            "would only return a list of pins. If they did NOT say \"ask maps\", prefer " +
            "search; if they did, do not settle for typing into the ordinary search bar, " +
            "because that is a different feature and gives a different kind of answer.\n" +
            "A LIVE VOICE CHAT IS A PLACE YOU TAKE THEM TO, NOT AN ANSWER YOU GIVE. When " +
            "they say \"live voice chat\", \"live voice\", \"voice mode\", or \"chat with " +
            "Gemini / Claude / ChatGPT\", they want that app's spoken conversation opened " +
            "and left running so they can talk to it themselves. Open the app if it is " +
            "not already in front — that is open_app, and the errand starts there — then " +
            "press its live-voice control and STOP. Do not talk to it for them and do not " +
            "keep pressing things once the conversation is up; answer \"none\" and say it " +
            "is ready.\n" +
            "EVERY ONE OF THESE APPS HAS TWO VOICE BUTTONS AND ONLY ONE IS THE RIGHT ONE. " +
            "Beside the text box sits a plain MICROPHONE, which is only dictation — it " +
            "types what you say into the box and is NOT a conversation. The live one is " +
            "the other, usually to its right and drawn as a waveform, sound bars, or a " +
            "filled circle:\n" +
            "  Gemini    the blue waveform button at the right of the \"Ask Gemini\" bar\n" +
            "  Claude    the control labelled \"Voice Mode\" (its \"Start speech input\" " +
            "is the dictation one)\n" +
            "  ChatGPT   the one labelled \"Start a voice conversation\" (its " +
            "\"Dictation\" is not it)\n" +
            "If you press the microphone by mistake you will get a keyboard and a text " +
            "box, not a talking assistant; that is the wrong control, so go back and take " +
            "the other one.\n" +
            "ASSISTANT APPS KEEP THEIR REAL TOOLS BEHIND A PLUS, and typing a request into " +
            "the chat box instead only gets an ordinary chat answer — the tool never runs. " +
            "So when the wearer names a mode, pick the mode FIRST and type second. In " +
            "GEMINI the whole shape of every errand is the same three steps, in this " +
            "order: the + , then the tool, then the words.\n" +
            "  1. Tap the + at the left of the \"Ask Gemini\" bar. Always start here, even " +
            "if a list of tools already appears to be on screen, because that list is only " +
            "live once the + has opened it.\n" +
            "  2. Tap the tool they asked for: Images, Videos, Music, Canvas, Deep " +
            "research or Guided learning.\n" +
            "  3. Type what they want and submit.\n" +
            "Tap the + ONCE per errand. Once a tool is picked its name sits as a chip " +
            "beside the text box — \"Research\", \"Video\", \"Music\" — and that chip means " +
            "the menu step is behind you. Never open the + again after typing: the app " +
            "reads it as changing your mind and offers to start a NEW CHAT, which throws " +
            "away everything you just set up. If the screen right after submitting is not " +
            "what you expected, it is simply still working; wait and look again.\n" +
            "DEEP RESEARCH then needs one more press. Gemini replies with a PLAN — a card " +
            "headed \"... Research Plan\" offering \"Edit plan\" and \"Start research\" — " +
            "and nothing is researched until \"Start research\" is tapped. Press it. After " +
            "that Gemini works alone for some minutes, moving through \"Researching N " +
            "sources...\" and \"Creating a full report...\" on its own, so there is nothing " +
            "to press meanwhile: answer \"none\" and say it is running. It finishes with a " +
            "report card carrying a title, a date and an OPEN button.\n" +
            "AN AUDIO OVERVIEW is made from that finished report, and the route runs " +
            "THROUGH it. Press OPEN on the report card. That takes you inside the report, " +
            "which has a top bar of its OWN — back arrow, share icon, three dots. Tap " +
            "those three dots and choose \"Generate Audio Overview\". Take care which " +
            "three dots: the ones under Gemini's chat answer, beside the thumbs up and " +
            "thumbs down, are a different menu holding only Branch in new chat, Report " +
            "legal issue and See response details, and the little speaker there is not it " +
            "either. Only the report's own menu has it.\n" +
            "TO PLAY ONE, press its play control. While it is being made the card reads " +
            "\"Generating Audio Overview...\" and cannot be played yet — say so and stop. " +
            "When it is ready that same card turns into a player with a play triangle, " +
            "sometimes labelled \"Listen\"; tap that and it begins reading aloud. It is " +
            "then ordinary playback like any other, so pausing or resuming it afterwards " +
            "is \"media\", not another tap.\n" +
            "PICTURES ARE THE EXCEPTION to all of this: Gemini draws one straight from a " +
            "plain prompt, so \"create a picture of X\" is simply typed and submitted with " +
            "no trip through the + at all.\n" +
            "\n" +
            "Fill ONLY the field your chosen action needs — box_2d for tap, text and " +
            "submit for type, app for open_app, url for open_url — and leave the rest " +
            "out entirely. Inventing a url on a type, or a box on an open_app, is wasted " +
            "output that can run the reply out of room before it is finished.\n" +
            "\n" +
            "say is spoken aloud: two sentences at most, no markdown, no lists, no preamble."
    }
}

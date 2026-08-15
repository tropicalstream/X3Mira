package com.x3mira.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The glasses end of the link: pull H.264 off a socket, decode it onto the
 * video surface, and push pointer work back the other way.
 *
 * Two decisions here are what make it feel like a screen rather than a
 * video call:
 *
 * RENDER IMMEDIATELY, NEVER SCHEDULE. A media player queues frames against
 * a presentation clock to keep audio in step. A remote screen has no audio
 * and no future — the newest frame IS the truth, and holding it for even
 * one vsync to be "smooth" is latency the wearer feels in their fingertips.
 * So every decoded frame is released to the surface the moment it is ready.
 *
 * COORDINATES GO BACK NORMALISED. The glasses know where the wearer
 * pointed as a fraction of the picture; the phone turns that into pixels
 * with ITS geometry. Neither side has to know the other's resolution, and
 * changing resolution mid-session cannot silently move every click.
 */
class DexLink(
    private val hostProvider: () -> String,
    private val port: Int = 7391,
    private val onState: (String) -> Unit,
    private val onGeometry: (w: Int, h: Int, inputReady: Boolean) -> Unit,
    private val onStats: (fps: Float, latencyMs: Float) -> Unit,
    private val onNotif: (String) -> Unit = {},
    private val onHudCfg: (
        notifLines: Int, readoutMode: Int, fontPct: Int,
        agentOn: Boolean, a11yContext: Boolean, pointerPct: Int
    ) -> Unit = { _, _, _, _, _, _ -> }
) {
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    /** Single worker for the return channel; see [send]. */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dexlink-send").apply { isDaemon = true }
    }
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    /** Absolute frame geometry, for turning taps into fractions. */
    @Volatile var frameW = 0
        private set
    @Volatile var frameH = 0
        private set
    @Volatile var inputReady = false
        private set

    fun start(surface: Surface) {
        if (running) return
        running = true
        current = this
        thread(name = "dexlink") { run(surface) }
    }

    fun stop() {
        running = false
        if (current === this) current = null
        // Anyone blocked on a reply would otherwise wait out its full
        // timeout against a socket that is already gone.
        waiting.values.forEach { it.offer(Reply(0, ByteArray(0))) }
        waiting.clear()
        runCatching { sender.shutdownNow() }
        runCatching { sock?.close() }
        runCatching { decoder?.stop(); decoder?.release() }
        decoder = null
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        audioTrack = null
    }

    private fun run(surface: Surface) {
        while (running) {
            try {
                val host = hostProvider()
                onState("connecting to $host…")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                sock = s
                val inp = DataInputStream(s.getInputStream().buffered(1 shl 16))
                out = DataOutputStream(s.getOutputStream())

                val magic = inp.readInt()
                if (magic != MAGIC_HELLO) throw IllegalStateException("bad hello")
                frameW = inp.readInt(); frameH = inp.readInt()
                val fps = inp.readInt()
                inputReady = inp.readInt() == 1
                val audioRate = inp.readInt()
                val audioCh = inp.readInt()
                if (audioRate > 0) openAudio(audioRate, audioCh)
                Log.i(TAG, "stream ${frameW}x$frameH @${fps} input=$inputReady audio=${audioRate}Hz")
                onGeometry(frameW, frameH, inputReady)
                onState(if (inputReady) "connected" else "connected — mirror only")

                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val fmt = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, frameW, frameH
                ).apply {
                    // Ask for the low-latency decode path where the device has
                    // one. On this SoC it is the difference between a couple of
                    // frames of pipeline and none.
                    setInteger("low-latency", 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                dec.configure(fmt, surface, null, 0)
                dec.start()
                decoder = dec

                pump(inp, dec)
            } catch (e: Throwable) {
                Log.w(TAG, "link: ${e.message}")
                onState("disconnected — retrying")
            }
            runCatching { decoder?.stop(); decoder?.release() }
            decoder = null
            runCatching { audioTrack?.stop(); audioTrack?.release() }
            audioTrack = null
            runCatching { sock?.close() }
            if (running) Thread.sleep(1200)
        }
    }

    private fun pump(inp: DataInputStream, dec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var latSum = 0f
        var window = SystemClock.elapsedRealtime()

        while (running) {
            val magic = inp.readInt()
            if (magic == MAGIC_AUDIO) {
                val alen = inp.readInt()
                val abuf = ByteArray(alen)
                inp.readFully(abuf)
                // MODE_STREAM write blocks until the track has room, which is
                // exactly the pacing we want — the audio clock throttles
                // itself and never runs ahead of the speaker.
                runCatching { audioTrack?.write(abuf, 0, alen) }
                continue
            }
            if (magic == MAGIC_NOTIF) {
                val nlen = inp.readInt()
                val nbuf = ByteArray(nlen)
                inp.readFully(nbuf)
                onNotif(String(nbuf, Charsets.UTF_8))
                continue
            }
            if (magic == MAGIC_REPLY) {
                val id = inp.readInt()
                val status = inp.readInt()
                val blen = inp.readInt()
                val buf = ByteArray(blen)
                inp.readFully(buf)
                deliver(id, status, buf)
                continue
            }
            if (magic == MAGIC_HUDCFG) {
                // Six ints, read in order and ALL consumed even if a callback
                // ignores one — a short read here desyncs the whole stream,
                // which is also why both apps must be installed together when
                // this message grows a field.
                val lines = inp.readInt()
                val readout = inp.readInt()
                val font = inp.readInt()
                val agentOn = inp.readInt()
                val a11y = inp.readInt()
                val pointer = inp.readInt()
                onHudCfg(lines, readout, font, agentOn == 1, a11y == 1, pointer)
                continue
            }
            if (magic != MAGIC_FRAME) throw IllegalStateException("desync")
            val sentNanos = inp.readLong()
            val flags = inp.readInt()
            val len = inp.readInt()
            val data = ByteArray(len)
            inp.readFully(data)

            // Both ends are Android, so elapsedRealtimeNanos is the same kind
            // of clock — not the same epoch, but the DRIFT is what matters and
            // it is small over a session. The absolute number is calibrated
            // once by the harness; here it is a relative health signal.
            val arrival = SystemClock.elapsedRealtimeNanos()

            val ii = dec.dequeueInputBuffer(20_000)
            if (ii >= 0) {
                val buf = dec.getInputBuffer(ii)
                buf?.clear()
                buf?.put(data)
                dec.queueInputBuffer(
                    ii, 0, len, arrival / 1000,
                    if (flags and 2 != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                )
            }

            // Drain everything available and show the LAST one: if we ever
            // fall behind, the right recovery is to skip forward, not to
            // play catch-up through stale pictures.
            var oi = dec.dequeueOutputBuffer(info, 0)
            var rendered = false
            while (oi >= 0) {
                val next = dec.dequeueOutputBuffer(info, 0)
                dec.releaseOutputBuffer(oi, next < 0)   // render only the newest
                if (next < 0) rendered = true
                oi = next
            }

            if (rendered) {
                frames++
                latSum += (arrival - sentNanos) / 1e6f
                val now = SystemClock.elapsedRealtime()
                if (now - window >= 1000) {
                    val fps = frames * 1000f / (now - window)
                    val lat = latSum / frames.coerceAtLeast(1)
                    // Logged as well as shown: the on-screen line is unreadable
                    // whenever a system panel covers the app, which on these
                    // glasses is often, and a measurement you cannot retrieve
                    // is not a measurement.
                    Log.i(TAG, "link-stat fps=%.1f latency_ms=%.1f frames=%d".format(fps, lat, frames))
                    onStats(fps, lat)
                    frames = 0; latSum = 0f; window = now
                }
            }
        }
    }

    private fun openAudio(rate: Int, channels: Int) {
        runCatching {
            val chMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO
                         else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                rate, chMask, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(chMask)
                        .build()
                )
                // Two device buffers: enough to ride out network jitter,
                // little enough that sound stays close to the picture.
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            audioTrack = t
            Log.i(TAG, "audio track open ${rate}Hz")
        }.onFailure { Log.w(TAG, "audio open failed: ${it.message}") }
    }

    // ── Pointer work, normalised 0..1 ────────────────────────────────

    fun tap(fx: Float, fy: Float) = send { it.writeByte('T'.code); it.writeFloat(fx); it.writeFloat(fy) }
    fun longPress(fx: Float, fy: Float) = send { it.writeByte('L'.code); it.writeFloat(fx); it.writeFloat(fy) }

    fun swipe(fx1: Float, fy1: Float, fx2: Float, fy2: Float, ms: Int) = send {
        it.writeByte('S'.code)
        it.writeFloat(fx1); it.writeFloat(fy1); it.writeFloat(fx2); it.writeFloat(fy2)
        it.writeInt(ms)
    }

    fun global(action: Int) = send { it.writeByte('G'.code); it.writeInt(action) }

    /**
     * Ask the phone to open a web address. Not a pointer gesture at all, but
     * it travels the same channel: the agent can press things and scroll them,
     * yet it cannot type, so reaching a named site by driving the address bar
     * was never possible. The phone opens it directly instead — and vets the
     * scheme at that end, since the address comes out of a vision model.
     */
    fun openUrl(url: String) = send { it.writeByte('U'.code); it.writeUTF(url) }

    /** Launch an installed app by the name a person would call it. */
    fun openApp(name: String) = send { it.writeByte('A'.code); it.writeUTF(name) }

    /**
     * Start turn-by-turn navigation, or ask the map a question.
     *
     * The travel mode travels as its own field rather than being folded into
     * the destination text: "walk to the station" and "drive to the station"
     * differ only in a word, and a wearer sent driving directions while on
     * foot finds out somewhere unpleasant. Keeping it separate means it
     * cannot be lost in a phrase.
     */
    fun navigate(destination: String, mode: String) = send {
        it.writeByte('N'.code); it.writeUTF(destination); it.writeUTF(mode)
    }

    /**
     * Tell the phone which channel our infrastructure Wi-Fi occupies, 0 for
     * none. The phone forms the NEXT P2P group there, so this one radio can
     * serve its router and the mirror without time-slicing two channels —
     * the difference between 2 fps video and 40.
     *
     * PROTOCOL NOTE: a phone that predates the 'F' verb drops the client on
     * receiving it, so the phone app must always be updated before the
     * glasses when this message is introduced.
     */
    fun reportStaFreq(hz: Int) = send { it.writeByte('F'.code); it.writeInt(hz) }

    // ── Request / reply ──────────────────────────────────────────────
    //
    // Everything above is fire-and-forget: the glasses say "tap there" and
    // never learn whether anything happened. That was survivable for a tap
    // and is not for the two things below.
    //
    // The agent's model calls are made FROM THE GLASSES today, which means
    // the eyewear needs its own route to the internet — so away from a
    // router the phone must become a hotspot, and every request is then
    // tethered traffic that carriers commonly throttle separately from
    // on-device traffic. The phone is already holding a cellular connection
    // and already has the whole calling apparatus. Handing it the request
    // over the socket that is ALREADY carrying 4 Mbps of video costs a
    // millisecond-scale local hop and removes the glasses' need for
    // internet altogether.
    //
    // Correlated by id because several can be in flight: an errand can be
    // transcribing while a previous hop's vision call is still returning,
    // and a reply that arrived on a first-come basis would be handed to
    // whichever caller happened to be waiting.

    class Reply(val status: Int, val body: ByteArray)

    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)
    private val waiting = java.util.concurrent.ConcurrentHashMap<
        Int, java.util.concurrent.ArrayBlockingQueue<Reply>>()

    /**
     * Ask the PHONE to perform an HTTPS request and hand back the response.
     * Blocking; null means the link could not carry it and the caller should
     * fall back to going out directly.
     */
    fun httpViaPhone(
        url: String,
        method: String,
        headersJson: String,
        body: ByteArray,
        timeoutMs: Long = 60_000L
    ): Reply? {
        if (out == null) return null
        val id = nextId.getAndIncrement()
        val box = java.util.concurrent.ArrayBlockingQueue<Reply>(1)
        waiting[id] = box
        try {
            send {
                it.writeByte('Q'.code)
                it.writeInt(id)
                it.writeInt(RPC_HTTP)
                it.writeUTF(url)
                it.writeUTF(method)
                it.writeUTF(headersJson)
                it.writeInt(body.size)
                it.write(body)
            }
            return box.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "rpc failed: ${t.message}")
            return null
        } finally {
            waiting.remove(id)
        }
    }

    /** Hand a reply to whoever asked for it. Called from the read loop. */
    private fun deliver(id: Int, status: Int, body: ByteArray) {
        val box = waiting.remove(id)
        if (box == null) {
            // Late: the caller already timed out and walked away. Dropping it
            // is correct — the alternative is a queue that grows forever with
            // answers nobody is listening for.
            Log.w(TAG, "reply $id arrived with nobody waiting")
            return
        }
        box.offer(Reply(status, body))
    }

    /**
     * Type into whatever the phone currently has focused, optionally pressing
     * the keyboard's action key afterwards. The phone refuses this outright
     * unless the wearer has turned typing on, so the glasses may ask freely.
     */
    fun typeText(text: String, submit: Boolean) = send {
        it.writeByte('X'.code); it.writeUTF(text); it.writeInt(if (submit) 1 else 0)
    }

    /**
     * Type, and WAIT to hear whether it landed.
     *
     * Fire-and-forget was the wrong shape for this one action. Every other
     * command either obviously worked or obviously did not — a tap that missed
     * still moved something — but text going nowhere looks exactly like text
     * arriving: the screen is unchanged either way. So the agent believed it
     * had typed, looked, saw nothing, sent the same words again and hit its own
     * repeat guard, which reads as giving up.
     *
     * Returns null if the link could not carry it at all, which the caller
     * must treat as failure rather than success.
     */
    fun typeAndConfirm(text: String, submit: Boolean, timeoutMs: Long = 8_000L): Reply? {
        if (out == null) return null
        val id = nextId.getAndIncrement()
        val box = java.util.concurrent.ArrayBlockingQueue<Reply>(1)
        waiting[id] = box
        try {
            send {
                it.writeByte('Q'.code)
                it.writeInt(id)
                it.writeInt(RPC_TYPE)
                it.writeUTF(text)                       // envelope's url slot
                it.writeUTF(if (submit) "1" else "0")   // ...method slot
                it.writeUTF("")
                it.writeInt(0)
            }
            return box.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "type rpc failed: ${t.message}")
            return null
        } finally {
            waiting.remove(id)
        }
    }

    /**
     * Outbound pointer work, OFF the main thread.
     *
     * Every one of these is called from a gesture callback, which is the UI
     * thread, and a socket write there is a NetworkOnMainThreadException —
     * whose message is null, so the old runCatching swallowed it into a
     * silent no-op and the pad appeared to do nothing at all. One worker
     * also serialises the writes, so two gestures can never interleave
     * halfway through a message.
     */
    private fun send(block: (DataOutputStream) -> Unit) {
        val o = out
        if (o == null) { Log.w(TAG, "send dropped: not connected"); return }
        sender.execute {
            runCatching { synchronized(o) { block(o); o.flush() } }
                .onFailure { Log.w(TAG, "send failed: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    companion object {
        const val TAG = "X3Dex"
        const val MAGIC_HELLO = 0xDEC0DE00.toInt()
        const val MAGIC_FRAME = 0xDEC0DE01.toInt()
        const val MAGIC_AUDIO = 0xDEC0DE02.toInt()
        const val MAGIC_NOTIF = 0xDEC0DE03.toInt()
        const val MAGIC_HUDCFG = 0xDEC0DE04.toInt()
        const val MAGIC_REPLY = 0xDEC0DE05.toInt()

        /** RPC kinds carried by the 'Q' verb. */
        const val RPC_HTTP = 1
        const val RPC_TYPE = 2

        /**
         * The live link, for callers that are nowhere near the Activity.
         *
         * The agent's HTTP goes through three different classes, none of
         * which holds a DexLink, and threading one into each would mean
         * three constructors changed to express "use the socket if there is
         * one". This is the seam instead.
         */
        @Volatile
        var current: DexLink? = null
        const val ACTION_BACK = 1
        const val ACTION_HOME = 2
        const val ACTION_RECENTS = 3
    }
}

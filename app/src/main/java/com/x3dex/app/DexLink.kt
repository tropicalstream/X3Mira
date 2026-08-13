package com.x3dex.app

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
    private val onStats: (fps: Float, latencyMs: Float) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
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
        thread(name = "dexlink") { run(surface) }
    }

    fun stop() {
        running = false
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

    private inline fun send(block: (DataOutputStream) -> Unit) {
        val o = out ?: return
        runCatching { synchronized(o) { block(o); o.flush() } }
    }

    companion object {
        const val TAG = "X3Dex"
        const val MAGIC_HELLO = 0xDEC0DE00.toInt()
        const val MAGIC_FRAME = 0xDEC0DE01.toInt()
        const val MAGIC_AUDIO = 0xDEC0DE02.toInt()
        const val ACTION_BACK = 1
        const val ACTION_HOME = 2
        const val ACTION_RECENTS = 3
    }
}

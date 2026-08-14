package com.x3mira.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The assistant: a neon-drawn face at the end of the dim readout, showing
 * without a legend whether she is listening or working.
 *
 * She replaces the "✦" and "⚙" glyphs the hub used. A wearer should not
 * have to recall which character meant which machine while a black display
 * is the only thing in front of them — a face that visibly listens, or
 * visibly thinks, is read rather than decoded. She is also the app's one
 * piece of warmth: Raynos is a black screen all day, and the single thing
 * on it should feel like someone keeping you company.
 *
 * A FACE, nothing else — no bust, no shoulders. At readout size a body
 * only steals pixels from the part that carries the expression, and the
 * head alone fills the box with the features large enough to actually read
 * on this projector.
 *
 * NEON, drawn the way a neon tube really works: a wide, dim, saturated
 * halo with a narrow white-hot core inside it. Three passes of decreasing
 * width and increasing brightness — no BlurMaskFilter, which is the
 * obvious tool and the wrong one here, since it is unreliable under
 * hardware acceleration and costs an offscreen pass per glyph. Layered
 * strokes cost three cheap draws and look like glass tubing.
 *
 * On a waveguide black is transparent, so only the ink glows: line art is
 * the one style that can be bright and saturated without becoming a lamp
 * in the wearer's eye.
 *
 * Everything is relative to (cx, cy, size), so she scales with the readout
 * and nothing needs re-tuning.
 */
object AssistantFigure {

    // A neon sign's palette: saturated hues that survive being thinned to
    // a stroke. Each state gets its OWN hue, so the wearer reads state
    // from colour at the edge of vision before they read the drawing.
    // Idle was magenta for months and the wearer read it as a FAULT, twice
    // — "the avatar is purple, it's not responding". They were half right:
    // purple was the colour she showed whenever something upstream was
    // broken, because broken states leave her idle. A resting colour that
    // doubles as the face of every failure is a bad resting colour. She now
    // idles in the readout's own steel cyan — the app's sign colour — and
    // BRIGHTENS toward electric white-cyan when a session is live, so
    // "resting" and "with you" differ in intensity and ornament (arcs,
    // dots, mouth) rather than in a hue the wearer learned to distrust.
    private const val RGB_IDLE = 0x004FA8C4    // steel cyan — her at rest
    private const val RGB_GEMINI = 0x0033EFFF  // electric cyan — listening
    private const val RGB_AGENT = 0x00FFAE3A   // amber — working
    private const val RGB_REC_LED = 0x00FF2E2E // red — the recording light
    private const val RGB_MUTED = 0x00FF5A5A  // soft red — media muted

    /**
     * Smoothed speech level. Object state is safe here: draw() only ever
     * runs on the main thread, and smoothing across frames is exactly what
     * makes the mouth read as speech rather than static.
     */
    private var mouthLevel = 0f

    /** Smoothed keyboard glow, for the same reason as [mouthLevel]. */
    private var keyLevel = 0f

    /**
     * @param state [AssistantState] bits.
     * @param phase 0..1 animation phase; read while dots animate.
     * @param level live voice amplitude 0..1 — drives the mouth while she
     *   talks, so her lips move to the actual audio instead of a canned
     *   loop.
     * @param agentLevel the AGENT voice's amplitude 0..1, or negative when
     *   that voice cannot report one — flashes the keyboard in time with
     *   the words it is speaking.
     * @param paint the caller's paint, reconfigured here and left dirty.
     * @param brightness the readout's 0..1 slider, so she dims with
     *   everything else on a night display rather than becoming the
     *   brightest thing in the room.
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        state: Int,
        phase: Float,
        level: Float,
        agentLevel: Float,
        paint: Paint,
        brightness: Float
    ) {
        val listening = AssistantState.hasListening(state)
        val thinking = AssistantState.hasThinking(state)
        val talking = AssistantState.hasTalking(state)
        val session = AssistantState.hasSession(state)
        val agent = AssistantState.hasAgent(state)
        val agentSpeaking = AssistantState.hasAgentSpeaking(state)

        val base = brightness.coerceIn(0.05f, 1f) * 255f
        // Active states sit near the top of what the wearer allows; idle
        // sits low but never invisible — her being faintly THERE is what
        // separates "dimmed" from "dead".
        val alpha = when {
            session || agent -> (base * 2.6f).toInt().coerceIn(150, 255)
            else -> (base * 1.9f).toInt().coerceIn(120, 240)
        }
        // Cyan wins the face whenever a session is live: the wearer is in a
        // CONVERSATION with her, which is the relationship in front; an
        // errand running underneath speaks for itself in the amber dots.
        val rgb = when {
            session -> RGB_GEMINI
            agent -> RGB_AGENT
            else -> RGB_IDLE
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Head proportions, not a circle: a face is markedly taller than
        // it is wide, and the first cut used one radius for both, which
        // read as a squashed oval lying on its side rather than a person.
        val rx = size * 0.25f
        val ry = size * 0.38f
        val core = size * 0.050f

        // ── Jaw and chin ─────────────────────────────────────────────
        // Open at the top: the hair caps the head, and a closed ring
        // would read as a coin with a face scratched on it.
        val jaw = Path().apply {
            moveTo(cx - rx, cy - ry * 0.18f)
            cubicTo(
                cx - rx * 0.98f, cy + ry * 0.74f,
                cx - rx * 0.46f, cy + ry * 1.02f,
                cx, cy + ry * 1.02f
            )
            cubicTo(
                cx + rx * 0.46f, cy + ry * 1.02f,
                cx + rx * 0.98f, cy + ry * 0.74f,
                cx + rx, cy - ry * 0.18f
            )
        }
        neonPath(canvas, paint, jaw, rgb, alpha, core)

        // ── Hair: a bob that FRAMES the face ─────────────────────────
        // The silhouette is what identifies her at this size — features
        // are barely present, so the hairline does the work. Two earlier
        // cuts failed here and both are worth remembering: a low side bun
        // read as a lump growing off her jaw, and a plain crown arc with a
        // centre-parting stroke read as a helmet with an antenna. What
        // works is hair OUTSIDE the face on both sides — down past the
        // jaw, up over the crown, down again — so the face sits inside it.
        val hair = Path().apply {
            moveTo(cx - rx * 1.36f, cy + ry * 0.66f)
            cubicTo(
                cx - rx * 1.44f, cy - ry * 0.34f,
                cx - rx * 1.18f, cy - ry * 1.22f,
                cx, cy - ry * 1.24f
            )
            cubicTo(
                cx + rx * 1.18f, cy - ry * 1.22f,
                cx + rx * 1.44f, cy - ry * 0.34f,
                cx + rx * 1.36f, cy + ry * 0.66f
            )
        }
        neonPath(canvas, paint, hair, rgb, alpha, core)

        // ── Eyes ─────────────────────────────────────────────────────
        // Calm downward curves — lids, not stares. Two dots would read as
        // alarm at this size, and a face that stares out of a HUD all day
        // is unnerving to wear.
        val eyeY = cy - ry * 0.02f
        val eyeW = rx * 0.46f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * rx * 0.40f
            val eye = Path().apply {
                moveTo(ex - eyeW * 0.5f, eyeY)
                quadTo(ex, eyeY + ry * 0.20f, ex + eyeW * 0.5f, eyeY)
            }
            neonPath(canvas, paint, eye, rgb, alpha, core * 0.82f)
        }

        // ── CAMERA: she puts glasses on, with a lit corner LED ───────
        // In dim mode the camera preview is hidden, so nothing on the
        // display tells the wearer the lens is live — which is how a
        // "what time is it" gets answered about the room. She wears the
        // camera for them: two rims over her eyes, a bridge between, and a
        // small red light pulsing at the outer corner, the universal "this
        // is recording" tell. Drawn over the eyes she already has, so a
        // glance reads "her, wearing a camera" rather than a new object.
        if (AssistantState.hasCamera(state)) {
            val lensR = rx * 0.42f
            val lensCy = eyeY + ry * 0.02f
            for (side in intArrayOf(-1, 1)) {
                val lx = cx + side * rx * 0.44f
                val rim = Path().apply {
                    addRoundRect(
                        RectF(lx - lensR, lensCy - lensR * 0.72f,
                            lx + lensR, lensCy + lensR * 0.72f),
                        lensR * 0.5f, lensR * 0.5f, Path.Direction.CW
                    )
                }
                neonPath(canvas, paint, rim, rgb, alpha, core * 0.6f)
            }
            // Bridge across the nose, joining the two rims.
            val bridge = Path().apply {
                moveTo(cx - rx * 0.02f, lensCy)
                lineTo(cx + rx * 0.02f, lensCy)
            }
            neonPath(canvas, paint, bridge, rgb, alpha, core * 0.6f)
            // Temple arm to the right rim, reaching back toward the hair.
            val temple = Path().apply {
                moveTo(cx + rx * 0.44f + lensR, lensCy)
                lineTo(cx + rx * 1.02f, lensCy - ry * 0.06f)
            }
            neonPath(canvas, paint, temple, rgb, alpha, core * 0.55f)

            // The recording LED: red, always — a camera-on light is red the
            // world over, and letting it take the face's cyan/amber tint
            // would bury the one mark whose whole job is to stand out. It
            // breathes so the eye catches it even in the corner of vision;
            // a steady dot reads as part of the frames, a pulsing one reads
            // as ON.
            val breathe = 0.55f + 0.45f *
                kotlin.math.sin(phase * 2f * Math.PI.toFloat())
            val ledAlpha = (alpha * (0.5f + 0.5f * breathe)).toInt().coerceIn(90, 255)
            val ledX = cx + rx * 0.44f + lensR
            val ledY = lensCy - lensR * 0.72f
            val led = Path().apply {
                addCircle(ledX, ledY, size * (0.020f + 0.010f * breathe), Path.Direction.CW)
            }
            neonPath(canvas, paint, led, RGB_REC_LED, ledAlpha, core * 0.7f)
        }

        // ── Mouth ────────────────────────────────────────────────────
        // Talking: an open ellipse whose height follows the LIVE audio
        // level, smoothed across frames so consonants read as movement
        // rather than flicker. Her lips move to what she is actually
        // saying — a canned open-close loop looks like a fish; amplitude
        // does not. At rest: one short line, faintly upturned — composed
        // rather than blank, and too small ever to read as a grin.
        val mouthY = cy + ry * 0.56f
        if (talking) {
            val target = level.coerceIn(0f, 1f)
            // Fast attack, slower release — speech onsets are sharp and
            // trails soft, and the asymmetry is what sells it as a voice.
            mouthLevel += (target - mouthLevel) * (if (target > mouthLevel) 0.65f else 0.30f)
            val openW = rx * 0.34f
            val openH = ry * (0.05f + 0.30f * mouthLevel)
            val mouth = Path().apply {
                addOval(
                    RectF(cx - openW, mouthY - openH, cx + openW, mouthY + openH),
                    Path.Direction.CW
                )
            }
            neonPath(canvas, paint, mouth, rgb, alpha, core * 0.72f)
        } else {
            mouthLevel = 0f
            val mouth = Path().apply {
                moveTo(cx - rx * 0.22f, cy + ry * 0.52f)
                quadTo(cx, cy + ry * 0.64f, cx + rx * 0.22f, cy + ry * 0.52f)
            }
            neonPath(canvas, paint, mouth, rgb, alpha, core * 0.75f)
        }

        // ── LISTENING: sound arcs beside her ─────────────────────────
        // Beside the head rather than at the mouth: a live session is
        // mostly her HEARING the wearer, and arcs at the mouth would read
        // as her talking over them. Static — the colour shift already
        // announces the session, and stillness costs no frames.
        if (listening && !talking) {
            for (i in 1..2) {
                val ar = rx * (1.30f + 0.42f * i)
                val box = RectF(cx - ar, cy - ar, cx + ar, cy + ar)
                val arc = Path().apply { addArc(box, -30f, 60f) }
                neonPath(canvas, paint, arc, RGB_GEMINI, alpha, core * 0.8f)
            }
        }

        // ── MUTED: a muted-speaker mark to her left ──────────────────
        // The dim double-tap mutes system MEDIA, and a muted video is
        // indistinguishable from a broken one — so the mute has to show.
        // A speaker cone with a slash is the mark the whole world reads as
        // "no sound", in red so it never blends into her face colour. ONE
        // AT EACH EAR — the wearer asked for the symmetry, and it earns
        // its keep: a single small mark can hide behind a moment of
        // inattention, while a matched pair reads as a STATE.
        //
        // The one wrinkle is the right ear, which is also where the
        // listening arcs live. She can be muted and listening at once, and
        // drawing both in the same spot makes an unreadable tangle — so
        // while the arcs are up, the right mark yields and the left one
        // carries the mute alone. The arcs are transient; the pair returns
        // the moment they go.
        if (AssistantState.hasMuted(state)) {
            val arcsUp = listening && !talking
            for (side in intArrayOf(-1, 1)) {
                if (side > 0 && arcsUp) continue
                val sx = cx + side * rx * 1.7f
                val sy = cy + ry * 0.02f
                // Mirrored horizontally: m flips the x-offsets so each
                // cone opens TOWARD her head, like a speaker aimed at the
                // ear it marks.
                val m = -side.toFloat()
                val sw = size * 0.10f
                val sh = size * 0.09f
                // Speaker: a small square body with a triangular cone
                // opening toward the head.
                val speaker = Path().apply {
                    moveTo(sx - m * sw, sy - sh * 0.5f)
                    lineTo(sx - m * sw * 0.3f, sy - sh * 0.5f)
                    lineTo(sx + m * sw, sy - sh)          // cone top
                    lineTo(sx + m * sw, sy + sh)          // cone bottom
                    lineTo(sx - m * sw * 0.3f, sy + sh * 0.5f)
                    lineTo(sx - m * sw, sy + sh * 0.5f)
                    close()
                }
                neonPath(canvas, paint, speaker, RGB_MUTED, alpha, core * 0.5f)
                // The slash — the "off" mark — through the cone.
                val slash = Path().apply {
                    moveTo(sx + m * sw * 1.5f, sy - sh * 1.4f)
                    lineTo(sx - m * sw * 0.2f, sy + sh * 1.4f)
                }
                neonPath(canvas, paint, slash, RGB_MUTED, alpha, core * 0.55f)
            }
        }

        // ── THINKING: three dots above her head ─────────────────────
        // HER thinking, and only hers. Dots above a head are someone
        // composing a thought — so when the page agent works, that gets
        // its own picture below instead (hands on a keyboard), and the two
        // never have to share one symbol or one colour.
        if (thinking) {
            val dotY = cy - ry * 1.66f
            val gap = size * 0.15f
            for (i in 0..2) {
                val local = (phase + i / 3f) % 1f
                // Triangle wave: swell and fade with no pop at the wrap.
                val swell = if (local < 0.5f) local * 2f else (1f - local) * 2f
                val a = (alpha * (0.34f + 0.66f * swell)).toInt().coerceIn(0, 255)
                val dot = Path().apply {
                    addCircle(
                        cx + (i - 1) * gap, dotY,
                        size * (0.028f + 0.016f * swell),
                        Path.Direction.CW
                    )
                }
                neonPath(canvas, paint, dot, RGB_GEMINI, a, core * 0.7f)
            }
        }

        // ── WORKING: her hands at a keyboard, below the face ─────────
        // The page agent is a thing that TYPES — it clicks and fills and
        // navigates a page on the wearer's behalf — so hands at a keyboard
        // say what it is doing far more directly than an abstract mark
        // could. Amber throughout, so it stays legible as "the errand" even
        // when her face has gone cyan for a live session running over it.
        //
        // The keys LIGHT when the agent speaks its result. That is the
        // moment the wearer should look up for, and it is the one part of
        // an errand they cannot see: everything else happens inside a
        // window that is not drawn.
        if (agent || agentSpeaking) {
            val kbTop = cy + ry * 1.52f
            val kbBot = cy + ry * 1.92f
            val kbHalf = size * 0.44f

            // The board: a shallow slab. Drawn as a closed path rather than
            // four strokes so the corners join cleanly at this size.
            val board = Path().apply {
                moveTo(cx - kbHalf, kbTop)
                lineTo(cx + kbHalf, kbTop)
                lineTo(cx + kbHalf * 0.90f, kbBot)
                lineTo(cx - kbHalf * 0.90f, kbBot)
                close()
            }
            neonPath(canvas, paint, board, RGB_AGENT, alpha, core * 0.72f)

            // Keys: five ticks along the board. While the agent merely
            // works they sit at the board's own steady brightness. While it
            // SPEAKS they flash IN SYNC with the voice — every key riding
            // the same measured amplitude, so the board pulses on syllables
            // and rests in the gaps between words. That synchrony is the
            // whole point: a keyboard blinking to its own rhythm next to a
            // voice saying something else reads as decoration, while one
            // that moves with the words reads as the source of them.
            //
            // When the voice cannot report an amplitude (the device
            // fallback speaks through Android's engine, which hands back no
            // samples) a crest travels left to right instead — visibly
            // alive, and honest about not knowing the waveform.
            val keyY0 = kbTop + (kbBot - kbTop) * 0.34f
            val keyY1 = kbBot - (kbBot - kbTop) * 0.20f
            val synced = agentSpeaking && agentLevel >= 0f
            if (synced) {
                // Smoothed like the mouth, and for the same reason: a level
                // sampled every 40ms and drawn raw strobes.
                val target = agentLevel.coerceIn(0f, 1f)
                keyLevel += (target - keyLevel) *
                    (if (target > keyLevel) 0.60f else 0.25f)
            } else {
                keyLevel = 0f
            }
            for (i in 0..4) {
                val kx = cx + (i - 2) * (kbHalf * 0.36f)
                val key = Path().apply {
                    moveTo(kx, keyY0)
                    lineTo(kx, keyY1)
                }
                val keyAlpha = when {
                    synced ->
                        (alpha * (0.30f + 0.70f * keyLevel)).toInt().coerceIn(0, 255)
                    agentSpeaking -> {
                        val local = (phase - i / 5f + 1f) % 1f
                        val crest = if (local < 0.5f) local * 2f else (1f - local) * 2f
                        (alpha * (0.35f + 0.65f * crest)).toInt().coerceIn(0, 255)
                    }
                    else -> (alpha * 0.55f).toInt().coerceIn(0, 255)
                }
                neonPath(canvas, paint, key, RGB_AGENT, keyAlpha, core * 0.60f)
            }

            // Hands: one arch each side, resting over the board with three
            // short fingers reaching down to it. At this size a hand can
            // only be a silhouette and a hint of fingers — anything more
            // detailed turns to mud.
            //
            // THE FINGERS TYPE while the agent works. Each presses on its
            // own beat: the phase is multiplied so strokes come several per
            // cycle rather than one, and offset per finger AND per hand so
            // the two never move as a pair — hands that rise and fall in
            // unison read as a machine stamping, not as someone typing.
            // The travel was 1.4px in the first cut, which is invisible on
            // this projector — the wearer reported the hands as static. It
            // is ~4px now: still a small movement on a 48px figure, but
            // above the threshold where a glance registers it as motion
            // rather than as a slightly fuzzy line.
            val typing = agent
            for (side in intArrayOf(-1, 1)) {
                val hx = cx + side * size * 0.20f
                // The whole hand settles a touch while its fingers work.
                val handBob = if (typing) {
                    val hb = (phase * 2f + if (side < 0) 0f else 0.5f) % 1f
                    (if (hb < 0.5f) hb * 2f else (1f - hb) * 2f) * size * 0.035f
                } else {
                    0f
                }
                val hTop = kbTop - size * 0.15f + handBob
                val hand = Path().apply {
                    moveTo(hx - size * 0.11f, kbTop - size * 0.01f + handBob)
                    cubicTo(
                        hx - size * 0.12f, hTop,
                        hx + size * 0.12f, hTop,
                        hx + size * 0.11f, kbTop - size * 0.01f + handBob
                    )
                }
                neonPath(canvas, paint, hand, RGB_AGENT, alpha, core * 0.62f)
                for (f in -1..1) {
                    val fx = hx + f * size * 0.075f
                    // Distinct beats per finger and per hand: 3 strokes a
                    // cycle, each finger a third out of step, each hand a
                    // sixth — so no two land together.
                    val press = if (typing) {
                        val local = (phase * 3f + (f + 1) * 0.33f +
                            (if (side < 0) 0f else 0.17f)) % 1f
                        val t = if (local < 0.5f) local * 2f else (1f - local) * 2f
                        t * size * 0.085f
                    } else {
                        0f
                    }
                    val finger = Path().apply {
                        moveTo(fx, kbTop - size * 0.055f + handBob + press)
                        lineTo(fx, kbTop + size * 0.005f + handBob + press)
                    }
                    // A pressed finger is brighter — it is the one touching
                    // the lit board.
                    val fa = if (typing) {
                        (alpha * (0.65f + 0.35f * (press / (size * 0.085f))))
                            .toInt().coerceIn(0, 255)
                    } else {
                        alpha
                    }
                    neonPath(canvas, paint, finger, RGB_AGENT, fa, core * 0.50f)
                }
            }
        }
    }

    /**
     * One neon stroke: a saturated halo, a brighter middle, and a nearly
     * white core. Drawing the same path three times is what gives a flat
     * vector line the look of a lit tube.
     */
    private fun neonPath(
        canvas: Canvas,
        paint: Paint,
        path: Path,
        rgb: Int,
        alpha: Int,
        coreWidth: Float
    ) {
        // Halo — wide and faint. Capped well below the core's alpha so a
        // dense drawing never smears into a glowing blob.
        paint.strokeWidth = coreWidth * 3.4f
        paint.color = ((alpha * 0.22f).toInt().coerceIn(0, 255) shl 24) or rgb
        canvas.drawPath(path, paint)

        // Body — the colour the wearer actually names.
        paint.strokeWidth = coreWidth * 1.8f
        paint.color = ((alpha * 0.55f).toInt().coerceIn(0, 255) shl 24) or rgb
        canvas.drawPath(path, paint)

        // Core — the hot filament. Pushed toward white so the tube reads
        // as LIT rather than merely coloured; that whitening is most of
        // what makes neon look like neon.
        paint.strokeWidth = coreWidth
        paint.color = (alpha shl 24) or whiten(rgb, 0.68f)
        canvas.drawPath(path, paint)
    }

    /** Mix an RGB toward white by [amount] (0..1). */
    private fun whiten(rgb: Int, amount: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val nr = (r + (255 - r) * amount).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * amount).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * amount).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }
}

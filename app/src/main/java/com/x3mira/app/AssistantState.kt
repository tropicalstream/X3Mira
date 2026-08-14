package com.x3mira.app

/**
 * What the assistant is doing, as bits.
 *
 * This replaces the hub's status GLYPHS — a string of "✦" and "⚙" drawn
 * beside the battery. On a readout that is the only thing on screen, two
 * small symbols asked the wearer to remember a legend; a figure who
 * visibly listens, thinks, talks or works does not.
 *
 * Four bits rather than one enum because these genuinely overlap: she can
 * be speaking her answer while an errand she already handed over is still
 * running, and the face has to show both at once. The split of the old
 * GEMINI bit into LISTENING / THINKING / TALKING follows the session's
 * real phases — each gets its own look, because "she heard me", "she is
 * deciding" and "she is answering" are different facts and the wearer can
 * only hear one of them.
 */
object AssistantState {

    const val IDLE = 0

    /** Mic is hers — the session is live and waiting on the wearer. */
    const val LISTENING = 1

    /** The wearer's turn ended; the model is composing its reply. */
    const val THINKING = 2

    /** Model audio is playing — she is speaking. */
    const val TALKING = 4

    /** The page agent (or a native errand) is working. */
    const val AGENT = 8

    /** The agent's own voice is sounding — it is reporting its result. */
    const val AGENT_SPEAKING = 16

    /**
     * The camera is streaming — Gemini sees what the wearer sees.
     *
     * This one is not about what she is DOING; it is a privacy fact the
     * wearer must be able to read at a glance. In dim mode the camera
     * preview is hidden, so the only sign the lens is live is her: she puts
     * glasses on, with a lit indicator at the corner. A camera that can be
     * on with no visible tell is exactly how a wearer ends up asking the
     * time and being answered about the room.
     */
    const val CAMERA = 32

    /**
     * System MEDIA audio is muted — the double-tap-in-dim mute. Like
     * [CAMERA] this is a fact the wearer needs to read, not something she
     * is doing: a muted video looks identical to a broken one, so the mute
     * has to show. She wears a muted-speaker mark while it holds.
     */
    const val MUTED = 64

    fun of(
        listening: Boolean,
        thinking: Boolean,
        talking: Boolean,
        agentBusy: Boolean,
        agentSpeaking: Boolean = false,
        camera: Boolean = false,
        muted: Boolean = false
    ): Int =
        (if (listening) LISTENING else 0) or
            (if (thinking) THINKING else 0) or
            (if (talking) TALKING else 0) or
            (if (agentBusy) AGENT else 0) or
            (if (agentSpeaking) AGENT_SPEAKING else 0) or
            (if (camera) CAMERA else 0) or
            (if (muted) MUTED else 0)

    fun hasListening(state: Int) = (state and LISTENING) != 0
    fun hasThinking(state: Int) = (state and THINKING) != 0
    fun hasTalking(state: Int) = (state and TALKING) != 0
    fun hasAgent(state: Int) = (state and AGENT) != 0
    fun hasAgentSpeaking(state: Int) = (state and AGENT_SPEAKING) != 0
    fun hasCamera(state: Int) = (state and CAMERA) != 0
    fun hasMuted(state: Int) = (state and MUTED) != 0

    /** Any live voice-session state — what the old GEMINI bit meant. */
    fun hasSession(state: Int) = (state and (LISTENING or THINKING or TALKING)) != 0

    /** True when something is animated and the readout needs a timer. */
    fun wantsAnimation(state: Int) =
        (state and (THINKING or TALKING or AGENT or AGENT_SPEAKING or CAMERA)) != 0
}

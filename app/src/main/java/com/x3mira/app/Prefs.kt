package com.x3mira.app

import android.content.Context

/**
 * The three things a wearer actually needs to change, and nothing else.
 * Resolution is expressed as a WIDTH the phone should capture at; the phone
 * keeps its own aspect, so one number is the whole control.
 */
object Prefs {
    private const val FILE = "x3dex"
    private const val K_HOST = "host"
    private const val K_WIDTH = "width"
    /**
     * Wi-Fi Direct instead of a shared network. ON by default and stored on
     * the GLASSES rather than pushed from the phone, because it is the setting
     * that decides whether a link can exist at all — a preference that only
     * arrives over the link is no use to someone who has no link.
     *
     * On by default because P2P is what lets the pair work OUTSIDE, where
     * there is no router to meet on — and the radio-contention worry that
     * once kept it off is answered now that the group forms on the channel
     * the glasses already live on. The toggle remains for a wearer who
     * genuinely wants LAN-only.
     */
    private const val K_P2P = "p2p"

    private const val K_SPEED = "speed"

    /**
     * The last HUD config the phone pushed.
     *
     * These are the WEARER's choices but they live on the PHONE, which means
     * the glasses only learn them when a link exists. Held in memory alone,
     * every restart put the HUD back to factory defaults — date+time+battery
     * at one banner line — and the wearer watched their settings "get wiped"
     * whenever the app restarted or the phone was out of reach. Outdoors with
     * no phone in range they were never right at all.
     *
     * The phone stays the source of truth and overwrites these on connect
     * (it pushes the config as its first act). This is only so the HUD is
     * already right in the meantime.
     */
    private const val K_HUD_LINES = "hud_lines"
    private const val K_HUD_READOUT = "hud_readout"
    private const val K_HUD_FONT = "hud_font"
    private const val K_HUD_AGENT = "hud_agent"
    private const val K_HUD_TYPING = "hud_typing"
    private const val K_HUD_POINTER = "hud_pointer"

    /** Presets, coarsest first. "Best" is the default and means half-native. */
    val WIDTHS = intArrayOf(360, 540, 720, 1080)

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun host(c: Context): String = p(c).getString(K_HOST, "192.168.1.10")!!
    fun setHost(c: Context, v: String) = p(c).edit().putString(K_HOST, v).apply()

    /**
     * 720 by default: on a 640x480 panel it is the highest width that still
     * buys visible detail after the downscale, and it costs half the pixels
     * of native. 1080 is offered for reading dense text by zooming, not
     * because the panel can resolve it.
     */
    fun width(c: Context): Int = p(c).getInt(K_WIDTH, 720)
    fun setWidth(c: Context, v: Int) = p(c).edit().putInt(K_WIDTH, v).apply()

    /** Cursor gain. The pad is small; 2.5 crosses the viewport in one swipe. */
    fun speed(c: Context): Float = p(c).getFloat(K_SPEED, 2.5f)

    fun p2p(c: Context): Boolean = p(c).getBoolean(K_P2P, true)
    fun setP2p(c: Context, v: Boolean) = p(c).edit().putBoolean(K_P2P, v).apply()
    fun setSpeed(c: Context, v: Float) = p(c).edit().putFloat(K_SPEED, v).apply()

    /**
     * The six fields of a HUDCFG message, in wire order. Defaults match the
     * ones MirrorHud and MainActivity were born with, so a pair that has
     * never connected behaves exactly as before.
     */
    data class HudCfg(
        val lines: Int, val readout: Int, val fontPct: Int,
        val agentOn: Boolean, val typing: Boolean, val pointerPct: Int
    )

    fun hudCfg(c: Context): HudCfg = p(c).let {
        HudCfg(
            lines = it.getInt(K_HUD_LINES, 1),
            readout = it.getInt(K_HUD_READOUT, 3),
            fontPct = it.getInt(K_HUD_FONT, 100),
            agentOn = it.getBoolean(K_HUD_AGENT, true),
            typing = it.getBoolean(K_HUD_TYPING, false),
            pointerPct = it.getInt(K_HUD_POINTER, 80)
        )
    }

    fun setHudCfg(c: Context, v: HudCfg) = p(c).edit()
        .putInt(K_HUD_LINES, v.lines)
        .putInt(K_HUD_READOUT, v.readout)
        .putInt(K_HUD_FONT, v.fontPct)
        .putBoolean(K_HUD_AGENT, v.agentOn)
        .putBoolean(K_HUD_TYPING, v.typing)
        .putInt(K_HUD_POINTER, v.pointerPct)
        .apply()
}

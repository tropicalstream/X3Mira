package com.x3dex.app

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
    private const val K_SPEED = "speed"

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
    fun setSpeed(c: Context, v: Float) = p(c).edit().putFloat(K_SPEED, v).apply()
}

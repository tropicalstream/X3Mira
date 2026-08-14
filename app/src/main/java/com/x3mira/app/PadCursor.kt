package com.x3mira.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The mouse-mode pointer.
 *
 * This is the one thing in the app allowed to sit ON the picture — the HUD is
 * deliberately banded off it, but a pointer that avoided the mirror would have
 * nothing to point at. It earns that by being temporary: it appears on a
 * double tap, and it leaves on its own after a few seconds of stillness.
 *
 * Drawn as a light ring around a dark core with a dark halo behind both,
 * rather than as a plain dot. A single-colour cursor disappears the moment it
 * crosses something the same shade — which on a phone screen is constantly —
 * and hunting for your own pointer is worse than having none. This way some
 * part of it always contrasts, over white paper or a dark video alike.
 */
class PadCursor(ctx: Context) : View(ctx) {

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC000000.toInt()
        strokeWidth = 4.5f
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF34E1C8.toInt()      // the suite's cyan, so it reads as ours
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = kotlin.math.min(width, height) * 0.5f - 3f
        canvas.drawCircle(cx, cy, r, halo)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r * 0.34f, core)
    }

    companion object {
        /** Side of the cursor in viewport pixels. */
        const val SIZE = 18
    }
}

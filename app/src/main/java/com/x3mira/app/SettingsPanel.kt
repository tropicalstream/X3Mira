package com.x3mira.app

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Triple-tap settings, drawn over the video on black.
 *
 * Buttons, not sliders, and every row cycles its value on click. There is
 * no keyboard on these glasses and the cursor is relative and coarse, so a
 * control that needs precision is a control the wearer cannot use. Cycling
 * is the one gesture that already works everywhere else in this suite.
 */
class SettingsPanel(
    private val activity: Activity,
    private val onApply: () -> Unit
) {
    val view: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(0xF0000814.toInt())
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        // Clickable so a stray tap on empty panel space is swallowed here
        // rather than falling through and clicking the phone underneath.
        isClickable = true
    }

    private val col = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 12, 18, 12)
    }

    private val resRow: TextView
    private val speedRow: TextView
    private val hostRow: TextView
    private var globalSender: ((Int) -> Unit)? = null

    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    init {
        col.addView(label("X3Mira settings", 15f, 0xFF7FDBFF.toInt(), 0, 6))

        resRow = row {
            val cur = Prefs.width(activity)
            val i = Prefs.WIDTHS.indexOf(cur).let { if (it < 0) 2 else it }
            Prefs.setWidth(activity, Prefs.WIDTHS[(i + 1) % Prefs.WIDTHS.size])
            refresh(); onApply()
        }
        speedRow = row {
            Prefs.setSpeed(
                activity,
                when (Prefs.speed(activity)) { 1.5f -> 2.5f; 2.5f -> 4.0f; else -> 1.5f }
            )
            refresh()
        }
        hostRow = row {
            // Cycles the last octet. Not a text field, because there is no
            // keyboard — and on a home LAN the phone's address moves by one
            // or two, so walking it is faster than any on-screen grid.
            val parts = Prefs.host(activity).split(".")
            if (parts.size == 4) {
                val last = parts[3].toIntOrNull() ?: 10
                Prefs.setHost(activity, "${parts[0]}.${parts[1]}.${parts[2]}.${if (last >= 254) 2 else last + 1}")
            }
            refresh(); onApply()
        }

        col.addView(label(
            // The pad is the agent's now: a tap asks about the screen rather
            // than clicking it, and clicking moved to press-and-hold.
            "tap = ask agent · double = stop · triple = this page",
            9.5f, 0xCCFFFFFF.toInt(), 8, 2
        ))
        col.addView(label(
            "swipe up/down = scroll phone · hold = click · sideways = aim",
            9.5f, 0xCCFFFFFF.toInt(), 0, 6
        ))

        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        nav.addView(navButton("Back") { globalSender?.invoke(DexLink.ACTION_BACK) })
        nav.addView(navButton("Home") { globalSender?.invoke(DexLink.ACTION_HOME) })
        nav.addView(navButton("Apps") { globalSender?.invoke(DexLink.ACTION_RECENTS) })
        nav.addView(navButton("Close") { toggle() })
        col.addView(nav)

        view.addView(col)
        refresh()
    }

    /** Lets the activity supply the live link for BACK/HOME/RECENTS. */
    fun bindGlobal(sender: (Int) -> Unit) { globalSender = sender }

    fun toggle() {
        view.visibility = if (isShowing) View.GONE else View.VISIBLE
        if (isShowing) refresh()
    }

    private fun refresh() {
        val w = Prefs.width(activity)
        resRow.text = "Resolution:  $w px wide" + if (w == 720) "   (best)" else ""
        // Panning only now: the POINTER's speed is set on the phone, where
        // there is a real screen to judge it on. One number used to drive both
        // and they want opposite things — a pan should cover ground, a pointer
        // should settle on a target.
        speedRow.text = "Pan speed:  ${Prefs.speed(activity)}x"
        hostRow.text = "Phone:  ${Prefs.host(activity)}"
    }

    private fun row(onClick: () -> Unit): TextView {
        val tv = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setPadding(10, 8, 10, 8)
            setBackgroundColor(0x22FFFFFF)
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 5 }
        }
        tv.setOnClickListener { onClick() }
        col.addView(tv)
        return tv
    }

    private fun label(t: String, size: Float, colour: Int, top: Int, bottom: Int) =
        TextView(activity).apply {
            text = t; textSize = size; setTextColor(colour)
            setPadding(0, top, 0, bottom)
        }

    private fun navButton(t: String, onClick: () -> Unit) = Button(activity).apply {
        text = t
        textSize = 10f
        setPadding(2, 2, 2, 2)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }

    companion object {
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}

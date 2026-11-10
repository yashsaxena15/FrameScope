package com.merlin.framescope

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView

/** One line per overlay value, in the order they appear on screen. */
class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sb = SpannableStringBuilder()
        fun section(t: String) {
            if (sb.isNotEmpty()) sb.append("\n")
            val s = sb.length
            sb.append(t).append("\n")
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun item(key: String, text: String) {
            val s = sb.length
            sb.append(key)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" — ").append(text).append("\n")
        }

        section("Frame rate")
        item("59.7 FPS", "frames shown on screen per second, measured over the last second (configurable).")
        item("@120Hz", "the display refresh rate. A game capped at 60 FPS on a 120 Hz screen is normal.")
        item("avg", "average FPS over the last 10 seconds.")
        item("1%", "\"1% low\": average FPS of the slowest 1 % of frames in the last 10 s. The closer to avg, the smoother.")
        item(".1%", "\"0.1% low\": the same for the slowest 0.1 % — the worst single hitches.")
        item("16.7ms", "how long the most recent frame took (1000 ÷ 60 = 16.7 ms is a perfect 60 FPS frame).")
        item("jank", "number of stutters: frames that took over 2× the previous three frames and over two screen refreshes.")
        item("gap", "only shown if the overlay missed frames because polling was too slow (lower the poll interval).")
        item("Graph", "time of each of the last 120 frames; spikes are slow frames. The green line is one screen refresh.")

        section("Compact preset")
        item("60 FPS · CPU 23% · RAM 79% · 42°", "frames per second, total CPU usage, RAM used by the whole device, and SoC temperature, in one line.")

        section("System (Full preset)")
        item("CPU 50%", "total CPU usage across all cores.")
        item("1.0|0.6|0.8 GHz", "current speed of each CPU core cluster, ordered from the slowest cluster to the fastest (e.g. efficiency | performance | prime).")
        item("RAM 8.6/11.0G 79%", "RAM in use / RAM usable by Android (part of the advertised RAM is reserved by the system).")
        item("App 1.30G", "RAM used by the app currently in front (resident memory).")
        item("Swap 4.5/12.0G 38%", "swap in use: compressed ZRAM + the storage \"memory extension\" (virtual RAM), combined. Not GPU memory.")
        item("SoC 55°", "chip temperature from Android's thermal service (the value the phone uses for throttling). Many phones, including HyperOS, report one chip-wide value, so CPU and GPU are not shown separately.")
        item("Bat 42°", "battery temperature.")
        item("GPU*", "experimental GPU load/frequency from kernel trace events; not accurate on MediaTek chips.")

        section("Tips")
        item("Double-tap", "the overlay to reset jank and the avg / 1% / 0.1% lows — e.g. when a match starts.")
        item("Drag", "the overlay to move it. \"Lock position\" makes it ignore touches so they reach the game. If it ends up off-screen or hard to reach, use \"Reset overlay position\" in settings.")
        item("Notification", "has Reset stats and Stop buttons. A Quick Settings tile can toggle the overlay.")

        val d = resources.displayMetrics.density
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(TextView(this@HelpActivity).apply {
                text = sb
                textSize = 15f
                setLineSpacing(0f, 1.15f)
                val p = (18 * d).toInt()
                setPadding(p, p, p, p)
            })
        })
    }
}

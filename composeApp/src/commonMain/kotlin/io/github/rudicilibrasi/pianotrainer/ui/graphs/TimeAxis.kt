package io.github.rudicilibrasi.pianotrainer.ui.graphs

import kotlin.math.roundToLong

/** A labelled tick on a time axis. */
data class Tick(val seconds: Double, val label: String)

/** Time axes shared by every graph: round tick spacing, labelled in seconds. */
object TimeAxis {
    private val STEPS = listOf(0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0)

    /** Ticks from 0 to [durationSeconds], at the smallest round step that gives at most [maxTicks]. */
    fun ticks(durationSeconds: Double, maxTicks: Int = 6): List<Tick> {
        if (durationSeconds <= 0.0) return listOf(Tick(0.0, "0 s"))
        val step = STEPS.firstOrNull { tickCount(durationSeconds, it) <= maxTicks } ?: STEPS.last()
        val count = tickCount(durationSeconds, step) - 1
        return (0..count).map { i -> (i * step).let { Tick(it, label(it)) } }
    }

    private fun tickCount(durationSeconds: Double, step: Double): Long = (durationSeconds / step + 1e-9).toLong() + 1

    /**
     * Where to draw each tick label: centred on its tick, kept inside [minLeft]..[maxRight], and
     * null (skipped) when it would touch the label before it, e.g. "0 s" and "1 s" on a narrow graph
     * at a large font size. The first label is always drawn.
     *
     * @param centres each tick's x position; @param widths each label's width, in the same units.
     */
    fun labelLefts(centres: List<Float>, widths: List<Float>, minLeft: Float, maxRight: Float, gap: Float): List<Float?> {
        var previousRight = Float.NEGATIVE_INFINITY
        return centres.zip(widths).map { (centre, width) ->
            val left = (centre - width / 2f).coerceIn(minLeft, maxOf(minLeft, maxRight - width))
            if (left < previousRight + gap) null else left.also { previousRight = left + width }
        }
    }

    /** "0 s", "1.5 s", "0.25 s". */
    fun label(seconds: Double): String {
        val hundredths = (seconds * 100).roundToLong()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val text = when {
            fraction == 0L -> "$whole"
            fraction % 10 == 0L -> "$whole.${fraction / 10}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
        return "$text s"
    }
}

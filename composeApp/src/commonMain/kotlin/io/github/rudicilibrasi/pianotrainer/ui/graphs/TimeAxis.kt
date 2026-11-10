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

package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tempo and timing of a performance, measured over its matched notes (spec §11).
 *
 * @property matchedCount number of correctly played notes the measures are based on.
 * @property tempoRatio `a` in the fit `userTime = a · expectedTime + b`. Above 1 means the student
 *   played slower than the reference (1.2 is 20% slower). Null with fewer than 2 matched notes.
 * @property offsetMs `b` in the same fit: when the student started, relative to the recording.
 * @property deviationsBeats for each matched note, in [Alignment.matches] order, how early
 *   (negative) or late (positive) it was in beats, after removing the fit. Beats are measured at
 *   the student's own tempo, so playing evenly slower gives deviations of 0.
 * @property timingAccuracy 0–1: 1 minus the mean per-note timing penalty (see [Timing]).
 * @property rhythmSpread standard deviation of [deviationsBeats], in beats. 0 is perfectly even.
 * @property tempoAccuracy 0–1: how close [tempoRatio] is to the reference tempo (see [Timing]).
 */
data class TimingResult(
    val matchedCount: Int,
    val tempoRatio: Double?,
    val offsetMs: Double?,
    val deviationsBeats: List<Double>,
    val timingAccuracy: Double,
    val rhythmSpread: Double,
    val tempoAccuracy: Double,
) {
    /** True when the student played noticeably faster or slower than the reference. */
    val tempoFlagged: Boolean get() = tempoRatio == null || abs(tempoRatio - 1.0) > Timing.TEMPO_TOLERANCE
}

/**
 * Measures tempo and timing fairly, without punishing a beginner for playing a bit slower.
 *
 * 1. Fit `userTime = a · expectedTime + b` by least squares over the onsets of matched notes.
 *    `a` is the tempo ratio.
 * 2. For each matched note, the residual from that fit, converted to beats at the student's tempo
 *    (`residualMs / (a · msPerBeat)`), is its timing deviation.
 * 3. Per-note timing penalty: 0 within ±[TIMING_FREE_BEATS] (⅛ beat), rising linearly to 1 at
 *    ±[TIMING_FULL_PENALTY_BEATS] (½ beat) and beyond. Timing accuracy = 1 − mean penalty.
 * 4. Rhythm consistency is the spread (standard deviation) of the deviations.
 * 5. Tempo accuracy: no penalty while `|a − 1|` is within [TEMPO_TOLERANCE] (±15%), rising
 *    linearly to a full penalty at [TEMPO_FULL_PENALTY] (±50%) and beyond. A fit that runs
 *    backwards (`a ≤ 0`) gets 0.
 *
 * With fewer than 2 matched notes there is nothing to fit: timing and tempo accuracy are 0.
 */
object Timing {
    const val TIMING_FREE_BEATS = 1.0 / 8
    const val TIMING_FULL_PENALTY_BEATS = 1.0 / 2
    const val TEMPO_TOLERANCE = 0.15
    const val TEMPO_FULL_PENALTY = 0.5

    fun measure(phrase: Phrase, tempoBpm: Double, detected: List<DetectedNote>, alignment: Alignment): TimingResult {
        val noteStartsMs = phrase.timedEvents(tempoBpm).filter { !it.event.isRest }.map { it.startMs }
        return measure(noteStartsMs, 60_000.0 / tempoBpm, detected, alignment)
    }

    /**
     * @param expectedStartsMs start of each expected note (rests excluded), indexed like the
     *   expected notes of [alignment].
     * @param msPerBeat length of one beat at the reference tempo.
     */
    fun measure(expectedStartsMs: List<Double>, msPerBeat: Double, detected: List<DetectedNote>, alignment: Alignment): TimingResult {
        require(expectedStartsMs.size == alignment.expectedCount) {
            "Got ${expectedStartsMs.size} expected note starts for an alignment of ${alignment.expectedCount} notes"
        }
        require(detected.size == alignment.detectedCount) {
            "Got ${detected.size} detected notes for an alignment of ${alignment.detectedCount} notes"
        }
        require(msPerBeat > 0.0) { "msPerBeat must be positive, but was $msPerBeat" }

        val matches = alignment.matches
        if (matches.size < 2) {
            return TimingResult(matches.size, null, null, emptyList(), timingAccuracy = 0.0, rhythmSpread = 0.0, tempoAccuracy = 0.0)
        }

        val x = matches.map { expectedStartsMs[it.expectedIndex] }
        val y = matches.map { detected[it.detectedIndex].startMs }
        val meanX = x.average()
        val meanY = y.average()
        var sxx = 0.0
        var sxy = 0.0
        for (k in x.indices) {
            sxx += (x[k] - meanX) * (x[k] - meanX)
            sxy += (x[k] - meanX) * (y[k] - meanY)
        }
        // Matched notes are in order and every note has a positive length, so sxx > 0.
        val a = sxy / sxx
        val b = meanY - a * meanX

        if (a <= 0.0) {
            return TimingResult(matches.size, a, b, emptyList(), timingAccuracy = 0.0, rhythmSpread = 0.0, tempoAccuracy = 0.0)
        }

        val userMsPerBeat = a * msPerBeat
        val deviations = x.indices.map { k -> (y[k] - (a * x[k] + b)) / userMsPerBeat }
        val timingAccuracy = 1.0 - deviations.map { timingPenalty(it) }.average()
        val meanDeviation = deviations.average()
        val spread = sqrt(deviations.sumOf { (it - meanDeviation) * (it - meanDeviation) } / deviations.size)

        return TimingResult(
            matchedCount = matches.size,
            tempoRatio = a,
            offsetMs = b,
            deviationsBeats = deviations,
            timingAccuracy = timingAccuracy,
            rhythmSpread = spread,
            tempoAccuracy = 1.0 - rampPenalty(abs(a - 1.0), TEMPO_TOLERANCE, TEMPO_FULL_PENALTY),
        )
    }

    /** Penalty 0–1 for one note that is [deviationBeats] early or late. */
    fun timingPenalty(deviationBeats: Double): Double =
        rampPenalty(abs(deviationBeats), TIMING_FREE_BEATS, TIMING_FULL_PENALTY_BEATS)

    private fun rampPenalty(value: Double, free: Double, full: Double): Double = when {
        value <= free -> 0.0
        value >= full -> 1.0
        else -> (value - free) / (full - free)
    }
}

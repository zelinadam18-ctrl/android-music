package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The detected notes plus the onsets and pitch track they came from. */
data class NoteAnalysis(val notes: List<DetectedNote>, val onsets: List<Onset>, val pitchFrames: List<PitchFrame>)

/**
 * Turns a recording into the list of notes the student played (spec §10), combining
 * [OnsetDetector] (where notes start) with [PitchDetector] (what pitch is sounding).
 *
 * 1. The recording is normalised to a fixed peak level, so a quiet recording is analysed like a
 *    loud one. A recording whose peak is below [silencePeak] is treated as silence.
 * 2. The noise floor is estimated from the quietest part of the recording (the
 *    5th percentile of 10 ms block levels, capped at 30 dB below the loudest block). Segments
 *    whose level stays within [noiseMarginDb] of it are ignored.
 * 3. Each segment runs from one onset to the next (or the end of the recording). Its pitch is the
 *    median f0 of the voiced frames in its steady part, skipping the first [attackSeconds], and is
 *    converted to the nearest piano key plus a deviation in cents.
 * 4. The note is marked **uncertain** instead of guessed when there are too few voiced frames,
 *    the pitch wanders (fewer than [minStableFraction] of the voiced frames within ±35 cents of
 *    the median, or the first and second halves differ by more than 30 cents), the median is more than [maxCents] from the nearest key, or the confidence is below
 *    [minConfidence]. An uncertain note keeps its best-guess pitch when there is one.
 */
class NoteDetector(
    val sampleRate: Int,
    val attackSeconds: Double = 0.03,
    val noiseMarginDb: Double = 12.0,
    val silencePeak: Double = 1e-4,
    val minVoicedFrames: Int = 3,
    val minStableFraction: Double = 0.7,
    val maxCents: Double = 40.0,
    val minConfidence: Double = 0.6,
) {
    private val onsetDetector = OnsetDetector(sampleRate)
    private val pitchDetector = PitchDetector(sampleRate)
    private val blockSize = (sampleRate * 0.01).roundToInt().coerceAtLeast(1)

    fun detect(recording: FloatArray): List<DetectedNote> = analyze(recording).notes

    /**
     * Like [detect], but also returns the onsets and pitch track it used, so callers that also
     * draw graphs don't have to compute them again.
     */
    fun analyze(recording: FloatArray): NoteAnalysis {
        val peak = recording.maxOfOrNull { abs(it) } ?: return NoteAnalysis(emptyList(), emptyList(), emptyList())
        if (peak < silencePeak) return NoteAnalysis(emptyList(), emptyList(), pitchDetector.detect(recording))
        val gain = (TARGET_PEAK / peak).toFloat()
        val samples = FloatArray(recording.size) { recording[it] * gain }

        val frames = pitchDetector.detect(samples)
        val onsets = onsetDetector.detect(samples)
        if (onsets.isEmpty()) return NoteAnalysis(emptyList(), onsets, frames)
        val blockRms = blockRms(samples)
        val noiseThreshold = noiseFloor(blockRms) * dbToRatio(noiseMarginDb)
        val endSeconds = samples.size.toDouble() / sampleRate

        val notes = onsets.indices.mapNotNull { i ->
            val start = onsets[i].timeSeconds
            val end = onsets.getOrNull(i + 1)?.timeSeconds ?: endSeconds
            segmentNote(start, end, frames, blockRms, noiseThreshold)
        }
        return NoteAnalysis(notes, onsets, frames)
    }

    private fun segmentNote(
        start: Double,
        end: Double,
        frames: List<PitchFrame>,
        blockRms: DoubleArray,
        noiseThreshold: Double,
    ): DetectedNote? {
        val steadyFrom = minOf(start + attackSeconds, (start + end) / 2)
        if (segmentLevel(blockRms, steadyFrom, end) <= noiseThreshold) return null

        val steady = frames.filter { it.timeSeconds in steadyFrom..end }
        val voiced = steady.filter { it.voiced }
        val startMs = start * 1000
        if (voiced.size < minVoicedFrames) {
            return DetectedNote(null, startMs, end * 1000, confidence = 0.0, uncertain = true)
        }

        val semitones = voiced.map { 69 + 12 * log2(it.frequencyHz!! / Pitch.A4_HZ) }
        val median = medianOf(semitones)
        val stableFraction = semitones.count { abs(it - median) <= STABLE_SEMITONES }.toDouble() / semitones.size
        // A pitch that slides (first half and second half disagree) is not one note.
        val half = semitones.size / 2
        val drift = if (half == 0) 0.0 else abs(medianOf(semitones.subList(half, semitones.size)) - medianOf(semitones.subList(0, half)))
        val medianHz = Pitch.A4_HZ * 2.0.pow((median - 69) / 12)
        val estimate = Pitch.nearest(medianHz)
        val meanConfidence = voiced.map { it.confidence }.average()
        val confidence = (meanConfidence * stableFraction).coerceIn(0.0, 1.0)

        // The note lasts until its pitch stops sounding, or the next onset.
        val lastVoiced = voiced.indices.lastOrNull { abs(semitones[it] - median) <= STABLE_SEMITONES }?.let { voiced[it] }
        val endMs = if (lastVoiced == null) end * 1000 else minOf(end, lastVoiced.timeSeconds + FRAME_HALF_SECONDS) * 1000

        val uncertain = estimate == null ||
            stableFraction < minStableFraction ||
            drift > MAX_DRIFT_SEMITONES ||
            abs(estimate.cents) > maxCents ||
            confidence < minConfidence
        return DetectedNote(
            pitch = estimate?.pitch,
            startMs = startMs,
            endMs = max(endMs, startMs),
            confidence = confidence,
            uncertain = uncertain,
            cents = estimate?.cents,
        )
    }

    private fun blockRms(samples: FloatArray): DoubleArray {
        val count = (samples.size + blockSize - 1) / blockSize
        return DoubleArray(count) { b ->
            val from = b * blockSize
            val to = minOf(samples.size, from + blockSize)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            sqrt(sum / (to - from))
        }
    }

    /**
     * RMS of the quietest part of the recording. A recording that is nearly all notes has no
     * truly quiet part, so the floor is capped at [MAX_NOISE_BELOW_PEAK_DB] below the loudest
     * block, and never below −90 dBFS.
     */
    private fun noiseFloor(blockRms: DoubleArray): Double {
        val sorted = blockRms.sorted()
        val quiet = sorted[(sorted.size * NOISE_PERCENTILE).toInt().coerceAtMost(sorted.size - 1)]
        val cap = sorted.last() * dbToRatio(-MAX_NOISE_BELOW_PEAK_DB)
        return max(minOf(quiet, cap), dbToRatio(-90.0))
    }

    private fun medianOf(values: List<Double>): Double = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /** Loudest 10 ms block level in the segment's steady part. */
    private fun segmentLevel(blockRms: DoubleArray, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * sampleRate / blockSize).toInt().coerceIn(0, blockRms.size - 1)
        val to = (toSeconds * sampleRate / blockSize).toInt().coerceIn(from, blockRms.size - 1)
        var loudest = 0.0
        for (b in from..to) loudest = max(loudest, blockRms[b])
        return loudest
    }

    private fun dbToRatio(db: Double): Double = 10.0.pow(db / 20)

    private companion object {
        /** Level the recording is normalised to before analysis. */
        const val TARGET_PEAK = 0.8

        /** Which quiet block level counts as the noise floor (5th percentile). */
        const val NOISE_PERCENTILE = 0.05

        /** The noise floor is never estimated higher than this far below the loudest block. */
        const val MAX_NOISE_BELOW_PEAK_DB = 30.0

        /** Frames within this many semitones of the note's median pitch count as stable. */
        const val STABLE_SEMITONES = 0.35

        /** More than this change between a note's first and second half means the pitch wanders. */
        const val MAX_DRIFT_SEMITONES = 0.3

        /** Half the hop of a pitch frame, so a note ends after its last voiced frame. */
        const val FRAME_HALF_SECONDS = 0.006
    }
}

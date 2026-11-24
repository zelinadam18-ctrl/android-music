package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
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
 * 3. An onset only starts a note if the recording gets louder there: a hammer strike always adds
 *    energy, so a flux peak with no level rise (more than [minOnsetRiseDb]) is partials trading
 *    energy inside a held note. One that rises by less than [weakStrikeDb] only counts if the
 *    pitch changes.
 * 4. Each segment runs from one onset to the next (or the end of the recording). Its pitch comes
 *    from the voiced frames in its steady part (skipping the first [attackSeconds]) that are within
 *    [loudRangeDb] of its loudest, because a decaying tail on a phone recording often reads a
 *    whole-number multiple of the period (an octave or more low). It is converted to the nearest
 *    piano key plus a deviation in cents. Frames at such a multiple still count as this note.
 * 5. A note that reads as the common period of itself and the still-ringing previous note (C3 for
 *    a G4 struck over a C4) is corrected to the harmonic of that period that got louder.
 * 6. The note is marked **uncertain** instead of guessed when there are too few voiced frames,
 *    the pitch wanders (fewer than [minStableFraction] of the voiced frames within ±35 cents of
 *    the note, or the first and second halves differ by more than 30 cents), it is more than
 *    [maxCents] from the nearest key, or the confidence is below [minConfidence]. An uncertain
 *    note keeps its best-guess pitch when there is one.
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
    val minOnsetRiseDb: Double = 0.0,
    val weakStrikeDb: Double = 1.0,
    val loudRangeDb: Double = 6.0,
) {
    internal val onsetDetector = OnsetDetector(sampleRate)
    internal val pitchDetector = PitchDetector(sampleRate)
    private val blockSize = (sampleRate * 0.01).roundToInt().coerceAtLeast(1)

    fun detect(recording: FloatArray): List<DetectedNote> = analyze(recording).notes

    /**
     * Like [detect], but also returns the onsets and pitch track it used, so callers that also
     * draw graphs don't have to compute them again.
     */
    fun analyze(recording: FloatArray): NoteAnalysis {
        val samples = normalized(recording) ?: return silence(recording)
        return notesFrom(samples, pitchDetector.detect(samples), onsetDetector.detect(samples))
    }

    /**
     * The recording scaled to a fixed peak level, which [pitchDetector] and [onsetDetector] are
     * run on; null if it is silent (see [silence]). Split out so the two can run in parallel.
     */
    internal fun normalized(recording: FloatArray): FloatArray? {
        val peak = recording.maxOfOrNull { abs(it) } ?: return null
        if (peak < silencePeak) return null
        val gain = (TARGET_PEAK / peak).toFloat()
        return FloatArray(recording.size) { recording[it] * gain }
    }

    /** The result for a recording [normalized] found silent: no notes. */
    internal fun silence(recording: FloatArray): NoteAnalysis =
        NoteAnalysis(emptyList(), emptyList(), if (recording.isEmpty()) emptyList() else pitchDetector.detect(recording))

    /** The notes in [normalized][normalized] [samples], from their pitch [frames] and [onsets]. */
    internal fun notesFrom(samples: FloatArray, frames: List<PitchFrame>, onsets: List<Onset>): NoteAnalysis {
        if (onsets.isEmpty()) return NoteAnalysis(emptyList(), onsets, frames)
        val blockRms = blockRms(samples)
        val noiseThreshold = noiseFloor(blockRms) * dbToRatio(noiseMarginDb)
        val endSeconds = samples.size.toDouble() / sampleRate

        // A hammer strike always adds energy. A spectral-flux peak where the level doesn't rise
        // (partials beating or swapping as a note decays) is not a new note.
        val candidates = onsets.filterIndexed { i, onset -> i == 0 || levelRiseDb(blockRms, onset.timeSeconds) > minOnsetRiseDb }

        val noteOnsets = ArrayList<Onset>()
        val notes = ArrayList<DetectedNote?>()
        fun noteAt(start: Double, end: Double) = segmentNote(start, end, frames, blockRms, noiseThreshold)?.let { note ->
            notes.lastOrNull { it != null }?.let { struckUnderRingingNote(note, it, samples) } ?: note
        }
        for (i in candidates.indices) {
            val start = candidates[i].timeSeconds
            val end = candidates.getOrNull(i + 1)?.timeSeconds ?: endSeconds
            val note = noteAt(start, end)
            val previous = notes.lastOrNull()
            // A barely louder onset that doesn't change the pitch is the same note going on.
            val weak = i > 0 && levelRiseDb(blockRms, start) < weakStrikeDb
            if (weak && previous != null && (note == null || note.pitch == null || note.pitch == previous.pitch)) {
                notes[notes.lastIndex] = null
                notes[notes.lastIndex] = noteAt(previous.startMs / 1000, end)
                continue
            }
            noteOnsets += candidates[i]
            notes += note
        }
        return NoteAnalysis(notes.filterNotNull(), noteOnsets, frames)
    }

    /**
     * A soft note struck while the previous one still rings: the two together repeat at their
     * common period, which is a whole-number fraction of both (C4 ringing + G4 struck reads as
     * C3). When [note] reads such a fraction of [previous] (previous = k · note, k = 2 or 3) and
     * the previous note's own harmonic did not get louder at the onset, the struck note is the
     * other harmonic of the common period that did: returns [note] with that pitch. Null when this
     * isn't the case.
     */
    private fun struckUnderRingingNote(note: DetectedNote, previous: DetectedNote, samples: FloatArray): DetectedNote? {
        val pitch = note.pitch ?: return null
        val previousPitch = previous.pitch ?: return null
        val commonHz = Pitch.A4_HZ * 2.0.pow((pitch.midi + (note.cents ?: 0.0) / 100 - 69) / 12)
        val k = (2..3).firstOrNull { previousPitch.midi - pitch.midi == (12 * log2(it.toDouble())).roundToInt() } ?: return null
        val onset = note.startMs / 1000
        fun riseDb(hz: Double) = 10 * log10(
            toneEnergy(samples, hz, onset + STRIKE_AFTER_FROM, onset + STRIKE_AFTER_TO) /
                toneEnergy(samples, hz, onset - STRIKE_BEFORE_FROM, onset - STRIKE_BEFORE_TO),
        )
        if (riseDb(k * commonHz) > RINGING_MAX_RISE_DB) return null
        val (m, rise) = (2..6).filter { it % k != 0 }.map { it to riseDb(it * commonHz) }.maxByOrNull { it.second } ?: return null
        if (rise < STRIKE_MIN_RISE_DB) return null
        val struck = Pitch.nearest(m * commonHz) ?: return null
        return note.copy(pitch = struck.pitch, cents = struck.cents)
    }

    /** Energy of [samples] at [hz] between two times (Hann-windowed single-bin DFT, Goertzel). */
    private fun toneEnergy(samples: FloatArray, hz: Double, fromSeconds: Double, toSeconds: Double): Double {
        val from = (fromSeconds * sampleRate).toInt().coerceIn(0, samples.size)
        val to = (toSeconds * sampleRate).toInt().coerceIn(from, samples.size)
        val n = to - from
        if (n < 2) return 1e-20
        val coefficient = 2 * cos(2 * PI * hz / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            val s0 = samples[from + i] * window + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coefficient * s1 * s2) / n / n + 1e-20
    }

    /**
     * How much louder the recording gets at [timeSeconds]: the loudest 10 ms block in the
     * [ONSET_AFTER_SECONDS] after it, against the mean level over [ONSET_BEFORE_SECONDS] before it
     * (skipping the last 10 ms, which the attack may already reach). Infinite at the very start.
     */
    private fun levelRiseDb(blockRms: DoubleArray, timeSeconds: Double): Double {
        val at = (timeSeconds * sampleRate / blockSize).toInt().coerceIn(0, blockRms.size - 1)
        val beforeBlocks = (ONSET_BEFORE_SECONDS * sampleRate / blockSize).roundToInt()
        val afterBlocks = (ONSET_AFTER_SECONDS * sampleRate / blockSize).roundToInt().coerceAtLeast(1)
        val from = at - 1 - beforeBlocks
        if (from < 0) return Double.POSITIVE_INFINITY
        var before = 0.0
        for (b in from until at - 1) before += blockRms[b]
        before /= beforeBlocks
        var after = 0.0
        for (b in at until minOf(blockRms.size, at + afterBlocks)) after = max(after, blockRms[b])
        if (before <= 0.0) return Double.POSITIVE_INFINITY
        return 20 * log10(after / before)
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

        // Judge the pitch while the note is loud. As a piano note decays its partials fade at
        // different rates, and on a phone recording the tail often reads an octave off.
        val levels = voiced.map { frameLevel(blockRms, it.timeSeconds) }
        val loudFrom = (levels.maxOrNull() ?: 0.0) * dbToRatio(-loudRangeDb)
        val loud = voiced.indices.filter { levels[it] >= loudFrom }.map { voiced[it] }
        val judged = if (loud.size >= minVoicedFrames) loud else voiced
        val raw = judged.map { 69 + 12 * log2(it.frequencyHz!! / Pitch.A4_HZ) }
        val median = fundamentalOf(raw)
        // Frames that read a whole-number fraction of the note's frequency (the detector locking
        // onto two or more periods) or an octave above it are still this note.
        val semitones = raw.map { fold(it, median) }
        val stableFraction = semitones.count { abs(it - median) <= STABLE_SEMITONES }.toDouble() / semitones.size
        // A pitch that slides (first half and second half disagree) is not one note.
        val half = semitones.size / 2
        val drift = if (half == 0) 0.0 else abs(medianOf(semitones.subList(half, semitones.size)) - medianOf(semitones.subList(0, half)))
        val medianHz = Pitch.A4_HZ * 2.0.pow((median - 69) / 12)
        val estimate = Pitch.nearest(medianHz)
        val meanConfidence = judged.map { it.confidence }.average()
        val confidence = (meanConfidence * stableFraction).coerceIn(0.0, 1.0)

        // The note lasts until its pitch stops sounding, or the next onset. Its decaying tail may
        // read an octave off, which is still the same note.
        val lastVoiced = voiced.lastOrNull { frame ->
            abs(fold(69 + 12 * log2(frame.frequencyHz!! / Pitch.A4_HZ), median) - median) <= STABLE_SEMITONES
        }
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

    /**
     * The note's pitch, in semitones, from its frames. Usually their median; but the detector's
     * errors are mostly whole-number multiples of the period (C3 or G3 read for a G4), so when a
     * clear share of the frames sits at a whole-number multiple of the median frequency, the
     * highest such group is the real note.
     */
    private fun fundamentalOf(semitones: List<Double>): Double {
        val median = medianOf(semitones)
        var best = median
        for (k in 2..MAX_SUBHARMONIC_DIVISOR) {
            val candidate = median + 12 * log2(k.toDouble())
            val group = semitones.filter { abs(it - candidate) <= STABLE_SEMITONES }
            if (group.size >= MIN_GROUP_SHARE * semitones.size && candidate > best) best = medianOf(group)
        }
        return best
    }

    /**
     * Maps a frame that reads [note]'s frequency divided by a whole number (2 to
     * [MAX_SUBHARMONIC_DIVISOR]), or doubled, back onto [note], keeping its deviation in cents.
     * Anything else is returned unchanged.
     */
    private fun fold(semitone: Double, note: Double): Double {
        val below = note - semitone
        if (abs(below + 12) <= STABLE_SEMITONES) return semitone - 12
        for (k in 2..MAX_SUBHARMONIC_DIVISOR) {
            val interval = 12 * log2(k.toDouble())
            if (abs(below - interval) <= STABLE_SEMITONES) return semitone + interval
        }
        return semitone
    }

    /** Level of the 10 ms block a pitch frame is centred in. */
    private fun frameLevel(blockRms: DoubleArray, timeSeconds: Double): Double =
        blockRms[(timeSeconds * sampleRate / blockSize).toInt().coerceIn(0, blockRms.size - 1)]

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

        /** Largest whole-number period multiple the pitch detector is assumed to lock onto. */
        const val MAX_SUBHARMONIC_DIVISOR = 8

        /** Share of a note's frames a higher, whole-number-multiple group needs to be the note. */
        const val MIN_GROUP_SHARE = 0.25

        /** Windows around an onset for comparing a harmonic's energy, in seconds from the onset. */
        const val STRIKE_BEFORE_FROM = 0.05
        const val STRIKE_BEFORE_TO = 0.01
        const val STRIKE_AFTER_FROM = 0.03
        const val STRIKE_AFTER_TO = 0.11

        /** A ringing note's harmonic gains at most this much at the next onset. */
        const val RINGING_MAX_RISE_DB = 3.0

        /** A newly struck note's harmonic gains at least this much at its onset. */
        const val STRIKE_MIN_RISE_DB = 12.0

        /** How far before an onset the level is measured, to see whether the onset adds energy. */
        const val ONSET_BEFORE_SECONDS = 0.04

        /** How far after an onset to look for the new note's peak level. */
        const val ONSET_AFTER_SECONDS = 0.08

        /** Half the hop of a pitch frame, so a note ends after its last voiced frame. */
        const val FRAME_HALF_SECONDS = 0.006
    }
}

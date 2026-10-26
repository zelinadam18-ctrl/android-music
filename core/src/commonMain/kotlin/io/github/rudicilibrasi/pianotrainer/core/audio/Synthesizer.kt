package io.github.rudicilibrasi.pianotrainer.core.audio

import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Produces the sound of a single note. Kept behind an interface so recorded piano samples can
 * replace [AdditivePianoTone] later without changing anything else.
 */
interface ToneGenerator {
    /** How long a note keeps sounding after it is released. */
    val releaseSeconds: Double

    /**
     * Adds one note to [buffer]: [frequencyHz] held for [heldSamples] samples starting at
     * [startSample], followed by its release. Samples past the end of [buffer] are dropped.
     */
    fun addNote(buffer: FloatArray, sampleRate: Int, frequencyHz: Double, startSample: Int, heldSamples: Int)
}

/**
 * A piano-like tone built from a sum of decaying partials (additive synthesis).
 *
 * Like a real piano string, the partials are slightly sharp of exact harmonics
 * (partial n is at `n · f0 · sqrt(1 + B·n²)`), higher partials are quieter and die away faster,
 * and lower notes ring longer than higher ones. Each note has a short attack and a short release
 * so it starts and stops without clicks. Everything is deterministic.
 */
class AdditivePianoTone(
    private val partialCount: Int = 10,
    private val inharmonicity: Double = 0.0004,
    private val attackSeconds: Double = 0.003,
    override val releaseSeconds: Double = 0.08,
) : ToneGenerator {

    init {
        require(partialCount >= 1) { "Need at least one partial, but got $partialCount" }
        require(inharmonicity >= 0.0) { "Inharmonicity must not be negative, but was $inharmonicity" }
        require(attackSeconds > 0.0 && releaseSeconds > 0.0) { "Attack and release must be positive" }
    }

    /** Frequency of partial [n] (1 = fundamental) for a note whose fundamental is [fundamentalHz]. */
    fun partialFrequency(fundamentalHz: Double, n: Int): Double = n * fundamentalHz * sqrt(1 + inharmonicity * n * n)

    /** Relative loudness of partial [n]; the fundamental is loudest. */
    fun partialAmplitude(n: Int): Double = 1.0 / n.toDouble().pow(1.3)

    /** Time for the fundamental to fall to 1/e: about 1.6 s at middle C, shorter for higher notes. */
    private fun decaySeconds(fundamentalHz: Double): Double = 1.6 * (261.63 / fundamentalHz).pow(0.6)

    override fun addNote(buffer: FloatArray, sampleRate: Int, frequencyHz: Double, startSample: Int, heldSamples: Int) {
        val nyquist = sampleRate / 2.0
        val partials = (1..partialCount)
            .map { n -> n to partialFrequency(frequencyHz, n) }
            .filter { (_, f) -> f < nyquist }
        if (partials.isEmpty()) return
        val totalAmplitude = partials.sumOf { (n, _) -> partialAmplitude(n) }
        val baseDecay = decaySeconds(frequencyHz)
        val attackSamples = (attackSeconds * sampleRate).coerceAtLeast(1.0)
        val releaseSamples = (releaseSeconds * sampleRate).roundToInt().coerceAtLeast(1)
        val end = minOf(buffer.size, startSample + heldSamples + releaseSamples)

        for ((n, partialHz) in partials) {
            val amplitude = partialAmplitude(n) / totalAmplitude
            // Higher partials decay faster, as on a real string.
            val decayPerSample = 1.0 / (baseDecay / (1 + 0.6 * (n - 1)) * sampleRate)
            val phaseStep = 2 * PI * partialHz / sampleRate
            for (i in maxOf(startSample, 0) until end) {
                val t = i - startSample
                val attack = if (t < attackSamples) t / attackSamples else 1.0
                val release = if (t < heldSamples) 1.0 else 0.5 + 0.5 * cos(PI * (t - heldSamples) / releaseSamples)
                val envelope = attack * release * exp(-t * decayPerSample)
                buffer[i] += (amplitude * envelope * sin(phaseStep * t)).toFloat()
            }
        }
    }
}

/** A note to synthesize, timed in seconds from the start of the output. */
data class SynthNote(val pitch: Pitch, val startSeconds: Double, val durationSeconds: Double)

/**
 * Renders notes to mono audio. The output is peak-normalized to [PEAK_LEVEL] so it never clips,
 * however many notes overlap.
 */
class Synthesizer(
    val sampleRate: Int = 44100,
    private val tone: ToneGenerator = AdditivePianoTone(),
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive, but was $sampleRate" }
    }

    /**
     * Renders [notes] into a buffer [totalSeconds] long plus the release of the last notes.
     */
    fun render(notes: List<SynthNote>, totalSeconds: Double): FloatArray {
        require(totalSeconds >= 0.0) { "Total duration must not be negative, but was $totalSeconds" }
        val buffer = FloatArray(ceil((totalSeconds + tone.releaseSeconds) * sampleRate).toInt())
        for (note in notes) {
            tone.addNote(
                buffer,
                sampleRate,
                note.pitch.frequencyHz,
                startSample = (note.startSeconds * sampleRate).roundToInt(),
                heldSamples = (note.durationSeconds * sampleRate).roundToInt(),
            )
        }
        normalize(buffer)
        return buffer
    }

    /** Renders one phrase of a song at [tempoBpm], including its rests. */
    fun renderPhrase(phrase: Phrase, tempoBpm: Double): FloatArray {
        val notes = phrase.timedEvents(tempoBpm).mapNotNull { timed ->
            timed.event.pitch?.let { SynthNote(it, timed.startMs / 1000, timed.durationMs / 1000) }
        }
        return render(notes, phrase.durationMs(tempoBpm) / 1000)
    }

    private fun normalize(buffer: FloatArray) {
        val peak = buffer.maxOfOrNull { abs(it) } ?: return
        if (peak == 0f) return
        val gain = PEAK_LEVEL / peak
        for (i in buffer.indices) buffer[i] *= gain
    }

    companion object {
        /** Output peak level, about −2 dBFS. */
        const val PEAK_LEVEL = 0.8f

        /** Bump when the sound changes, so cached reference audio is regenerated (#21). */
        const val VERSION = 1
    }
}

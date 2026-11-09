package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchDetectorTest {

    private val sampleRate = 44100
    private val detector = PitchDetector(sampleRate)

    /** Frames from the steady part of a note: after the attack, before the release. */
    private fun steadyFrames(samples: FloatArray, fromSeconds: Double = 0.05, toSeconds: Double = 0.8) =
        detector.detect(samples).filter { it.timeSeconds in fromSeconds..toSeconds }

    private fun assertEveryFrameIs(expected: Pitch, frames: List<PitchFrame>, what: String) {
        assertTrue(frames.isNotEmpty(), "$what: no frames")
        for (frame in frames) {
            val hz = frame.frequencyHz
            assertTrue(hz != null, "$what: unvoiced frame at ${frame.timeSeconds} s")
            val estimate = Pitch.nearest(hz)!!
            assertEquals(expected, estimate.pitch, "$what: frame at ${frame.timeSeconds} s read $hz Hz")
            assertTrue(abs(estimate.cents) < 10, "$what: $hz Hz is ${estimate.cents} cents off $expected")
        }
    }

    /** A tone built from the given partial amplitudes (index 0 = fundamental). */
    private fun tone(frequencyHz: Double, partials: List<Double>, seconds: Double = 1.0) =
        FloatArray((seconds * sampleRate).toInt()) { i ->
            val t = i.toDouble() / sampleRate
            partials.withIndex().sumOf { (k, amplitude) -> amplitude * sin(2 * PI * (k + 1) * frequencyHz * t) }.toFloat() * 0.3f
        }

    @Test
    fun findsEverySynthesizedPianoNoteFromG3ToG5() {
        val synthesizer = Synthesizer(sampleRate)
        for (midi in Pitch.parse("G3").midi..Pitch.parse("G5").midi) {
            val pitch = Pitch(midi)
            val samples = synthesizer.render(listOf(SynthNote(pitch, 0.0, 0.9)), 1.0)
            assertEveryFrameIs(pitch, steadyFrames(samples), "piano $pitch")
        }
    }

    @Test
    fun weakFundamentalWithAStrongSecondPartialHasNoOctaveError() {
        // Fundamental 14 dB and 26 dB below the second partial.
        for (fundamental in listOf(0.2, 0.05)) {
            for (label in listOf("G3", "C4", "A4", "E5", "G5")) {
                val pitch = Pitch.parse(label)
                val samples = tone(pitch.frequencyHz, listOf(fundamental, 1.0, 0.1, 0.3))
                assertEveryFrameIs(pitch, steadyFrames(samples, toSeconds = 0.9), "weak-fundamental ($fundamental) $label")
            }
        }
    }

    @Test
    fun missingFundamentalStillReadsTheFundamental() {
        // Partials 2, 3 and 4 only: the period is still that of the (absent) fundamental.
        val pitch = Pitch.parse("C4")
        val samples = tone(pitch.frequencyHz, listOf(0.0, 1.0, 0.8, 0.5))
        assertEveryFrameIs(pitch, steadyFrames(samples, toSeconds = 0.9), "missing-fundamental C4")
    }

    @Test
    fun cleanNotesHaveHighConfidence() {
        val samples = Synthesizer(sampleRate).render(listOf(SynthNote(Pitch.parse("A4"), 0.0, 0.9)), 1.0)
        val frames = steadyFrames(samples)
        assertTrue(frames.all { it.confidence > 0.85 }, "confidences ${frames.map { it.confidence }}")
    }

    @Test
    fun frequencyIsAccurateToAFewCents() {
        val samples = tone(440.0, listOf(1.0, 0.5, 0.3))
        val frames = steadyFrames(samples, toSeconds = 0.9)
        for (frame in frames) assertTrue(abs(frame.frequencyHz!! - 440.0) < 1.0, "read ${frame.frequencyHz} Hz")
    }

    @Test
    fun silenceIsUnvoiced() {
        val frames = detector.detect(FloatArray(sampleRate))
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.none { it.voiced })
        assertTrue(frames.all { it.confidence == 0.0 })
    }

    @Test
    fun whiteNoiseIsUnvoiced() {
        val random = Random(42)
        val noise = FloatArray(sampleRate) { (random.nextDouble() * 2 - 1).toFloat() * 0.3f }
        val frames = detector.detect(noise)
        assertTrue(frames.none { it.voiced }, "voiced noise frames: ${frames.filter { it.voiced }}")
    }

    @Test
    fun aNoteAfterSilenceIsUnvoicedThenVoiced() {
        val pitch = Pitch.parse("E4")
        val samples = Synthesizer(sampleRate).render(listOf(SynthNote(pitch, 0.5, 0.6)), 1.2)
        val frames = detector.detect(samples)
        assertTrue(frames.filter { it.timeSeconds < 0.45 }.none { it.voiced })
        assertEveryFrameIs(pitch, frames.filter { it.timeSeconds in 0.6..1.0 }, "E4 after silence")
    }

    @Test
    fun framesAreEvenlySpacedAtTheHopSize() {
        val frames = detector.detect(FloatArray(sampleRate))
        val hopSeconds = detector.hopSize.toDouble() / sampleRate
        for (k in 1 until frames.size) assertTrue(abs(frames[k].timeSeconds - frames[k - 1].timeSeconds - hopSeconds) < 1e-9)
    }

    @Test
    fun worksAtOtherSampleRates() {
        val rate = 16000
        val pitch = Pitch.parse("G4")
        val samples = Synthesizer(rate).render(listOf(SynthNote(pitch, 0.0, 0.9)), 1.0)
        val frames = PitchDetector(rate).detect(samples).filter { it.timeSeconds in 0.05..0.8 }
        assertEveryFrameIs(pitch, frames, "G4 at 16 kHz")
    }
}

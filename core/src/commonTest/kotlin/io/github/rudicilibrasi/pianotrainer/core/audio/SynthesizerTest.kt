package io.github.rudicilibrasi.pianotrainer.core.audio

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent.Companion.note
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent.Companion.rest
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthesizerTest {

    private val synth = Synthesizer(sampleRate = 44100)

    /** Magnitude of [samples] at [frequencyHz] (Goertzel algorithm). */
    private fun goertzel(samples: FloatArray, sampleRate: Int, frequencyHz: Double): Double {
        val coefficient = 2 * cos(2 * PI * frequencyHz / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(s1 * s1 + s2 * s2 - coefficient * s1 * s2)
    }

    private fun single(label: String, seconds: Double = 1.0, sampleRate: Int = 44100) =
        Synthesizer(sampleRate).render(listOf(SynthNote(Pitch.parse(label), 0.0, seconds)), seconds)

    @Test
    fun a4HasItsFundamentalAt440Hz() {
        val samples = single("A4")
        val atFundamental = goertzel(samples, 44100, 440.0)
        for (other in listOf(400.0, 420.0, 460.0, 480.0, 660.0)) {
            assertTrue(atFundamental > 10 * goertzel(samples, 44100, other), "440 Hz should dominate $other Hz")
        }
        val secondPartial = AdditivePianoTone().partialFrequency(440.0, 2)
        assertTrue(atFundamental > goertzel(samples, 44100, secondPartial), "fundamental louder than 2nd partial")
    }

    @Test
    fun partialsAreSlightlySharpOfHarmonics() {
        val tone = AdditivePianoTone(inharmonicity = 0.0004)
        assertEquals(440.0 * sqrt(1.0004), tone.partialFrequency(440.0, 1), absoluteTolerance = 1e-9)
        assertTrue(tone.partialFrequency(440.0, 4) > 4 * 440.0)
        assertTrue(tone.partialAmplitude(1) > tone.partialAmplitude(2))
    }

    @Test
    fun neverClipsEvenWhenManyNotesOverlap() {
        val chord = listOf("C4", "E4", "G4", "C5", "E5", "G5", "C6", "E6").map { SynthNote(Pitch.parse(it), 0.0, 2.0) }
        val samples = synth.render(chord, 2.0)
        val peak = samples.maxOf { abs(it) }
        assertEquals(Synthesizer.PEAK_LEVEL, peak, absoluteTolerance = 1e-6f)
        assertTrue(samples.all { abs(it) <= 1f })
    }

    @Test
    fun phraseLengthIsItsDurationPlusTheRelease() {
        val phrase = Phrase(0, listOf(note("C4", 1.0), note("D4", 1.0), note("E4", 2.0)), startBeat = 0.0)
        val samples = synth.renderPhrase(phrase, tempoBpm = 120.0) // 4 beats = 2 s
        val expected = kotlin.math.ceil((2.0 + AdditivePianoTone().releaseSeconds) * 44100).toInt()
        assertEquals(expected, samples.size)
    }

    @Test
    fun restsAreSilentOnceTheReleaseHasFinished() {
        val phrase = Phrase(0, listOf(note("C4", 1.0), rest(1.0), note("C4", 1.0)), startBeat = 0.0)
        val samples = synth.renderPhrase(phrase, tempoBpm = 60.0) // the rest runs from 1 s to 2 s
        val releaseEnd = ((1.0 + AdditivePianoTone().releaseSeconds) * 44100).toInt() + 1
        for (i in releaseEnd until 2 * 44100) assertEquals(0f, samples[i], "sample $i")
        assertTrue(samples.slice(2 * 44100 until 2 * 44100 + 4410).any { abs(it) > 0.1f }, "second note sounds")
    }

    @Test
    fun startsAndEndsWithoutAClick() {
        val samples = single("G4", seconds = 0.5)
        assertEquals(0f, samples.first())
        assertTrue(abs(samples.last()) < 1e-3f, "last sample ${samples.last()}")
    }

    @Test
    fun isDeterministic() {
        assertContentEquals(single("E4"), single("E4"))
    }

    @Test
    fun worksAt48kHz() {
        val samples = single("A4", sampleRate = 48000)
        assertEquals(kotlin.math.ceil((1.0 + AdditivePianoTone().releaseSeconds) * 48000).toInt(), samples.size)
        assertTrue(goertzel(samples, 48000, 440.0) > 10 * goertzel(samples, 48000, 420.0))
    }

    @Test
    fun emptyInputIsSilence() {
        val samples = synth.render(emptyList(), 0.5)
        assertTrue(samples.all { it == 0f })
    }
}

package io.github.rudicilibrasi.pianotrainer.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PitchTest {

    @Test
    fun middleCIsC4AndMidi60() {
        val c4 = Pitch.parse("C4")
        assertEquals(60, c4.midi)
        assertEquals("C4", c4.label)
        assertEquals(261.6256, c4.frequencyHz, absoluteTolerance = 0.0001)
    }

    @Test
    fun a4IsMidi69At440Hz() {
        val a4 = Pitch(69)
        assertEquals("A4", a4.label)
        assertEquals(440.0, a4.frequencyHz)
    }

    @Test
    fun pianoRangeIsA0ToC8() {
        assertEquals("A0", Pitch(Pitch.LOWEST_MIDI).label)
        assertEquals("C8", Pitch(Pitch.HIGHEST_MIDI).label)
        assertEquals(27.5, Pitch(Pitch.LOWEST_MIDI).frequencyHz, absoluteTolerance = 1e-9)
        assertEquals(4186.009, Pitch(Pitch.HIGHEST_MIDI).frequencyHz, absoluteTolerance = 0.001)
    }

    @Test
    fun labelsRoundTripForAll88Keys() {
        val keys = (Pitch.LOWEST_MIDI..Pitch.HIGHEST_MIDI).map(::Pitch)
        assertEquals(88, keys.size)
        for (key in keys) {
            assertEquals(key, Pitch.parse(key.label), "round trip of ${key.label}")
        }
    }

    @Test
    fun octavesChangeAtC() {
        assertEquals("B3", Pitch(59).label)
        assertEquals("C4", Pitch(60).label)
    }

    @Test
    fun parsesSharpsAndFlats() {
        assertEquals(54, Pitch.parse("F#3").midi)
        assertEquals("F#3", Pitch(54).label)
        assertEquals(Pitch.parse("A#3"), Pitch.parse("Bb3"))
        assertEquals(Pitch.parse("C4"), Pitch.parse("B#3"))
    }

    @Test
    fun rejectsTextThatIsNotANote() {
        for (text in listOf("", "C", "C#", "H4", "c4", "C 4", "C4x", "#4", "Cbb4")) {
            assertFailsWith<IllegalArgumentException>("\"$text\" should be rejected") { Pitch.parse(text) }
        }
    }

    @Test
    fun rejectsNotesOutsideThePiano() {
        for (text in listOf("G#0", "C9", "C#8", "A-1")) {
            assertFailsWith<IllegalArgumentException>("\"$text\" should be rejected") { Pitch.parse(text) }
        }
        assertFailsWith<IllegalArgumentException> { Pitch(20) }
        assertFailsWith<IllegalArgumentException> { Pitch(109) }
    }

    @Test
    fun nearestReportsCentsDeviation() {
        val sharp = assertNotNull(Pitch.nearest(445.0))
        assertEquals("A4", sharp.pitch.label)
        assertEquals(19.56, sharp.cents, absoluteTolerance = 0.01)

        val flat = assertNotNull(Pitch.nearest(435.0))
        assertEquals("A4", flat.pitch.label)
        assertEquals(-19.79, flat.cents, absoluteTolerance = 0.01)
    }

    @Test
    fun nearestFindsEveryKeyFromItsOwnFrequency() {
        for (midi in Pitch.LOWEST_MIDI..Pitch.HIGHEST_MIDI) {
            val estimate = assertNotNull(Pitch.nearest(Pitch(midi).frequencyHz))
            assertEquals(midi, estimate.pitch.midi)
            assertEquals(0.0, estimate.cents, absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun nearestReturnsNullOutsideThePiano() {
        assertNull(Pitch.nearest(20.0))
        assertNull(Pitch.nearest(5000.0))
    }

    @Test
    fun nearestRejectsInvalidFrequencies() {
        for (frequency in listOf(0.0, -440.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("$frequency should be rejected") { Pitch.nearest(frequency) }
        }
    }
}

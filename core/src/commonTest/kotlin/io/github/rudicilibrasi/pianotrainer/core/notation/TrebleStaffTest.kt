package io.github.rudicilibrasi.pianotrainer.core.notation

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrebleStaffTest {

    private fun position(label: String) = TrebleStaff.position(Pitch.parse(label))

    @Test
    fun everyWhiteKeyFromG3ToG5HasTheRightStep() {
        val expected = listOf(
            "G3" to -5, "A3" to -4, "B3" to -3, "C4" to -2, "D4" to -1, "E4" to 0, "F4" to 1, "G4" to 2,
            "A4" to 3, "B4" to 4, "C5" to 5, "D5" to 6, "E5" to 7, "F5" to 8, "G5" to 9,
        )
        for ((label, step) in expected) {
            val position = position(label)
            assertEquals(step, position.step, label)
            assertNull(position.accidental, label)
        }
    }

    @Test
    fun linesAndSpacesAlternate() {
        assertEquals(true, position("E4").isOnLine)
        assertEquals(false, position("F4").isOnLine)
        assertEquals(true, position("C4").isOnLine)
        assertEquals(false, position("B3").isOnLine)
    }

    @Test
    fun ledgerLinesBelowTheStaff() {
        assertEquals(emptyList(), position("D4").ledgerLines)
        assertEquals(listOf(-2), position("C4").ledgerLines)
        assertEquals(listOf(-2), position("B3").ledgerLines)
        assertEquals(listOf(-2, -4), position("A3").ledgerLines)
        assertEquals(listOf(-2, -4), position("G3").ledgerLines)
        assertEquals(listOf(-2, -4, -6), position("F3").ledgerLines)
    }

    @Test
    fun ledgerLinesAboveTheStaff() {
        assertEquals(emptyList(), position("F5").ledgerLines)
        assertEquals(emptyList(), position("G5").ledgerLines)
        assertEquals(listOf(10), position("A5").ledgerLines)
        assertEquals(listOf(10), position("B5").ledgerLines)
        assertEquals(listOf(10, 12), position("C6").ledgerLines)
    }

    @Test
    fun stemsGoUpBelowTheMiddleLineAndDownFromIt() {
        for (label in listOf("G3", "C4", "E4", "A4")) assertEquals(StemDirection.UP, position(label).stemDirection, label)
        for (label in listOf("B4", "C5", "F5", "A5")) assertEquals(StemDirection.DOWN, position(label).stemDirection, label)
    }

    @Test
    fun blackKeysUseTheLetterBelowWithASharp() {
        val cSharp = position("C#4")
        assertEquals(position("C4").step, cSharp.step)
        assertEquals(Accidental.SHARP, cSharp.accidental)
        assertEquals(position("F4").step, position("F#4").step)
        assertEquals(position("A3").step, position("Bb3").step) // Bb3 is the same key as A#3
        assertEquals(Accidental.SHARP, position("Bb3").accidental)
    }

    @Test
    fun octavesSevenStepsApart() {
        assertEquals(position("C4").step + 7, position("C5").step)
        assertEquals(position("A0").step + 7 * 7, position("A7").step)
    }
}

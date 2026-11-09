package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Deletion
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Insertion
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Match
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Substitution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlignmentTest {

    private fun pitches(labels: String) = labels.split(" ").map(Pitch::parse)

    /** Detected notes 500 ms apart, one per label; "?" is an uncertain note with no pitch. */
    private fun played(labels: String, msPerNote: Double = 500.0) = labels.split(" ").mapIndexed { i, label ->
        val start = i * msPerNote
        if (label == "?") {
            DetectedNote(null, start, start + msPerNote * 0.9, confidence = 0.1, uncertain = true)
        } else {
            DetectedNote(Pitch.parse(label), start, start + msPerNote * 0.9, confidence = 0.95, uncertain = false)
        }
    }

    private fun align(expected: String, detected: String) = Aligner.align(pitches(expected), played(detected))

    private val twinkle = "C4 C4 G4 G4 A4 A4 G4"

    @Test
    fun perfectPerformanceMatchesEveryNoteInOrder() {
        val alignment = align(twinkle, twinkle)
        assertTrue(alignment.isPerfect)
        assertEquals(7, alignment.correctCount)
        assertEquals(0, alignment.editCount)
        assertEquals(List(7) { Match(it, it) }, alignment.steps)
    }

    @Test
    fun playingSlowerOrFasterStillAligns() {
        val expected = pitches(twinkle)
        assertTrue(Aligner.align(expected, played(twinkle, msPerNote = 1500.0)).isPerfect)
        assertTrue(Aligner.align(expected, played(twinkle, msPerNote = 120.0)).isPerfect)
    }

    @Test
    fun oneWrongNoteIsASubstitution() {
        val alignment = align(twinkle, "C4 C4 G4 F4 A4 A4 G4")
        assertEquals(6, alignment.correctCount)
        assertEquals(1, alignment.wrongCount)
        assertEquals(0, alignment.missedCount)
        assertEquals(0, alignment.extraCount)
        assertEquals(Substitution(3, 3), alignment.steps[3])
    }

    @Test
    fun oneMissedNoteIsADeletion() {
        val alignment = align(twinkle, "C4 C4 G4 A4 A4 G4")
        assertEquals(6, alignment.correctCount)
        assertEquals(0, alignment.wrongCount)
        assertEquals(1, alignment.missedCount)
        assertEquals(0, alignment.extraCount)
        assertEquals(1, alignment.steps.count { it is Deletion && it.expectedIndex in 2..3 })
    }

    @Test
    fun oneExtraNoteIsAnInsertion() {
        val alignment = align(twinkle, "C4 C4 G4 G4 E4 A4 A4 G4")
        assertEquals(7, alignment.correctCount)
        assertEquals(0, alignment.wrongCount)
        assertEquals(0, alignment.missedCount)
        assertEquals(1, alignment.extraCount)
        assertEquals(Insertion(4), alignment.steps[4])
        assertFalse(alignment.isPerfect)
    }

    @Test
    fun twoSwappedNotesAreNotPerfect() {
        val alignment = align("C4 D4 E4 F4 G4", "C4 D4 F4 E4 G4")
        assertFalse(alignment.isPerfect)
        assertEquals(4, alignment.correctCount)
        assertEquals(2, alignment.editCount)
    }

    @Test
    fun reversedOrderScoresFarBelowPerfect() {
        // Every expected note appears somewhere, but in the wrong order (spec §11).
        val alignment = align("C4 D4 E4 F4 G4", "G4 F4 E4 D4 C4")
        assertFalse(alignment.isPerfect)
        assertEquals(1, alignment.correctCount)
        assertTrue(alignment.editCount >= 4)
    }

    @Test
    fun emptyRecordingMissesEveryNote() {
        val alignment = Aligner.align(pitches(twinkle), emptyList())
        assertEquals(0, alignment.correctCount)
        assertEquals(7, alignment.missedCount)
        assertEquals(List(7) { Deletion(it) }, alignment.steps)
    }

    @Test
    fun uncertainNotesAlignButNeverCountAsCorrect() {
        val alignment = align("C4 D4 E4", "C4 ? E4")
        assertEquals(listOf(Match(0, 0), Substitution(1, 1), Match(2, 2)), alignment.steps)
        assertEquals(2, alignment.correctCount)
    }

    @Test
    fun anUncertainNoteWithTheRightPitchIsStillNotCorrect() {
        val detected = listOf(DetectedNote(Pitch.parse("C4"), 0.0, 400.0, confidence = 0.3, uncertain = true))
        val alignment = Aligner.align(pitches("C4"), detected)
        assertEquals(listOf(Substitution(0, 0)), alignment.steps)
        assertEquals(0, alignment.correctCount)
    }

    @Test
    fun everyNoteAppearsExactlyOnce() {
        val alignment = align("C4 D4 E4 F4 G4 A4", "D4 C4 E4 E4 B4 G4 A4 C5")
        val expectedSeen = alignment.steps.mapNotNull {
            when (it) {
                is Match -> it.expectedIndex
                is Substitution -> it.expectedIndex
                is Deletion -> it.expectedIndex
                is Insertion -> null
            }
        }
        val detectedSeen = alignment.steps.mapNotNull {
            when (it) {
                is Match -> it.detectedIndex
                is Substitution -> it.detectedIndex
                is Insertion -> it.detectedIndex
                is Deletion -> null
            }
        }
        assertEquals((0 until 6).toList(), expectedSeen)
        assertEquals((0 until 8).toList(), detectedSeen)
    }

    @Test
    fun tiesAreBrokenDeterministically() {
        // Swapping two notes has several equally cheap alignments; the same one must win every time.
        val first = align("C4 D4", "D4 C4")
        repeat(5) { assertEquals(first, align("C4 D4", "D4 C4")) }
        assertEquals(listOf(Deletion(0), Match(1, 0), Insertion(1)), first.steps)
    }

    @Test
    fun alignsAgainstAPhraseIgnoringRests() {
        val phrase = Phrase(0, listOf(NoteEvent.note("E4", 1.0), NoteEvent.rest(1.0), NoteEvent.note("D4", 1.0)), 0.0)
        assertTrue(Aligner.align(phrase, played("E4 D4")).isPerfect)
    }
}

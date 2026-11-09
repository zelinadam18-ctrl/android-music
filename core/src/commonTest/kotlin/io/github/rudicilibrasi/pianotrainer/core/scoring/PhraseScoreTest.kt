package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhraseScoreTest {

    private val tempoBpm = 120.0 // 500 ms per beat

    private val scale = Phrase(0, listOf("C4", "D4", "E4", "F4", "G4").map { NoteEvent.note(it, 1.0) }, 0.0)

    /** One reliable note per label, [msPerNote] apart. */
    private fun played(labels: String, msPerNote: Double = 500.0) = labels.split(" ").mapIndexed { i, label ->
        DetectedNote(Pitch.parse(label), i * msPerNote, i * msPerNote + 400.0, 0.9, uncertain = false)
    }

    private fun score(detected: List<DetectedNote>) = PhraseScorer.score(scale, tempoBpm, detected)

    @Test
    fun perfectPerformanceScores100() {
        val result = score(played("C4 D4 E4 F4 G4"))
        assertEquals(1.0, result.noteAccuracy)
        assertEquals(1.0, result.orderAccuracy)
        assertEquals(1.0, result.timingAccuracy)
        assertEquals(1.0, result.tempoAccuracy)
        assertEquals(100.0, result.rawScore)
        assertEquals(100, result.score)
    }

    @Test
    fun emptyRecordingScores0() {
        val result = score(emptyList())
        assertEquals(0.0, result.rawScore)
        assertEquals(0, result.score)
    }

    @Test
    fun scoreIsDeterministic() {
        val detected = played("C4 E4 D4 F4 F4 A4", msPerNote = 530.0)
        val first = score(detected)
        repeat(10) { assertEquals(first, score(detected.toList())) }
    }

    @Test
    fun swappedNotesCannotReach100() {
        val result = score(played("C4 D4 F4 E4 G4"))
        assertTrue(result.score < 100, "score ${result.score}")
        assertTrue(result.orderAccuracy < 1.0)
    }

    @Test
    fun reversedOrderScoresFarBelowPerfect() {
        // Every note is there, but backwards.
        val result = score(played("G4 F4 E4 D4 C4"))
        assertEquals(0.2, result.orderAccuracy)
        assertTrue(result.score < 40, "score ${result.score}")
    }

    @Test
    fun aWrongNoteCostsItsShareOfNoteAccuracy() {
        val result = score(played("C4 D4 A4 F4 G4"))
        assertEquals(0.8, result.noteAccuracy)
        assertEquals(1.0, result.orderAccuracy)
        assertEquals(91, result.score) // 45 × 0.8 + 20 + 20 + 15 = 91
    }

    @Test
    fun anExtraNoteCostsHalfANote() {
        val result = score(played("C4 D4 E4 B4 F4 G4"))
        assertEquals(0.9, result.noteAccuracy)
        assertTrue(result.score < 100)
    }

    @Test
    fun uncertainNotesNeverCountAsCorrect() {
        val detected = played("C4 D4 E4 F4 G4").mapIndexed { i, note -> if (i == 2) note.copy(uncertain = true) else note }
        val result = score(detected)
        assertEquals(0.8, result.noteAccuracy)
        assertTrue(result.score < 100)
    }

    @Test
    fun playingWithin15PercentOfTheTempoIsNotPenalised() {
        assertEquals(100, score(played("C4 D4 E4 F4 G4", msPerNote = 560.0)).score)
    }

    @Test
    fun playingMuchSlowerLosesOnlyTempoPoints() {
        val result = score(played("C4 D4 E4 F4 G4", msPerNote = 1_000.0)) // half speed
        assertEquals(1.0, result.timingAccuracy)
        assertEquals(0.0, result.tempoAccuracy)
        assertEquals(85, result.score)
    }

    @Test
    fun roundsHalfUpExactlyOnce() {
        assertEquals(50, PhraseScorer.roundScore(49.5))
        assertEquals(49, PhraseScorer.roundScore(49.4))
        assertEquals(49, PhraseScorer.roundScore(49.49))
        assertEquals(90, PhraseScorer.roundScore(89.5))
        assertEquals(89, PhraseScorer.roundScore(89.4))
        assertEquals(0, PhraseScorer.roundScore(0.0))
        assertEquals(0, PhraseScorer.roundScore(0.4))
        assertEquals(1, PhraseScorer.roundScore(0.5))
        assertEquals(100, PhraseScorer.roundScore(99.5))
        assertEquals(100, PhraseScorer.roundScore(100.0))
        // A value meant to be 49.5 that floating point computed as slightly less still rounds up.
        assertEquals(50, PhraseScorer.roundScore(49.49999999999999))
    }

    @Test
    fun roundedScoreDrivesTheUnlockThreshold() {
        assertTrue(PhraseScorer.roundScore(49.5) >= Progression.UNLOCK_SCORE)
        assertTrue(PhraseScorer.roundScore(49.4) < Progression.UNLOCK_SCORE)
        assertTrue(PhraseScorer.roundScore(89.5) >= Progression.STAR_SCORE)
        assertTrue(PhraseScorer.roundScore(89.4) < Progression.STAR_SCORE)
    }

    @Test
    fun weightsAddUpTo100() {
        assertEquals(100, PhraseScorer.NOTE_WEIGHT + PhraseScorer.ORDER_WEIGHT + PhraseScorer.TIMING_WEIGHT + PhraseScorer.TEMPO_WEIGHT)
    }
}

package io.github.rudicilibrasi.pianotrainer.ui.graphs

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The cases from #39's alignment tests, as the student would see them. */
class ComparisonGraphTest {

    private val tempo = 120.0 // 500 ms per beat
    private val phrase = Phrase(0, SongFormat.parseEvents("C4/1 D4/1 E4/1 F4/1 G4/1"), 0.0)

    private fun played(labels: String, msPerNote: Double = 500.0, startMs: Double = 0.0) = labels.split(" ").mapIndexed { i, label ->
        val start = startMs + i * msPerNote
        DetectedNote(Pitch.parse(label), start, start + msPerNote * 0.8, 0.9, uncertain = false)
    }

    private fun model(detected: List<DetectedNote>, aligned: Boolean = true) =
        ComparisonModel.of(phrase, tempo, detected, PhraseScorer.score(phrase, tempo, detected), aligned)

    private fun ComparisonModel.played() = bars.filter { !it.expected }
    private fun ComparisonModel.expected() = bars.filter { it.expected }

    @Test
    fun aPerfectPerformanceLinesUpExactly() {
        val model = model(played("C4 D4 E4 F4 G4"))
        assertEquals(mapOf(NoteOutcome.Correct to 5, NoteOutcome.Wrong to 0, NoteOutcome.Missed to 0, NoteOutcome.Extra to 0), model.counts)
        assertEquals(5, model.pairs.size)
        model.pairs.forEach { pair ->
            assertEquals(pair.expectedRow, pair.playedRow)
            assertTrue(abs(pair.expectedX - pair.playedX) < 1e-3, "played on time")
        }
        assertEquals("Comparison with the reference: 5 correct, 0 wrong, 0 missed, 0 extra", model.description)
    }

    @Test
    fun aWrongNoteIsPairedInADifferentRow() {
        val model = model(played("C4 D4 A4 F4 G4"))
        val wrong = model.pairs.single { it.outcome == NoteOutcome.Wrong }
        assertEquals(model.rows.indexOf(Pitch.parse("E4").midi), wrong.expectedRow)
        assertEquals(model.rows.indexOf(Pitch.parse("A4").midi), wrong.playedRow)
        assertEquals("A4", model.played().single { it.outcome == NoteOutcome.Wrong }.label)
    }

    @Test
    fun aMissedNoteIsAnExpectedOutlineWithNothingPlayed() {
        val model = model(played("C4 D4 F4 G4").let { notes -> notes.take(2) + notes.drop(2).map { it.copy(startMs = it.startMs + 500, endMs = it.endMs + 500) } })
        val missed = model.bars.single { it.outcome == NoteOutcome.Missed }
        assertTrue(missed.expected)
        assertEquals("E4", missed.label)
        assertEquals(4, model.pairs.size)
    }

    @Test
    fun anExtraNoteIsPlayedWithNoExpectedNote() {
        val detected = played("C4 D4").let { it + DetectedNote(Pitch.parse("B4"), 1_250.0, 1_400.0, 0.9, false) } +
            played("E4 F4 G4", startMs = 1_000.0)
        val model = model(detected.sortedBy { it.startMs })
        val extra = model.bars.single { it.outcome == NoteOutcome.Extra }
        assertTrue(!extra.expected)
        assertEquals("B4", extra.label)
        assertEquals(5, model.counts[NoteOutcome.Correct])
    }

    @Test
    fun swappedNotesShowAsAMissAndAnExtraAtTheWrongTime() {
        val model = model(played("C4 D4 F4 E4 G4"))
        assertTrue(model.counts.getValue(NoteOutcome.Correct) < 5)
        assertTrue(model.counts.getValue(NoteOutcome.Missed) + model.counts.getValue(NoteOutcome.Wrong) >= 1)
    }

    @Test
    fun reversedOrderIsMostlyNotCorrect() {
        val model = model(played("G4 F4 E4 D4 C4"))
        assertEquals(1, model.counts[NoteOutcome.Correct])
    }

    @Test
    fun anEmptyRecordingMissesEverything() {
        val model = model(emptyList())
        assertEquals(5, model.counts[NoteOutcome.Missed])
        assertEquals(emptyList(), model.played())
        assertEquals(emptyList(), model.pairs)
    }

    @Test
    fun aSlowerPerformanceLinesUpWhenMatchedToTempoButNotAsRecorded() {
        // Played evenly 40% slower, starting 2 s into the recording.
        val detected = played("C4 D4 E4 F4 G4", msPerNote = 700.0, startMs = 2_000.0)
        val matched = model(detected)
        matched.pairs.forEach { assertTrue(abs(it.expectedX - it.playedX) < 1e-3, "lined up: ${it.expectedX} vs ${it.playedX}") }

        val raw = model(detected, aligned = false)
        assertTrue(abs(raw.pairs.first().expectedX - raw.pairs.first().playedX) < 1e-3, "both start together")
        assertTrue(raw.pairs.last().playedX > raw.pairs.last().expectedX + 0.05f, "but the slower notes drift later")
    }

    @Test
    fun aLateNoteShowsAsAnOffset() {
        val detected = played("C4 D4 E4 F4 G4").toMutableList()
        detected[2] = detected[2].copy(startMs = detected[2].startMs + 200, endMs = detected[2].endMs + 200)
        val late = model(detected).pairs[2]
        assertTrue(late.playedX > late.expectedX, "played after where it was expected")
    }
}

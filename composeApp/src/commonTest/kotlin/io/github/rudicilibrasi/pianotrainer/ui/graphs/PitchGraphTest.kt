package io.github.rudicilibrasi.pianotrainer.ui.graphs

import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.analysis.GraphData
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PitchGraphTest {

    private fun result(notes: List<DetectedNote>, seconds: Double = 4.0, pitchHz: List<Double?> = List(400) { null }) = AnalysisResult(
        analysisVersion = 1,
        sampleRate = 44100,
        durationSeconds = seconds,
        notes = notes,
        onsets = emptyList(),
        graphs = GraphData(seconds / pitchHz.size, 40.0, Array(pitchHz.size) { FloatArray(1) }, FloatArray(pitchHz.size), pitchHz),
    )

    private fun note(label: String?, startMs: Double, endMs: Double, uncertain: Boolean = false) =
        DetectedNote(label?.let(Pitch::parse), startMs, endMs, if (uncertain) 0.2 else 0.9, uncertain)

    @Test
    fun rowsCoverG3ToG5WithWhiteKeysLabelled() {
        val model = PitchGraphModel.of(result(emptyList()))
        assertEquals(Pitch.parse("G5").midi, model.rows.first())
        assertEquals(Pitch.parse("G3").midi, model.rows.last())
        assertEquals(25, model.rows.size)
        assertEquals(listOf("G5", "F5", "E5", "D5", "C5", "B4", "A4", "G4", "F4", "E4", "D4", "C4", "B3", "A3", "G3"), model.labelledRows.map { it.second })
        assertEquals("Pitch over time: no notes heard", model.description)
    }

    @Test
    fun rowsWidenToFitNotesOutsideG3ToG5() {
        val model = PitchGraphModel.of(result(listOf(note("C3", 0.0, 500.0), note("C6", 500.0, 900.0))))
        assertEquals(Pitch.parse("C6").midi, model.rows.first())
        assertEquals(Pitch.parse("C3").midi, model.rows.last())
    }

    @Test
    fun eachNoteIsABarInItsRowAtItsTime() {
        val model = PitchGraphModel.of(result(listOf(note("C4", 1_000.0, 1_500.0), note("E4", 2_000.0, 3_000.0))))
        val (c4, e4) = model.bars
        assertEquals(0.25f, c4.startX)
        assertEquals(0.375f, c4.endX)
        assertEquals(model.rows.indexOf(Pitch.parse("C4").midi), c4.row)
        assertEquals(model.rows.indexOf(Pitch.parse("E4").midi), e4.row)
        assertTrue(model.rowCentreY(e4.row!!) < model.rowCentreY(c4.row!!), "higher notes are higher up")
        assertEquals("Pitch over time: C4 at 1 s, E4 at 2 s", model.description)
    }

    @Test
    fun uncertainNotesAreMarkedAndUnpitchedOnesSpanEveryRow() {
        val model = PitchGraphModel.of(result(listOf(note("C4", 0.0, 400.0, uncertain = true), note(null, 500.0, 800.0, uncertain = true))))
        val (unsure, unknown) = model.bars
        assertTrue(unsure.uncertain)
        assertEquals("?", unsure.label)
        assertTrue(unknown.uncertain)
        assertNull(unknown.row)
    }

    @Test
    fun thePitchTrackBreaksWhereNothingIsVoiced() {
        val a4 = Pitch.A4_HZ
        val sharp = a4 * 2.0.pow(0.3 / 12) // 30 cents sharp
        val track = List(400) { c -> if (c in 100 until 200) a4 else if (c in 250 until 300) sharp else null }
        val model = PitchGraphModel.of(result(emptyList(), pitchHz = track))
        assertEquals(2, model.pitchTrack.size)
        val row = model.rows.indexOf(Pitch.A4_MIDI)
        assertTrue(model.pitchTrack[0].all { abs(it.second - model.rowCentreY(row)) < 1e-4 })
        // Slightly sharp sits slightly above the row's centre, but still inside the row.
        assertTrue(model.pitchTrack[1].all { it.second < model.rowCentreY(row) && it.second > model.rowCentreY(row) - model.rowHeight / 2 })
    }

    @Test
    fun aRealRecordingShowsWhichNotesWerePlayedAndWhen() = runTest {
        val notes = listOf("C4", "E4", "G4").mapIndexed { i, label -> SynthNote(Pitch.parse(label), 0.3 + 0.6 * i, 0.5) }
        val samples = Synthesizer(44100).render(notes, 2.2)
        val model = PitchGraphModel.of(AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, 44100)))
        assertEquals(listOf("C4", "E4", "G4"), model.bars.map { it.label })
        model.bars.zip(listOf(0.3, 0.9, 1.5)).forEach { (bar, start) ->
            assertTrue(abs(bar.startX * model.durationSeconds - start) < 0.03)
        }
        assertTrue(model.pitchTrack.isNotEmpty())
    }
}

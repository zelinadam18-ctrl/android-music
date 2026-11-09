package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimingTest {

    private val tempoBpm = 120.0 // 500 ms per beat

    /** Twinkle's first bar and a half: six quarter notes and a half note. */
    private val phrase = Phrase(
        0,
        listOf("C4", "C4", "G4", "G4", "A4", "A4").map { NoteEvent.note(it, 1.0) } + NoteEvent.note("G4", 2.0),
        0.0,
    )

    private val labels = listOf("C4", "C4", "G4", "G4", "A4", "A4", "G4")

    /** The phrase's notes, played starting at [startsMs]. */
    private fun played(startsMs: List<Double>, labels: List<String> = this.labels) = startsMs.zip(labels) { start, label ->
        DetectedNote(Pitch.parse(label), start, start + 300.0, 0.9, uncertain = false)
    }

    private fun measure(detected: List<DetectedNote>): TimingResult =
        Timing.measure(phrase, tempoBpm, detected, Aligner.align(phrase, detected))

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")

    @Test
    fun exactTimingIsPerfect() {
        val result = measure(played(List(7) { it * 500.0 }))
        assertEquals(7, result.matchedCount)
        assertClose(1.0, result.tempoRatio!!)
        assertClose(0.0, result.offsetMs!!, 1e-6)
        result.deviationsBeats.forEach { assertClose(0.0, it, 1e-9) }
        assertEquals(1.0, result.timingAccuracy)
        assertClose(0.0, result.rhythmSpread)
        assertEquals(1.0, result.tempoAccuracy)
        assertFalse(result.tempoFlagged)
    }

    @Test
    fun startingLateInTheRecordingIsNotPenalised() {
        val result = measure(played(List(7) { 2_000.0 + it * 500.0 }))
        assertClose(2_000.0, result.offsetMs!!, 1e-6)
        assertEquals(1.0, result.timingAccuracy)
        assertEquals(1.0, result.tempoAccuracy)
    }

    @Test
    fun evenly20PercentSlowerKeepsGoodTimingButFlagsTempo() {
        val result = measure(played(List(7) { it * 600.0 }))
        assertClose(1.2, result.tempoRatio!!)
        assertEquals(1.0, result.timingAccuracy)
        assertClose(0.0, result.rhythmSpread)
        assertTrue(result.tempoFlagged)
        // 20% is 5 points past the 15% tolerance, out of a 35-point ramp.
        assertClose(1.0 - 0.05 / 0.35, result.tempoAccuracy)
    }

    @Test
    fun tenPercentFasterIsWithinTheTempoTolerance() {
        val result = measure(played(List(7) { it * 450.0 }))
        assertClose(0.9, result.tempoRatio!!)
        assertEquals(1.0, result.tempoAccuracy)
        assertFalse(result.tempoFlagged)
    }

    @Test
    fun unevenRhythmLowersTimingAccuracyAndWidensTheSpread() {
        // Notes alternately rushed and dragged by about a third of a beat.
        val starts = listOf(0.0, 330.0, 1_170.0, 1_330.0, 2_170.0, 2_330.0, 3_000.0)
        val result = measure(played(starts))
        assertEquals(7, result.matchedCount)
        assertTrue(result.timingAccuracy < 0.8, "timing accuracy ${result.timingAccuracy}")
        assertTrue(result.rhythmSpread > 0.2, "spread ${result.rhythmSpread}")
    }

    @Test
    fun tinyBeginnerWobblesAreNotPenalised() {
        // ±30 ms is well inside ⅛ beat (62.5 ms at 120 BPM).
        val starts = List(7) { it * 500.0 + if (it % 2 == 0) 30.0 else -30.0 }
        val result = measure(played(starts))
        assertEquals(1.0, result.timingAccuracy)
        assertTrue(result.rhythmSpread > 0.0)
    }

    @Test
    fun onlyOneMatchedNoteGivesZeroTimingAndTempo() {
        val detected = played(listOf(0.0, 500.0), listOf("C4", "D5"))
        val result = measure(detected)
        assertEquals(1, result.matchedCount)
        assertNull(result.tempoRatio)
        assertEquals(0.0, result.timingAccuracy)
        assertEquals(0.0, result.tempoAccuracy)
        assertTrue(result.tempoFlagged)
    }

    @Test
    fun emptyRecordingGivesZeroTimingAndTempo() {
        val result = measure(emptyList())
        assertEquals(0, result.matchedCount)
        assertEquals(0.0, result.timingAccuracy)
        assertEquals(0.0, result.tempoAccuracy)
    }

    @Test
    fun onlyMatchedNotesAreTimed() {
        // A wrong note played wildly late doesn't affect timing: only correct notes are fitted.
        val starts = List(7) { it * 500.0 }.toMutableList().also { it[3] = 9_000.0 }
        val labels = labels.toMutableList().also { it[3] = "F4" }
        val result = measure(played(starts, labels).sortedBy { it.startMs })
        assertEquals(6, result.matchedCount)
        assertEquals(1.0, result.timingAccuracy)
    }

    @Test
    fun timingPenaltyRampsFromAnEighthToAHalfBeat() {
        assertEquals(0.0, Timing.timingPenalty(0.0))
        assertEquals(0.0, Timing.timingPenalty(0.125))
        assertEquals(0.0, Timing.timingPenalty(-0.125))
        assertClose(0.5, Timing.timingPenalty(0.3125))
        assertEquals(1.0, Timing.timingPenalty(0.5))
        assertEquals(1.0, Timing.timingPenalty(-2.0))
    }
}

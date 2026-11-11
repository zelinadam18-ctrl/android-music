package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackHighlightTest {

    private fun highlight(events: String, tempo: Double) = PlaybackHighlight(Phrase(0, SongFormat.parseEvents(events), 0.0), tempo)

    @Test
    fun twinklesHalfNoteStaysHighlightedForItsWholeLength() {
        // 120 BPM: quarters every 500 ms, then a half note from 3000 to 4000 ms.
        val h = highlight("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", 120.0)
        assertEquals(0, h.noteAt(0))
        assertEquals(0, h.noteAt(499))
        assertEquals(1, h.noteAt(500), "the repeated C4 is a new note")
        assertEquals(6, h.noteAt(3000))
        assertEquals(6, h.noteAt(3999))
        assertNull(h.noteAt(4000), "after the last note ends")
    }

    @Test
    fun preludeSixteenthsChangeEveryFewHundredMilliseconds() {
        // 66 BPM: a sixteenth is about 227 ms.
        val h = highlight("C4/0.25 E4/0.25 G4/0.25 C5/0.25", 66.0)
        val sixteenth = 60_000.0 / 66 / 4
        assertEquals(listOf(0, 1, 2, 3), listOf(0.5, 1.5, 2.5, 3.5).map { h.noteAt((it * sixteenth).toLong()) })
    }

    @Test
    fun restsAreSkippedAndCountNoIndex() {
        // E4, a rest, then D4: D4 is note 1 (rests aren't counted), and nothing lights up during the rest.
        val h = highlight("E4/1 R/1 D4/1", 60.0)
        assertEquals(0, h.noteAt(500))
        assertNull(h.noteAt(1_500))
        assertEquals(1, h.noteAt(2_500))
    }

    @Test
    fun stoppedPlaybackClearsTheHighlight() {
        val h = highlight("C4/1 D4/1", 60.0)
        assertNull(h.noteAt(null))
        assertNull(h.noteAt(-10))
    }
}

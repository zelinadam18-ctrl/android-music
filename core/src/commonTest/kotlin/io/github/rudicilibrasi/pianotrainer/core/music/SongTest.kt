package io.github.rudicilibrasi.pianotrainer.core.music

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent.Companion.note
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent.Companion.rest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SongTest {

    private fun song(
        phrases: List<List<NoteEvent>>,
        tempoBpm: Double = 120.0,
        timeSignature: TimeSignature = TimeSignature(4, 4),
        pickupBeats: Double = 0.0,
    ) = Song(
        id = "test",
        title = "Test Song",
        composer = null,
        tempoBpm = tempoBpm,
        timeSignature = timeSignature,
        key = "C major",
        source = "traditional",
        license = "Public domain",
        sourceVerified = false,
        phrases = phrases,
        pickupBeats = pickupBeats,
    )

    private val twinkleOpening = song(
        listOf(
            listOf(note("C4", 1.0), note("C4", 1.0), note("G4", 1.0), note("G4", 1.0), note("A4", 1.0), note("A4", 1.0), note("G4", 2.0)),
            listOf(note("F4", 1.0), note("F4", 1.0), note("E4", 1.0), note("E4", 1.0), note("D4", 1.0), note("D4", 1.0), note("C4", 2.0)),
        ),
    )

    @Test
    fun convertsBeatsToMillisecondsAtTheSongTempo() {
        assertEquals(500.0, song(listOf(listOf(note("C4", 1.0))), tempoBpm = 120.0).msPerBeat)
        assertEquals(1000.0, song(listOf(listOf(note("C4", 1.0))), tempoBpm = 60.0).msPerBeat)
        assertEquals(600.0, song(listOf(listOf(note("C4", 1.0))), tempoBpm = 100.0).msPerBeat)
    }

    @Test
    fun timesEachEventFromTheStartOfItsPhrase() {
        val phrase = twinkleOpening.phrases[1]
        val timed = twinkleOpening.timedEvents(phrase)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0), timed.map { it.startBeat })
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0, 2000.0, 2500.0, 3000.0), timed.map { it.startMs })
        assertEquals(1000.0, timed.last().durationMs)
        assertEquals(4000.0, timed.last().endMs)
        assertEquals(4000.0, twinkleOpening.phraseDurationMs(phrase))
    }

    @Test
    fun restsTakeTimeButAreNotNotes() {
        val phrase = song(listOf(listOf(note("C4", 1.0), rest(1.0), note("E4", 1.5), note("D4", 0.5)))).phrases[0]
        assertEquals(3, phrase.noteCount)
        assertEquals(listOf("C4", "E4", "D4"), phrase.notes.map { it.pitch!!.label })
        assertEquals(4.0, phrase.durationBeats)
        val timed = phrase.timedEvents(tempoBpm = 60.0)
        assertTrue(timed[1].event.isRest)
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3500.0), timed.map { it.startMs })
        assertEquals(1500.0, timed[2].durationMs) // dotted quarter
    }

    @Test
    fun phrasesKnowWhereTheyStartAndHowManyNotesTheyHave() {
        val song = song(
            listOf(
                listOf(note("C4", 1.0), note("D4", 1.0), note("E4", 2.0)),
                listOf(rest(1.0), note("G4", 1.0), note("A4", 1.0), note("B4", 0.5), note("C5", 0.5)),
                listOf(note("C5", 4.0)),
            ),
        )
        assertEquals(listOf(0.0, 4.0, 8.0), song.phrases.map { it.startBeat })
        assertEquals(listOf(3, 4, 1), song.phrases.map { it.noteCount })
        assertEquals(listOf(1, 2, 3), song.phrases.map { it.number })
        assertEquals(8, song.noteCount)
        assertEquals(12.0, song.durationBeats)
    }

    @Test
    fun joiningThePhrasesGivesBackTheWholeSong() {
        val all = twinkleOpening.phrases.flatMap { it.events }
        assertEquals(twinkleOpening.events, all)
        assertEquals("C4 C4 G4 G4 A4 A4 G4 F4 F4 E4 E4 D4 D4 C4", all.joinToString(" ") { it.pitch!!.label })
    }

    @Test
    fun timeSignatureGivesTheBarLengthInQuarterBeats() {
        assertEquals(4.0, TimeSignature(4, 4).quarterBeatsPerBar)
        assertEquals(3.0, TimeSignature(3, 4).quarterBeatsPerBar)
        assertEquals(3.0, TimeSignature(6, 8).quarterBeatsPerBar)
        assertEquals("3/4", TimeSignature(3, 4).toString())
    }

    @Test
    fun rejectsInvalidSongs() {
        assertFailsWith<IllegalArgumentException> { song(emptyList()) }
        assertFailsWith<IllegalArgumentException> { song(listOf(emptyList())) }
        assertFailsWith<IllegalArgumentException> { song(listOf(listOf(rest(4.0)))) }
        assertFailsWith<IllegalArgumentException> { song(listOf(listOf(note("C4", 1.0))), tempoBpm = 0.0) }
        assertFailsWith<IllegalArgumentException> { song(listOf(listOf(note("C4", 1.0))), pickupBeats = 4.0) }
        assertFailsWith<IllegalArgumentException> { note("C4", 0.0) }
        assertFailsWith<IllegalArgumentException> { rest(-1.0) }
        assertFailsWith<IllegalArgumentException> { TimeSignature(3, 3) }
    }
}

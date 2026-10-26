package io.github.rudicilibrasi.pianotrainer.core.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongFormatTest {

    private fun songJson(
        phrases: String = """["C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", "F4/1 F4/1 E4/1 E4/1 D4/1 D4/1 C4/2"]""",
        timeSignature: String = "4/4",
        extra: String = "",
    ) = """
        {
          "id": "sample",
          "title": "Sample Song",
          "tempoBpm": 90,
          "timeSignature": "$timeSignature",
          "key": "C major",
          "source": "traditional",
          "license": "Public domain",
          "sourceVerified": false,
          $extra
          "phrases": $phrases
        }
    """.trimIndent()

    @Test
    fun loadsASampleSong() {
        val song = SongFormat.parseSong(songJson(), "sample.json")
        assertEquals("sample", song.id)
        assertEquals("Sample Song", song.title)
        assertNull(song.composer)
        assertEquals(90.0, song.tempoBpm)
        assertEquals(TimeSignature(4, 4), song.timeSignature)
        assertEquals(2, song.phrases.size)
        assertEquals(listOf(7, 7), song.phrases.map { it.noteCount })
        assertEquals("C4 C4 G4 G4 A4 A4 G4", song.phrases[0].notes.joinToString(" ") { it.pitch!!.label })
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0), song.phrases[0].events.map { it.beats })
    }

    @Test
    fun readsOptionalFields() {
        val song = SongFormat.parseSong(
            songJson(
                timeSignature = "3/4",
                extra = """"composer": "Someone", "pickupBeats": 1, "arrangerNotes": "Simplified rhythm",""",
            ),
            "sample.json",
        )
        assertEquals("Someone", song.composer)
        assertEquals(1.0, song.pickupBeats)
        assertEquals("Simplified rhythm", song.arrangerNotes)
        assertEquals(TimeSignature(3, 4), song.timeSignature)
    }

    @Test
    fun parsesRestsSharpsAndDottedNotes() {
        val events = SongFormat.parseEvents("  C4/1   R/0.5 F#4/1.5\tBb3/0.25 ")
        assertEquals(listOf("C4", null, "F#4", "A#3"), events.map { it.pitch?.label })
        assertEquals(listOf(1.0, 0.5, 1.5, 0.25), events.map { it.beats })
    }

    @Test
    fun formatsEventsBackIntoTheSameSyntax() {
        val text = "C4/1 R/0.5 F#4/1.5 A#3/0.25 G4/2"
        assertEquals(text, SongFormat.formatEvents(SongFormat.parseEvents(text)))
    }

    @Test
    fun errorsNameTheFileAndPhrase() {
        val error = assertFailsWith<SongFormatException> {
            SongFormat.parseSong(songJson(phrases = """["C4/1 D4/1", "E4/1 X4/1"]"""), "twinkle.json")
        }
        val message = error.message!!
        assertTrue(message.startsWith("twinkle.json: phrase 2: event 2 \"X4/1\""), message)
    }

    @Test
    fun rejectsBadEvents() {
        val cases = mapOf(
            "C4" to "should look like C4/1",
            "C4/0" to "invalid duration",
            "C4/abc" to "invalid duration",
            "C4/1/2" to "should look like C4/1",
            "H4/1" to "not a note",
            "" to "is empty",
        )
        for ((events, expected) in cases) {
            val error = assertFailsWith<SongFormatException>("\"$events\"") {
                SongFormat.parseSong(songJson(phrases = """["$events"]"""), "bad.json")
            }
            assertTrue(error.message!!.contains(expected), "\"$events\": ${error.message}")
        }
    }

    @Test
    fun rejectsBadSongs() {
        val cases = mapOf(
            "missing field" to songJson().replace("\"tempoBpm\": 90,", ""),
            "unknown field" to songJson(extra = """"tempo": 90,"""),
            "not JSON" to "{ this is not json",
            "bad time signature" to songJson(timeSignature = "four"),
            "zero tempo" to songJson().replace("\"tempoBpm\": 90", "\"tempoBpm\": 0"),
            "rest-only phrase" to songJson(phrases = """["R/4"]"""),
            "no phrases" to songJson(phrases = "[]"),
        )
        for ((name, text) in cases) {
            val error = assertFailsWith<SongFormatException>(name) { SongFormat.parseSong(text, "bad.json") }
            assertTrue(error.message!!.startsWith("bad.json: "), "$name: ${error.message}")
        }
    }

    @Test
    fun readsTheIndexInMenuOrder() {
        assertEquals(listOf("twinkle", "mary", "ode-to-joy"), SongFormat.parseIndex("""{ "songs": ["twinkle", "mary", "ode-to-joy"] }"""))
        assertEquals(emptyList(), SongFormat.parseIndex("""{ "songs": [] }"""))
    }

    @Test
    fun rejectsABadIndex() {
        assertFailsWith<SongFormatException> { SongFormat.parseIndex("""{ "songs": ["a", "b", "a"] }""") }
        assertFailsWith<SongFormatException> { SongFormat.parseIndex("""{ "titles": [] }""") }
    }
}

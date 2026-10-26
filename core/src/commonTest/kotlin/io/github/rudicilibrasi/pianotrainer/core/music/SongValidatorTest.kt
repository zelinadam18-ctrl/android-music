package io.github.rudicilibrasi.pianotrainer.core.music

import io.github.rudicilibrasi.pianotrainer.core.music.SongIssue.Severity.ERROR
import io.github.rudicilibrasi.pianotrainer.core.music.SongIssue.Severity.WARNING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongValidatorTest {

    private fun song(
        vararg phrases: String,
        id: String = "test",
        license: String = "Public domain",
        source: String = "traditional",
        verified: Boolean = true,
    ) = Song(
        id = id, title = "Test", composer = null, tempoBpm = 100.0, timeSignature = TimeSignature(4, 4),
        key = "C major", source = source, license = license, sourceVerified = verified,
        phrases = phrases.map(SongFormat::parseEvents),
    )

    private val good = "C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"

    private fun List<SongIssue>.messages(severity: SongIssue.Severity) = filter { it.severity == severity }.map { it.message }

    @Test
    fun aGoodSongHasNoIssues() {
        assertEquals(emptyList(), SongValidator.validate(song(good, "G3/0.5 A3/0.5 B3/1.5 C4/0.25 D4/0.75 E4/3 F5/4 G5/6")))
    }

    @Test
    fun phraseLengthsOutsideSixToEightAreFlagged() {
        val issues = SongValidator.validate(
            song("C4/1 D4/1 E4/1 F4/1 G4/1", "C4/1 D4/1 E4/1", "C4/1 C4/1 C4/1 C4/1 C4/1 C4/1 C4/1 C4/1 C4/1 C4/1 C4/1"),
        )
        assertEquals(listOf("test: phrase 1 has 5 notes; 6–8 is the target"), issues.messages(WARNING))
        assertEquals(
            listOf("test: phrase 2 has 3 notes; phrases must have 4–10", "test: phrase 3 has 11 notes; phrases must have 4–10"),
            issues.messages(ERROR),
        )
    }

    @Test
    fun restsDoNotCountAsNotes() {
        val issues = SongValidator.validate(song("C4/1 R/1 D4/1 R/1 E4/1 R/1 F4/1 R/1 G4/1"))
        assertEquals(listOf("test: phrase 1 has 5 notes; 6–8 is the target"), issues.messages(WARNING))
    }

    @Test
    fun blackKeysAreErrors() {
        val issues = SongValidator.validate(song("C4/1 C#4/1 D4/1 E4/1 F4/1 G4/1"))
        assertEquals(listOf("test: phrase 1, event 2 (C#4/1.0) is a black key; songs use C major (white keys only)"), issues.messages(ERROR))
    }

    @Test
    fun notesOutsideG3ToG5AreErrors() {
        val messages = SongValidator.validate(song("F3/1 G3/1 A4/1 G5/1 A5/1 C4/1")).messages(ERROR)
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("event 1 (F3/1.0) is outside the beginner range G3–G5"), messages[0])
        assertTrue(messages[1].contains("event 5 (A5/1.0) is outside"), messages[1])
    }

    @Test
    fun unusualLengthsAreErrors() {
        val messages = SongValidator.validate(song("C4/1 D4/1 E4/1 F4/1 G4/1 A4/0.33")).messages(ERROR)
        assertEquals(listOf("test: phrase 1, event 6 (A4/0.33) has an unsupported length of 0.33 beats"), messages)
    }

    @Test
    fun sourceAndLicenseAreChecked() {
        assertEquals(
            listOf("test: has no source", "test: license \"All rights reserved\" is not public domain"),
            SongValidator.validate(song(good, source = " ", license = "All rights reserved")).messages(ERROR),
        )
        assertEquals(
            listOf("test: notes have not yet been checked against the source by a person"),
            SongValidator.validate(song(good, verified = false)).messages(WARNING),
        )
    }

    @Test
    fun catalogIdsMustBeUnique() {
        val issues = SongValidator.validateCatalog(listOf(song(good, id = "a"), song(good, id = "b"), song(good, id = "a")))
        assertEquals(listOf("a: id is used by more than one song"), issues.messages(ERROR))
    }
}

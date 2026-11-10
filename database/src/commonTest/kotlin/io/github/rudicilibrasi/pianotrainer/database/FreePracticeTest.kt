package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FreePracticeGeneratorTest {

    @Test
    fun sixWhiteKeyQuarterNotesFromG3ToG5() {
        repeat(50) { seed ->
            val phrase = FreePractice.newPhrase(Random(seed))
            assertEquals(6, phrase.noteCount)
            assertTrue(phrase.events.all { it.beats == 1.0 })
            assertTrue(phrase.notes.all { it.pitch!!.name.length == 1 }, "white keys only")
            assertTrue(phrase.notes.all { it.pitch!!.midi in Pitch.parse("G3").midi..Pitch.parse("G5").midi })
        }
        assertEquals(
            listOf("G3", "A3", "B3", "C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5", "D5", "E5", "F5", "G5"),
            FreePractice.WHITE_KEYS.map { it.label },
        )
    }

    @Test
    fun newNotesGivesADifferentPhrase() {
        val random = Random(1)
        val phrases = List(5) { FreePractice.newPhrase(random).notes.map { it.pitch } }
        assertTrue(phrases.toSet().size > 1)
    }
}

class FreePracticeTest {

    private val file = TestDatabaseFile()

    @AfterTest
    fun cleanUp() = file.delete()

    private val song = Song(
        id = "twinkle", title = "Twinkle", composer = null, tempoBpm = 100.0, timeSignature = TimeSignature(4, 4),
        key = "C major", source = "traditional", license = "Public domain", sourceVerified = false,
        phrases = List(3) { SongFormat.parseEvents("C4/1 C4/1 G4/2") },
    )

    private fun freePractice(score: Int) = NewAttempt(
        songId = null,
        phraseIndex = null,
        createdAt = 5_000,
        mode = AttemptMode.FreePractice,
        expectedNotes = FreePractice.newPhrase(Random(3)).notes.map { it.pitch!! },
        detectedNotes = emptyList(),
        referenceWavPath = null,
        userWavPath = "recordings/free.wav",
        scores = AttemptScores(score, score.toDouble(), 1.0, 1.0, 1.0, 1.0, 1.0),
        feedback = PhraseFeedback("You scored $score/100.", emptyList(), emptyList()),
        analysisVersion = 1,
    )

    @Test
    fun freePracticeIsSavedToHistoryAndSongProgressIsUnchanged() = runTest {
        val database = buildPianoDatabase(file.builder())
        val attempts = AttemptRepository(database.attemptDao())
        val progress = ProgressRepository(database.progressDao())
        // Some real progress first, so "unchanged" means more than "still empty".
        val saved = Progression.recordScore(SongProgress.initial(song), 0, 70).progress
        progress.save(song.id, saved, nowMillis = 1_000)
        val before = progress.get(song)
        val datesBefore = progress.dates(song.id)

        val id = RecordFreePractice(attempts)(freePractice(score = 100))

        assertEquals(before, progress.get(song))
        assertEquals(datesBefore, progress.dates(song.id))
        val stored = attempts.get(id)!!
        assertEquals(AttemptMode.FreePractice, stored.mode)
        assertEquals(listOf(id), attempts.observeAll().first().filter { it.mode == AttemptMode.FreePractice }.map { it.id })
        assertNotEquals(0L, id)
        database.close()
    }

    @Test
    fun lessonAttemptsAreRefused() = runTest {
        val database = buildPianoDatabase(file.builder())
        val lesson = freePractice(80).copy(songId = "twinkle", phraseIndex = 0, mode = AttemptMode.Lesson)
        assertFailsWith<IllegalArgumentException> { RecordFreePractice(AttemptRepository(database.attemptDao()))(lesson) }
        database.close()
    }
}

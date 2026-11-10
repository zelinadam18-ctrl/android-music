package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AttemptRepositoryTest {

    private val file = TestDatabaseFile()

    @AfterTest
    fun cleanUp() = file.delete()

    private fun attempt(songId: String? = "twinkle", phrase: Int? = 0, at: Long = 1_000, score: Int = 76, mode: AttemptMode = AttemptMode.Lesson) = NewAttempt(
        songId = songId,
        phraseIndex = phrase,
        createdAt = at,
        mode = mode,
        expectedNotes = listOf("C4", "C4", "G4").map(Pitch::parse),
        detectedNotes = listOf(
            DetectedNote(Pitch.parse("C4"), 300.0, 790.0, 0.93, uncertain = false, cents = -4.5),
            DetectedNote(null, 900.0, 1_200.0, 0.1, uncertain = true),
            DetectedNote(Pitch.parse("A4"), 1_310.0, 1_800.0, 0.55, uncertain = true, cents = 12.0),
        ),
        referenceWavPath = songId?.let { "reference/${it}_p${(phrase ?: 0) + 1}.wav" },
        userWavPath = "recordings/2026-11-09T10-00-00_$songId.wav",
        scores = AttemptScores(score, score - 0.3, 0.33, 1.0, 0.0, 0.0, null),
        feedback = PhraseFeedback(
            summary = "You scored $score/100. The next phrase is now unlocked.",
            wentWell = listOf("You played 1 out of 3 notes correctly."),
            toImprove = listOf("We couldn't hear note 2 clearly.", "You played A4 where G4 was expected (note 3)."),
        ),
        analysisVersion = 1,
    )

    @Test
    fun anAttemptRoundTripsExactly() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = AttemptRepository(database.attemptDao())
        val new = attempt()
        val id = repository.insert(new)
        val saved = repository.get(id)!!

        assertEquals(id, saved.id)
        assertEquals(new.songId, saved.songId)
        assertEquals(new.phraseIndex, saved.phraseIndex)
        assertEquals(new.createdAt, saved.createdAt)
        assertEquals(new.mode, saved.mode)
        assertEquals(new.expectedNotes, saved.expectedNotes)
        assertEquals(new.detectedNotes, saved.detectedNotes)
        assertEquals(new.referenceWavPath, saved.referenceWavPath)
        assertEquals(new.userWavPath, saved.userWavPath)
        assertEquals(new.scores, saved.scores)
        assertEquals(new.feedback, saved.feedback)
        assertEquals(new.analysisVersion, saved.analysisVersion)
        database.close()
    }

    @Test
    fun anUnknownIdIsNull() = runTest {
        val database = buildPianoDatabase(file.builder())
        assertNull(AttemptRepository(database.attemptDao()).get(42))
        database.close()
    }

    @Test
    fun everyAttemptIsKeptSeparately() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = AttemptRepository(database.attemptDao())
        val first = repository.insert(attempt(score = 40))
        val second = repository.insert(attempt(score = 40))
        assertNotEquals(first, second)
        assertEquals(2, repository.observeAll().first().size)
        database.close()
    }

    @Test
    fun listsAreNewestFirstAndFilterBySongAndPhrase() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = AttemptRepository(database.attemptDao())
        repository.insert(attempt("twinkle", 0, at = 1_000, score = 10))
        repository.insert(attempt("mary", 0, at = 2_000, score = 20))
        repository.insert(attempt("twinkle", 1, at = 3_000, score = 30))
        repository.insert(attempt(null, null, at = 4_000, score = 40, mode = AttemptMode.FreePractice))

        assertEquals(listOf(40, 30, 20, 10), repository.observeAll().first().map { it.scores.score })
        assertEquals(listOf(30, 10), repository.observeForSong("twinkle").first().map { it.scores.score })
        assertEquals(listOf(10), repository.observeForPhrase("twinkle", 0).first().map { it.scores.score })
        assertEquals(AttemptMode.FreePractice, repository.observeAll().first().first().mode)
        database.close()
    }

    @Test
    fun aSavedAttemptSurvivesClosingAndReopeningTheDatabase() = runTest {
        val first = buildPianoDatabase(file.builder())
        val id = AttemptRepository(first.attemptDao()).insert(attempt())
        first.close()

        val second = buildPianoDatabase(file.builder())
        assertEquals(attempt().detectedNotes, AttemptRepository(second.attemptDao()).get(id)!!.detectedNotes)
        second.close()
    }

    @Test
    fun wavPathsMustBeRelative() {
        assertFailsWith<IllegalArgumentException> { attempt().copy(userWavPath = "/var/mobile/Containers/x.wav") }
        assertFailsWith<IllegalArgumentException> { attempt().copy(referenceWavPath = "C:\\Users\\x.wav") }
    }

    @Test
    fun aLessonAttemptNeedsASongAndPhrase() {
        assertFailsWith<IllegalArgumentException> { attempt(songId = null, phrase = null, mode = AttemptMode.Lesson) }
        assertFailsWith<IllegalArgumentException> { attempt(songId = "twinkle", phrase = null) }
    }
}

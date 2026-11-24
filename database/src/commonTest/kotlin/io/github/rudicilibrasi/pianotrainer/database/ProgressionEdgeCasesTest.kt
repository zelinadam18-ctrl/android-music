package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The progression rules the spec singles out (§16, §17, #54), end to end through the real
 * database and the RecordAttempt use case. Scores are fed in directly here; the app itself only
 * ever records scores computed by PhraseScorer.
 */
class ProgressionEdgeCasesTest {

    private val file = TestDatabaseFile()
    private lateinit var database: PianoDatabase
    private var clock = 1_000L

    private val song = Song(
        id = "twinkle",
        title = "Twinkle",
        composer = null,
        tempoBpm = 100.0,
        timeSignature = TimeSignature(4, 4),
        key = "C major",
        source = "traditional",
        license = "Public domain",
        sourceVerified = false,
        // Two phrases of equal length, so the overall score is the plain average of the two.
        phrases = List(2) { SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1") },
    )

    @BeforeTest
    fun open() {
        database = buildPianoDatabase(file.builder())
    }

    @AfterTest
    fun close() {
        if (::database.isInitialized) database.close()
        file.delete()
    }

    private val progress get() = ProgressRepository(database.progressDao())
    private val attempts get() = AttemptRepository(database.attemptDao())
    private val record get() = RecordAttempt(database, attempts, progress)

    private suspend fun score(phrase: Int, score: Int): RecordAttemptResult = record(
        song,
        NewAttempt(
            songId = song.id,
            phraseIndex = phrase,
            createdAt = clock++,
            mode = AttemptMode.Lesson,
            expectedNotes = listOf("C4", "C4", "G4", "G4").map(Pitch::parse),
            detectedNotes = emptyList(),
            referenceWavPath = null,
            userWavPath = "recordings/take-$clock.wav",
            scores = AttemptScores(score, score.toDouble(), 1.0, 1.0, 1.0, 1.0, 1.0),
            feedback = PhraseFeedback("You scored $score/100.", emptyList(), emptyList()),
            analysisVersion = 1,
        ),
    )

    private suspend fun reopen() {
        database.close()
        database = buildPianoDatabase(file.builder())
    }

    @Test
    fun scoreOf49DoesNotUnlock() = runTest {
        assertIs<RecordAttemptResult.Recorded>(score(0, 49))
        assertFalse(progress.get(song).isUnlocked(1))
        assertIs<StartLessonResult.Locked>(StartLesson(progress)(song, 1))
    }

    @Test
    fun scoreOf50Unlocks() = runTest {
        val result = assertIs<RecordAttemptResult.Recorded>(score(0, 50))
        assertTrue(ProgressEvent.PhraseUnlocked(1) in result.update.events)
        assertIs<StartLessonResult.Ready>(StartLesson(progress)(song, 1))
    }

    @Test
    fun rawScoreOf49Point5RoundsTo50AndUnlocks() = runTest {
        assertEquals(50, PhraseScorer.roundScore(49.5))
        assertEquals(49, PhraseScorer.roundScore(49.4))
        score(0, PhraseScorer.roundScore(49.5))
        assertTrue(progress.get(song).isUnlocked(1))
    }

    @Test
    fun overall89EarnsNoStarAnd90Does() = runTest {
        score(0, 89)
        score(1, 89)
        assertTrue(progress.get(song).completed)
        assertEquals(89, progress.get(song).overallScore)
        assertFalse(progress.get(song).starEarned)

        // Raising one phrase to 91 makes the overall (89 + 91) / 2 = 90.
        val result = assertIs<RecordAttemptResult.Recorded>(score(1, 91))
        assertEquals(90, progress.get(song).overallScore)
        assertTrue(progress.get(song).starEarned)
        assertTrue(ProgressEvent.StarEarned in result.update.events)
    }

    @Test
    fun lowerReplaysKeepTheStar() = runTest {
        score(0, 95)
        score(1, 95)
        assertTrue(progress.get(song).starEarned)
        score(0, 10)
        score(1, 20)
        assertTrue(progress.get(song).starEarned)
        assertEquals(95, progress.get(song).overallScore, "overall uses best scores")
    }

    @Test
    fun retakingWithALowerScoreKeepsTheUnlockAndTheBest() = runTest {
        score(0, 80)
        score(0, 30)
        val phrase = progress.get(song).phrases[0]
        assertEquals(80, phrase.bestScore)
        assertEquals(30, phrase.latestScore)
        assertEquals(2, phrase.attempts)
        assertTrue(progress.get(song).isUnlocked(1))
    }

    @Test
    fun progressUnlocksAndTheStarSurviveReopeningTheDatabase() = runTest {
        score(0, 92)
        score(1, 96)
        val before = progress.get(song)
        val datesBefore = progress.dates(song.id)
        assertTrue(before.starEarned)

        reopen()

        assertEquals(before, progress.get(song))
        assertEquals(datesBefore, progress.dates(song.id))
        assertTrue(progress.get(song).isUnlocked(1))
        assertTrue(progress.get(song).starEarned)
        assertEquals(2, attempts.observeAll().first().size)
    }

    @Test
    fun openingHistoryDoesNotChangeProgress() = runTest {
        score(0, 70)
        score(0, 40)
        val before = progress.get(song)
        val history: AttemptHistory = attempts
        // Everything the history screens read.
        val all = history.observeAll().first()
        all.forEach { history.get(it.id) }
        history.observeForSong(song.id).first()
        history.observeForPhrase(song.id, 0).first()
        assertEquals(before, progress.get(song))
        assertEquals(2, history.observeAll().first().size)
    }
}

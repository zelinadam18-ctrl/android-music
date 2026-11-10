package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LessonsTest {

    private val file = TestDatabaseFile()
    private lateinit var database: PianoDatabase
    private lateinit var attempts: AttemptRepository
    private lateinit var progress: ProgressRepository
    private lateinit var startLesson: StartLesson
    private lateinit var recordAttempt: RecordAttempt

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
        phrases = List(3) { SongFormat.parseEvents("C4/1 C4/1 G4/2") },
    )

    @BeforeTest
    fun open() {
        database = buildPianoDatabase(file.builder())
        attempts = AttemptRepository(database.attemptDao())
        progress = ProgressRepository(database.progressDao())
        startLesson = StartLesson(progress)
        recordAttempt = RecordAttempt(database, attempts, progress)
    }

    @AfterTest
    fun close() {
        if (::database.isInitialized) database.close()
        file.delete()
    }

    private fun attempt(phrase: Int, score: Int, at: Long = 1_000) = NewAttempt(
        songId = song.id,
        phraseIndex = phrase,
        createdAt = at,
        mode = AttemptMode.Lesson,
        expectedNotes = listOf("C4", "C4", "G4").map(Pitch::parse),
        detectedNotes = emptyList(),
        referenceWavPath = null,
        userWavPath = "recordings/take.wav",
        scores = AttemptScores(score, score.toDouble(), 1.0, 1.0, 1.0, 1.0, 1.0),
        feedback = PhraseFeedback("You scored $score/100.", emptyList(), emptyList()),
        analysisVersion = 1,
    )

    @Test
    fun onlyTheFirstPhraseCanBeStartedAtFirst() = runTest {
        assertIs<StartLessonResult.Ready>(startLesson(song, 0))
        val locked = assertIs<StartLessonResult.Locked>(startLesson(song, 1))
        assertEquals("Phrase 2 is locked. Score 50 or more on phrase 1 to unlock it.", locked.message)
        assertIs<StartLessonResult.Locked>(startLesson(song, 2))
        assertIs<StartLessonResult.NoSuchPhrase>(startLesson(song, 3))
        assertIs<StartLessonResult.NoSuchPhrase>(startLesson(song, -1))
    }

    @Test
    fun aLockedPhraseCannotBeScored() = runTest {
        val result = recordAttempt(song, attempt(phrase = 1, score = 100))
        assertIs<RecordAttemptResult.Locked>(result)
        assertEquals(emptyList(), attempts.observeAll().first())
        assertEquals(SongProgress.initial(song), progress.get(song))
    }

    @Test
    fun recordingSavesTheAttemptAndProgressTogether() = runTest {
        val result = assertIs<RecordAttemptResult.Recorded>(recordAttempt(song, attempt(phrase = 0, score = 76)))
        assertEquals(listOf(ProgressEvent.PhraseCompleted(0), ProgressEvent.PhraseUnlocked(1)), result.update.events)
        assertEquals(76, attempts.get(result.attemptId)!!.scores.score)
        assertEquals(76, progress.get(song).phrases[0].bestScore)
        assertTrue(progress.get(song).isUnlocked(1))
        assertIs<StartLessonResult.Ready>(startLesson(song, 1))
    }

    @Test
    fun aLowScoreIsSavedButUnlocksNothing() = runTest {
        assertIs<RecordAttemptResult.Recorded>(recordAttempt(song, attempt(phrase = 0, score = 49)))
        assertEquals(1, attempts.observeAll().first().size)
        assertIs<StartLessonResult.Locked>(startLesson(song, 1))
    }

    @Test
    fun aFailedSaveLeavesNothingBehind() = runTest {
        // An impossible score fails inside the transaction, after the attempt row was inserted:
        // the whole transaction rolls back.
        assertFailsWith<IllegalArgumentException> { recordAttempt(song, attempt(phrase = 0, score = 150)) }
        assertEquals(emptyList(), attempts.observeAll().first())
        assertEquals(SongProgress.initial(song), progress.get(song))
    }

    @Test
    fun aCancelledRecordingChangesNothing() = runTest {
        val job = launch { recordAttempt(song, attempt(phrase = 0, score = 80)) }
        job.cancel()
        job.join()
        assertEquals(emptyList(), attempts.observeAll().first())
        assertEquals(SongProgress.initial(song), progress.get(song))
    }

    @Test
    fun freePracticeNeverChangesProgress() = runTest {
        val freePractice = attempt(phrase = 0, score = 80).copy(songId = null, phraseIndex = null, mode = AttemptMode.FreePractice)
        assertFailsWith<IllegalArgumentException> { recordAttempt(song, freePractice) }
        assertEquals(SongProgress.initial(song), progress.get(song))
    }

    @Test
    fun anAttemptForAnotherSongIsRejected() = runTest {
        assertFailsWith<IllegalArgumentException> { recordAttempt(song, attempt(phrase = 0, score = 80).copy(songId = "mary")) }
    }
}

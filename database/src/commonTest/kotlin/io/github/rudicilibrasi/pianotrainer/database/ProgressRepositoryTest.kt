package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressRepositoryTest {

    private val file = TestDatabaseFile()

    @AfterTest
    fun cleanUp() = file.delete()

    private fun song(id: String = "twinkle", phrases: Int = 3) = Song(
        id = id,
        title = id,
        composer = null,
        tempoBpm = 100.0,
        timeSignature = TimeSignature(4, 4),
        key = "C major",
        source = "traditional",
        license = "Public domain",
        sourceVerified = false,
        phrases = List(phrases) { SongFormat.parseEvents("C4/1 D4/1 E4/2") },
    )

    private fun SongProgress.score(phrase: Int, score: Int) = Progression.recordScore(this, phrase, score).progress

    @Test
    fun aSongWithNothingSavedIsInitial() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val song = song()
        assertEquals(SongProgress.initial(song), repository.get(song))
        assertEquals(SongProgress.initial(song), repository.observe(song).first())
        assertNull(repository.dates(song.id))
        database.close()
    }

    @Test
    fun savedProgressIsReadBackAndObserved() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val song = song()
        val progress = SongProgress.initial(song).score(0, 72).score(0, 40).score(1, 55)

        repository.save(song.id, progress, nowMillis = 1_000)

        assertEquals(progress, repository.get(song))
        assertEquals(progress, repository.observe(song).first())
        val phrase0 = repository.get(song).phrases[0]
        assertEquals(72, phrase0.bestScore)
        assertEquals(40, phrase0.latestScore)
        assertEquals(2, phrase0.attempts)
        assertTrue(repository.get(song).isUnlocked(2))
        database.close()
    }

    @Test
    fun progressSurvivesClosingAndReopeningTheDatabase() = runTest {
        val song = song()
        val progress = SongProgress.initial(song).score(0, 90).score(1, 95).score(2, 92)

        val first = buildPianoDatabase(file.builder())
        ProgressRepository(first.progressDao()).save(song.id, progress, nowMillis = 5_000)
        first.close()

        val second = buildPianoDatabase(file.builder())
        val reloaded = ProgressRepository(second.progressDao()).get(song)
        assertEquals(progress, reloaded)
        assertTrue(reloaded.completed)
        assertTrue(reloaded.starEarned)
        second.close()
    }

    @Test
    fun theSummaryRowMatchesThePhrases() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val song = song()
        val progress = SongProgress.initial(song).score(0, 80).score(1, 60)
        repository.save(song.id, progress, nowMillis = 1_000)

        val row = database.progressDao().getSong(song.id)!!
        assertEquals(progress.currentPhraseIndex, row.currentPhraseIndex)
        assertEquals(progress.highestUnlockedIndex, row.highestUnlockedIndex)
        assertEquals(2, row.completedPhraseCount)
        assertEquals(2, row.attemptCount)
        assertEquals(progress.overallScore, row.overallScore)
        assertFalse(row.completed)
        database.close()
    }

    @Test
    fun startAndStarDatesAreSetOnceAndKept() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val song = song(phrases = 1)

        var progress = SongProgress.initial(song).score(0, 40)
        repository.save(song.id, progress, nowMillis = 1_000)
        assertEquals(ProgressDates(startedAt = 1_000, starEarnedAt = null, lastAttemptAt = 1_000), repository.dates(song.id))

        progress = progress.score(0, 95)
        repository.save(song.id, progress, nowMillis = 2_000)
        assertEquals(ProgressDates(startedAt = 1_000, starEarnedAt = 2_000, lastAttemptAt = 2_000), repository.dates(song.id))

        progress = progress.score(0, 10)
        repository.save(song.id, progress, nowMillis = 3_000)
        assertEquals(ProgressDates(startedAt = 1_000, starEarnedAt = 2_000, lastAttemptAt = 3_000), repository.dates(song.id))
        assertTrue(repository.get(song).starEarned)
        database.close()
    }

    @Test
    fun songsAreKeptSeparate() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val a = song("a")
        val b = song("b")
        repository.save(a.id, SongProgress.initial(a).score(0, 70), nowMillis = 1_000)

        val all = repository.observeAll(listOf(a, b)).first()
        assertEquals(70, all.getValue("a").phrases[0].bestScore)
        assertEquals(SongProgress.initial(b), all.getValue("b"))
        database.close()
    }

    @Test
    fun aSongFileWithMorePhrasesKeepsSavedOnesAndLocksNewOnes() = runTest {
        val database = buildPianoDatabase(file.builder())
        val repository = ProgressRepository(database.progressDao())
        val original = song(phrases = 2)
        repository.save(original.id, SongProgress.initial(original).score(0, 80), nowMillis = 1_000)

        val longer = song(phrases = 4)
        val progress = repository.get(longer)
        assertEquals(4, progress.phraseCount)
        assertEquals(80, progress.phrases[0].bestScore)
        assertEquals(listOf(true, true, false, false), progress.phrases.map { it.unlocked })

        val shorter = song(phrases = 1)
        assertEquals(1, repository.get(shorter).phraseCount)
        database.close()
    }
}

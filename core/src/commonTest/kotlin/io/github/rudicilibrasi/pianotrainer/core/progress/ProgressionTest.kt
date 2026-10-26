package io.github.rudicilibrasi.pianotrainer.core.progress

import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.PhraseCompleted
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.PhraseUnlocked
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.SongCompleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressionTest {

    private fun SongProgress.score(phraseIndex: Int, score: Int) = Progression.recordScore(this, phraseIndex, score)

    @Test
    fun aNewSongHasOnlyItsFirstPhraseUnlocked() {
        val progress = SongProgress.initial(4)
        assertEquals(listOf(true, false, false, false), progress.phrases.map { it.unlocked })
        assertFalse(progress.started)
        assertEquals(0, progress.currentPhraseIndex)
        assertEquals(0, progress.highestUnlockedIndex)
    }

    @Test
    fun scoreOfZeroIsRecordedButUnlocksNothing() {
        val update = SongProgress.initial(3).score(0, 0)
        assertEquals(PhraseProgress(unlocked = true, bestScore = 0, latestScore = 0, attempts = 1), update.progress.phrases[0])
        assertFalse(update.progress.isUnlocked(1))
        assertEquals(emptyList(), update.events)
        assertTrue(update.progress.started)
    }

    @Test
    fun scoreOf49DoesNotUnlockTheNextPhrase() {
        val update = SongProgress.initial(3).score(0, 49)
        assertFalse(update.progress.isUnlocked(1))
        assertFalse(update.progress.phrases[0].completed)
        assertEquals(emptyList(), update.events)
    }

    @Test
    fun scoreOfExactly50UnlocksTheNextPhrase() {
        val update = SongProgress.initial(3).score(0, 50)
        assertTrue(update.progress.isUnlocked(1))
        assertTrue(update.progress.phrases[0].completed)
        assertEquals(listOf(PhraseCompleted(0), PhraseUnlocked(1)), update.events)
        assertEquals(1, update.progress.currentPhraseIndex)
    }

    @Test
    fun scoreOf100UnlocksTheNextPhrase() {
        val update = SongProgress.initial(3).score(0, 100)
        assertTrue(update.progress.isUnlocked(1))
        assertFalse(update.progress.isUnlocked(2))
    }

    @Test
    fun aLowerRetakeKeepsTheUnlockAndTheBestScore() {
        val afterPass = SongProgress.initial(3).score(0, 80).progress
        val update = afterPass.score(0, 20)
        val phrase = update.progress.phrases[0]
        assertEquals(80, phrase.bestScore)
        assertEquals(20, phrase.latestScore)
        assertEquals(2, phrase.attempts)
        assertTrue(phrase.completed)
        assertTrue(update.progress.isUnlocked(1))
        assertEquals(emptyList(), update.events)
    }

    @Test
    fun retryingAfterAFailCanStillUnlock() {
        val update = SongProgress.initial(2).score(0, 30).progress.score(0, 65)
        assertEquals(65, update.progress.phrases[0].bestScore)
        assertEquals(listOf(PhraseCompleted(0), PhraseUnlocked(1)), update.events)
    }

    @Test
    fun replayingAnEarlierPhraseDoesNotAffectLaterOnes() {
        var progress = SongProgress.initial(3)
        progress = progress.score(0, 70).progress
        progress = progress.score(1, 40).progress
        val update = progress.score(0, 95)
        assertEquals(95, update.progress.phrases[0].bestScore)
        assertEquals(PhraseProgress(unlocked = true, bestScore = 40, latestScore = 40, attempts = 1), update.progress.phrases[1])
        assertFalse(update.progress.isUnlocked(2))
        assertEquals(emptyList(), update.events)
        assertEquals(1, update.progress.currentPhraseIndex)
    }

    @Test
    fun lockedPhrasesCannotBeScored() {
        val progress = SongProgress.initial(3)
        val error = assertFailsWith<PhraseLockedException> { progress.score(1, 100) }
        assertEquals(1, error.phraseIndex)
        assertFailsWith<PhraseLockedException> { progress.score(0, 49).progress.score(1, 100) }
    }

    @Test
    fun completingTheLastPhraseCompletesTheSong() {
        var progress = SongProgress.initial(3)
        progress = progress.score(0, 60).progress
        progress = progress.score(1, 75).progress
        assertFalse(progress.completed)
        val update = progress.score(2, 50)
        assertTrue(update.progress.completed)
        assertEquals(3, update.progress.completedPhraseCount)
        assertEquals(listOf(PhraseCompleted(2), SongCompleted), update.events)
        assertEquals(2, update.progress.currentPhraseIndex)
        assertEquals(3, update.progress.attempts)

        // Replaying a completed song doesn't complete it again.
        assertEquals(emptyList(), update.progress.score(1, 99).events)
    }

    @Test
    fun aOnePhraseSongCompletesOnItsFirstPass() {
        val update = SongProgress.initial(1).score(0, 50)
        assertEquals(listOf(PhraseCompleted(0), SongCompleted), update.events)
    }

    @Test
    fun rejectsInvalidInput() {
        val progress = SongProgress.initial(2)
        assertFailsWith<IllegalArgumentException> { progress.score(0, -1) }
        assertFailsWith<IllegalArgumentException> { progress.score(0, 101) }
        assertFailsWith<IllegalArgumentException> { progress.score(2, 50) }
        assertFailsWith<IllegalArgumentException> { SongProgress.initial(0) }
        assertFailsWith<IllegalArgumentException> { SongProgress(listOf(PhraseProgress(unlocked = false))) }
    }
}

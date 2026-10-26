package io.github.rudicilibrasi.pianotrainer.core.progress

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent.Companion.note
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.PhraseCompleted
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.SongCompleted
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressEvent.StarEarned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongScoreTest {

    /** Plays each phrase once with the given scores, in order. */
    private fun play(noteCounts: List<Int>, vararg scores: Int): ProgressUpdate {
        var progress = SongProgress.initial(noteCounts)
        var update = ProgressUpdate(progress, emptyList())
        scores.forEachIndexed { phrase, score ->
            update = Progression.recordScore(progress, phrase, score)
            progress = update.progress
        }
        return update
    }

    @Test
    fun overallScoreIsNullBeforeAnyAttempt() {
        assertNull(SongProgress.initial(listOf(6, 6)).overallScore)
    }

    @Test
    fun overallScoreWeightsPhrasesByTheirNotes() {
        // (100·4 + 70·8) / 12 = 80
        assertEquals(80, play(listOf(4, 8), 100, 70).progress.overallScore)
        // A plain average of phrase scores would give 85; the longer phrase counts for more.
    }

    @Test
    fun overallScoreCoversAttemptedPhrasesBeforeTheSongIsComplete() {
        val progress = play(listOf(6, 6, 6), 90, 70).progress
        assertFalse(progress.completed)
        assertEquals(80, progress.overallScore)
    }

    @Test
    fun overallOf89DoesNotEarnAStar() {
        val update = play(listOf(6, 6), 88, 90) // 89
        assertEquals(89, update.progress.overallScore)
        assertTrue(update.progress.completed)
        assertFalse(update.progress.starEarned)
        assertEquals(listOf(PhraseCompleted(1), SongCompleted), update.events)
    }

    @Test
    fun overallOfExactly90EarnsAStar() {
        val update = play(listOf(6, 6), 85, 95) // 90
        assertEquals(90, update.progress.overallScore)
        assertTrue(update.progress.starEarned)
        assertEquals(listOf(PhraseCompleted(1), SongCompleted, StarEarned), update.events)
    }

    @Test
    fun overallOf89Point5RoundsUpTo90AndEarnsAStar() {
        val update = play(listOf(1, 1), 89, 90) // 89.5
        assertEquals(90, update.progress.overallScore)
        assertTrue(update.progress.starEarned)
    }

    @Test
    fun overallJustBelow89Point5RoundsDown() {
        // (89·1000 + 90·999) / 1999 = 89.4997…
        val update = play(listOf(1000, 999), 89, 90)
        assertEquals(89, update.progress.overallScore)
        assertFalse(update.progress.starEarned)
    }

    @Test
    fun aHighPhraseScoreAloneNeverEarnsAStar() {
        val update = play(listOf(6, 6, 6), 100, 100)
        assertFalse(update.progress.completed)
        assertEquals(100, update.progress.overallScore)
        assertFalse(update.progress.starEarned)
    }

    @Test
    fun theStarIsKeptAfterLowerReplays() {
        val starred = play(listOf(6, 6), 95, 95).progress
        assertTrue(starred.starEarned)
        val replay = Progression.recordScore(starred, 0, 10)
        assertTrue(replay.progress.starEarned)
        assertEquals(95, replay.progress.overallScore) // best scores are unchanged
        assertEquals(emptyList(), replay.events)
    }

    @Test
    fun theStarCanBeEarnedLaterByImprovingAPhrase() {
        var progress = play(listOf(6, 6), 70, 95).progress // 83: complete, no star
        assertTrue(progress.completed)
        assertFalse(progress.starEarned)

        var update = Progression.recordScore(progress, 0, 80) // 88: still no star
        assertFalse(update.progress.starEarned)
        progress = update.progress

        update = Progression.recordScore(progress, 0, 86) // 91
        assertTrue(update.progress.starEarned)
        assertEquals(listOf(StarEarned), update.events)

        // Earned only once.
        assertEquals(emptyList(), Progression.recordScore(update.progress, 0, 100).events)
    }

    @Test
    fun initialProgressCanComeFromASong() {
        val song = Song(
            id = "s", title = "S", composer = null, tempoBpm = 100.0, timeSignature = TimeSignature(4, 4),
            key = "C major", source = "traditional", license = "Public domain", sourceVerified = false,
            phrases = listOf(
                listOf(note("C4", 1.0), note("D4", 1.0), note("E4", 2.0)),
                listOf(note("E4", 1.0), note("D4", 1.0), note("C4", 1.0), note("D4", 1.0), note("C4", 4.0)),
            ),
        )
        assertEquals(listOf(3, 5), SongProgress.initial(song).phrases.map { it.noteCount })
    }
}

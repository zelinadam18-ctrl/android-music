package io.github.rudicilibrasi.pianotrainer.core.progress

import io.github.rudicilibrasi.pianotrainer.core.music.Song

/**
 * A student's progress on one phrase.
 *
 * @property noteCount number of notes in the phrase; weights the phrase in the overall song score.
 * @property bestScore highest score so far, or null before the first attempt.
 * @property latestScore score of the most recent attempt, or null before the first attempt.
 */
data class PhraseProgress(
    val noteCount: Int,
    val unlocked: Boolean,
    val bestScore: Int? = null,
    val latestScore: Int? = null,
    val attempts: Int = 0,
) {
    init {
        require(noteCount > 0) { "A phrase has at least one note, but got $noteCount" }
    }

    /** A phrase is completed once its best score reaches [Progression.UNLOCK_SCORE]. */
    val completed: Boolean get() = bestScore != null && bestScore >= Progression.UNLOCK_SCORE
}

/**
 * A student's progress on one song: one [PhraseProgress] per phrase, in order.
 *
 * @property starEarned true once the song has been completed with an overall score of at least
 *   [Progression.STAR_SCORE]. It is never taken away.
 */
data class SongProgress(val phrases: List<PhraseProgress>, val starEarned: Boolean = false) {

    init {
        require(phrases.isNotEmpty()) { "A song has at least one phrase" }
        require(phrases[0].unlocked) { "The first phrase is always unlocked" }
    }

    val phraseCount: Int get() = phrases.size

    val started: Boolean get() = phrases.any { it.attempts > 0 }

    val attempts: Int get() = phrases.sumOf { it.attempts }

    val completedPhraseCount: Int get() = phrases.count { it.completed }

    /** The song is completed when every phrase is completed. */
    val completed: Boolean get() = phrases.all { it.completed }

    /** 0-based index of the furthest unlocked phrase. */
    val highestUnlockedIndex: Int get() = phrases.indexOfLast { it.unlocked }

    /** The phrase to continue with: the first unlocked phrase not yet completed, or the last phrase. */
    val currentPhraseIndex: Int
        get() = phrases.indexOfFirst { it.unlocked && !it.completed }.takeIf { it >= 0 } ?: (phraseCount - 1)

    /**
     * Overall song score: the average of each phrase's best score, weighted by its number of notes,
     * rounded half up to a whole number:
     *
     *     overall = Σ(best phrase score × notes in phrase) ÷ Σ(notes in phrase)
     *
     * Because each phrase score already measures note accuracy, order, timing and tempo, this
     * accounts for all four across the song. Before the song is completed it covers only the
     * phrases attempted so far; it is null if no phrase has been attempted. Since it uses best
     * scores, it never goes down.
     */
    val overallScore: Int?
        get() {
            val attempted = phrases.filter { it.bestScore != null }
            if (attempted.isEmpty()) return null
            val weightedSum = attempted.sumOf { it.bestScore!! * it.noteCount }
            val notes = attempted.sumOf { it.noteCount }
            // Exact half-up rounding in integers: floor(weightedSum / notes + 1/2).
            return (2 * weightedSum + notes) / (2 * notes)
        }

    fun isUnlocked(phraseIndex: Int): Boolean = phrases.getOrNull(phraseIndex)?.unlocked == true

    companion object {
        /** A song nobody has practised yet, with phrases of [noteCounts] notes: only phrase 1 is unlocked. */
        fun initial(noteCounts: List<Int>): SongProgress {
            require(noteCounts.isNotEmpty()) { "A song has at least one phrase" }
            return SongProgress(noteCounts.mapIndexed { index, notes -> PhraseProgress(notes, unlocked = index == 0) })
        }

        fun initial(song: Song): SongProgress = initial(song.phrases.map { it.noteCount })
    }
}

/** Something worth telling the student about after an attempt. */
sealed interface ProgressEvent {
    /** The phrase was completed (best score reached the unlock score) for the first time. */
    data class PhraseCompleted(val phraseIndex: Int) : ProgressEvent

    data class PhraseUnlocked(val phraseIndex: Int) : ProgressEvent

    /** Every phrase of the song is now completed, for the first time. */
    data object SongCompleted : ProgressEvent

    /** The song's mastery star was earned. Happens at most once per song. */
    data object StarEarned : ProgressEvent
}

data class ProgressUpdate(val progress: SongProgress, val events: List<ProgressEvent>)

/** Thrown when an attempt is recorded for a phrase the student hasn't unlocked. */
class PhraseLockedException(val phraseIndex: Int) :
    IllegalStateException("Phrase ${phraseIndex + 1} is locked; score at least ${Progression.UNLOCK_SCORE} on the phrase before it first")

/**
 * The unlocking and star rules from the spec, sections 5 and 6, with no database or UI:
 *
 * - A score of at least [UNLOCK_SCORE] on a phrase unlocks the next phrase.
 * - Unlocked phrases never relock; a later, lower score changes only the latest score.
 * - The best score, latest score and attempt count are kept for each phrase.
 * - A phrase is completed once its best score reaches [UNLOCK_SCORE]; the song is completed when
 *   every phrase is.
 * - The star is earned when the song is completed and its [SongProgress.overallScore] is at least
 *   [STAR_SCORE]. A high score on a single phrase never earns it, and once earned it is kept.
 * - Attempts can only be recorded for unlocked phrases.
 */
object Progression {
    const val UNLOCK_SCORE = 50
    const val STAR_SCORE = 90

    /**
     * Applies one attempt's [score] (a whole number from 0 to 100) on [phraseIndex] to [progress].
     *
     * @throws PhraseLockedException if the phrase is locked.
     */
    fun recordScore(progress: SongProgress, phraseIndex: Int, score: Int): ProgressUpdate {
        require(phraseIndex in progress.phrases.indices) {
            "Phrase index $phraseIndex is outside 0..${progress.phraseCount - 1}"
        }
        require(score in 0..100) { "Score must be between 0 and 100, but was $score" }
        val before = progress.phrases[phraseIndex]
        if (!before.unlocked) throw PhraseLockedException(phraseIndex)

        val after = before.copy(
            bestScore = maxOf(before.bestScore ?: score, score),
            latestScore = score,
            attempts = before.attempts + 1,
        )
        val phrases = progress.phrases.toMutableList()
        phrases[phraseIndex] = after
        val events = mutableListOf<ProgressEvent>()

        if (after.completed && !before.completed) events += ProgressEvent.PhraseCompleted(phraseIndex)

        val next = phraseIndex + 1
        if (score >= UNLOCK_SCORE && next < phrases.size && !phrases[next].unlocked) {
            phrases[next] = phrases[next].copy(unlocked = true)
            events += ProgressEvent.PhraseUnlocked(next)
        }

        var updated = SongProgress(phrases, progress.starEarned)
        if (updated.completed && !progress.completed) events += ProgressEvent.SongCompleted
        if (!updated.starEarned && updated.completed && (updated.overallScore ?: 0) >= STAR_SCORE) {
            updated = updated.copy(starEarned = true)
            events += ProgressEvent.StarEarned
        }
        return ProgressUpdate(updated, events)
    }
}

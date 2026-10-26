package io.github.rudicilibrasi.pianotrainer.core.progress

/**
 * A student's progress on one phrase.
 *
 * @property bestScore highest score so far, or null before the first attempt.
 * @property latestScore score of the most recent attempt, or null before the first attempt.
 */
data class PhraseProgress(
    val unlocked: Boolean,
    val bestScore: Int? = null,
    val latestScore: Int? = null,
    val attempts: Int = 0,
) {
    /** A phrase is completed once its best score reaches [Progression.UNLOCK_SCORE]. */
    val completed: Boolean get() = bestScore != null && bestScore >= Progression.UNLOCK_SCORE
}

/** A student's progress on one song: one [PhraseProgress] per phrase, in order. */
data class SongProgress(val phrases: List<PhraseProgress>) {

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

    fun isUnlocked(phraseIndex: Int): Boolean = phrases.getOrNull(phraseIndex)?.unlocked == true

    companion object {
        /** A song nobody has practised yet: only the first phrase is unlocked. */
        fun initial(phraseCount: Int): SongProgress {
            require(phraseCount > 0) { "A song has at least one phrase, but got $phraseCount" }
            return SongProgress(List(phraseCount) { PhraseProgress(unlocked = it == 0) })
        }
    }
}

/** Something worth telling the student about after an attempt. */
sealed interface ProgressEvent {
    /** The phrase was completed (best score reached the unlock score) for the first time. */
    data class PhraseCompleted(val phraseIndex: Int) : ProgressEvent

    data class PhraseUnlocked(val phraseIndex: Int) : ProgressEvent

    /** Every phrase of the song is now completed, for the first time. */
    data object SongCompleted : ProgressEvent
}

data class ProgressUpdate(val progress: SongProgress, val events: List<ProgressEvent>)

/** Thrown when an attempt is recorded for a phrase the student hasn't unlocked. */
class PhraseLockedException(val phraseIndex: Int) :
    IllegalStateException("Phrase ${phraseIndex + 1} is locked; score at least ${Progression.UNLOCK_SCORE} on the phrase before it first")

/**
 * The unlocking rules from the spec, section 5, with no database or UI:
 *
 * - A score of at least [UNLOCK_SCORE] on a phrase unlocks the next phrase.
 * - Unlocked phrases never relock; a later, lower score changes only the latest score.
 * - The best score, latest score and attempt count are kept for each phrase.
 * - A phrase is completed once its best score reaches [UNLOCK_SCORE]; the song is completed when
 *   every phrase is.
 * - Attempts can only be recorded for unlocked phrases.
 */
object Progression {
    const val UNLOCK_SCORE = 50

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

        val updated = SongProgress(phrases)
        if (updated.completed && !progress.completed) events += ProgressEvent.SongCompleted
        return ProgressUpdate(updated, events)
    }
}

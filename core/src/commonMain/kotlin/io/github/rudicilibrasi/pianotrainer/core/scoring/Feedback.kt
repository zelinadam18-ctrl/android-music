package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Deletion
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Insertion
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Match
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep.Substitution
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Feedback on one attempt, written from what the student actually played (spec §12).
 *
 * @property summary the score and what it unlocks, e.g. "You scored 76/100. The next phrase is
 *   now unlocked."
 * @property wentWell things the student did well.
 * @property toImprove specific things to work on, in playing order.
 */
data class PhraseFeedback(val summary: String, val wentWell: List<String>, val toImprove: List<String>)

/** Where this phrase sits in the song, for the unlock message. */
data class PhraseContext(val isLastPhrase: Boolean, val nextPhraseAlreadyUnlocked: Boolean)

/**
 * Generates [PhraseFeedback] from a [PhraseScore]. Every message is derived from the alignment and
 * timing measures behind the score, so none can contradict it:
 *
 * - Notes: how many were correct; each missed, wrong and extra note (more than
 *   [MAX_EXTRA_NOTE_MESSAGES] extra notes are summed up in one message); adjacent notes played the
 *   other way round are reported once as a swap. Notes that couldn't be identified reliably are
 *   reported as not heard clearly, never as wrong.
 * - Order: praised only when order accuracy is perfect.
 * - Tempo: the tempo ratio, rounded to the nearest 5%, when outside the ±15% tolerance.
 * - Rhythm: steady when timing accuracy is at least [STEADY_RHYTHM], otherwise a suggestion.
 *   Not mentioned with fewer than 2 correct notes, since there is nothing to measure.
 */
object FeedbackGenerator {
    const val STEADY_RHYTHM = 0.8

    /** Up to this many extra notes get a message each; more are summed up in one message. */
    const val MAX_EXTRA_NOTE_MESSAGES = 2

    fun generate(
        phrase: Phrase,
        detected: List<DetectedNote>,
        score: PhraseScore,
        context: PhraseContext,
    ): PhraseFeedback = generate(phrase.notes.map { it.pitch!! }, detected, score, context)

    fun generate(
        expected: List<Pitch>,
        detected: List<DetectedNote>,
        score: PhraseScore,
        context: PhraseContext,
    ): PhraseFeedback {
        val summary = summary(score.score, context)
        if (detected.isEmpty()) {
            return PhraseFeedback(summary, emptyList(), listOf("We didn't hear any notes. Is the phone close to the piano?"))
        }

        val alignment = score.alignment
        val wentWell = ArrayList<String>()
        val toImprove = ArrayList<String>()

        val correct = alignment.correctCount
        val total = expected.size
        val countMessage = "You played $correct out of $total ${if (total == 1) "note" else "notes"} correctly."
        if (correct > 0) wentWell += countMessage else toImprove += countMessage

        toImprove += noteMessages(expected, detected, alignment.steps)

        if (correct > 0 && score.orderAccuracy == 1.0) wentWell += "Your note order was correct."

        val timing = score.timing
        val ratio = timing.tempoRatio
        if (ratio != null) {
            if (timing.tempoFlagged) {
                val percent = (abs(ratio - 1) * 100 / 5).roundToInt() * 5
                val direction = if (ratio > 1) "slower" else "faster"
                toImprove += "You played about $percent% $direction than the reference."
            } else {
                wentWell += "Your tempo was close to the reference."
            }
            if (timing.timingAccuracy >= STEADY_RHYTHM) {
                wentWell += "Your rhythm was steady."
            } else {
                toImprove += "Try keeping a more consistent rhythm."
            }
        }

        return PhraseFeedback(summary, wentWell, toImprove)
    }

    private fun summary(score: Int, context: PhraseContext): String {
        val scored = "You scored $score/100."
        return when {
            score >= Progression.UNLOCK_SCORE && context.isLastPhrase -> "$scored You completed the last phrase of this song."
            score >= Progression.UNLOCK_SCORE && context.nextPhraseAlreadyUnlocked -> "$scored The next phrase is unlocked."
            score >= Progression.UNLOCK_SCORE -> "$scored The next phrase is now unlocked."
            context.isLastPhrase -> "$scored You need at least ${Progression.UNLOCK_SCORE} to complete this phrase."
            context.nextPhraseAlreadyUnlocked -> scored
            else -> "$scored You need at least ${Progression.UNLOCK_SCORE} to unlock the next phrase."
        }
    }

    private fun noteMessages(expected: List<Pitch>, detected: List<DetectedNote>, steps: List<AlignmentStep>): List<String> {
        fun playedPitch(j: Int): Pitch? = detected[j].takeIf { it.isReliable }?.pitch
        val messages = ArrayList<String>()
        // Many extra notes (often background noise) would bury the useful messages under one bullet
        // each, so past MAX_EXTRA_NOTE_MESSAGES they're reported once, where the first one occurs.
        val extraCount = steps.count { it is Insertion }
        val summariseExtras = extraCount > MAX_EXTRA_NOTE_MESSAGES
        var extrasReported = false
        var k = 0
        while (k < steps.size) {
            val swap = swapAt(steps, k, expected, ::playedPitch)
            if (swap != null) {
                messages += "Notes ${swap + 1} and ${swap + 2} were swapped."
                k += if (steps[k] is Substitution) 2 else 3
                continue
            }
            when (val step = steps[k]) {
                is Match -> {}
                is Deletion -> messages += "You missed ${expected[step.expectedIndex]} (note ${step.expectedIndex + 1})."
                is Substitution -> {
                    val played = playedPitch(step.detectedIndex)
                    messages += if (played == null) {
                        "We couldn't hear note ${step.expectedIndex + 1} clearly."
                    } else {
                        "You played $played where ${expected[step.expectedIndex]} was expected (note ${step.expectedIndex + 1})."
                    }
                }
                is Insertion -> when {
                    !summariseExtras -> {
                        val played = playedPitch(step.detectedIndex)
                        messages += if (played == null) "We heard an extra note we couldn't identify." else "You added an extra $played."
                    }
                    !extrasReported -> {
                        messages += "We heard $extraCount extra notes. Play only the notes in the phrase, and keep the room quiet while you record."
                        extrasReported = true
                    }
                }
            }
            k++
        }
        return messages
    }

    /**
     * If expected notes i and i+1 were played the other way round starting at step [k], returns i.
     * A swap shows up as missed–correct–extra, extra–correct–missed, or two crossed wrong notes.
     */
    private fun swapAt(steps: List<AlignmentStep>, k: Int, expected: List<Pitch>, played: (Int) -> Pitch?): Int? {
        val a = steps[k]
        val b = steps.getOrNull(k + 1) ?: return null
        if (a is Substitution && b is Substitution && b.expectedIndex == a.expectedIndex + 1) {
            val i = a.expectedIndex
            if (played(a.detectedIndex) == expected[i + 1] && played(b.detectedIndex) == expected[i]) return i
            return null
        }
        val c = steps.getOrNull(k + 2) ?: return null
        if (a is Deletion && b is Match && c is Insertion && b.expectedIndex == a.expectedIndex + 1) {
            if (played(c.detectedIndex) == expected[a.expectedIndex]) return a.expectedIndex
        }
        if (a is Insertion && b is Match && c is Deletion && c.expectedIndex == b.expectedIndex + 1) {
            if (played(a.detectedIndex) == expected[c.expectedIndex]) return b.expectedIndex
        }
        return null
    }
}

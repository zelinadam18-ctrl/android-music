package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch

/** One step of an [Alignment], pairing an expected note with a detected note, or one of them with nothing. */
sealed interface AlignmentStep {

    /** The student played the expected note. */
    data class Match(val expectedIndex: Int, val detectedIndex: Int) : AlignmentStep

    /**
     * The student played a note where [expectedIndex] was expected, but it was the wrong note or
     * could not be identified reliably.
     */
    data class Substitution(val expectedIndex: Int, val detectedIndex: Int) : AlignmentStep

    /** The expected note was not played (a missed note). */
    data class Deletion(val expectedIndex: Int) : AlignmentStep

    /** The student played a note that isn't in the phrase (an extra note). */
    data class Insertion(val detectedIndex: Int) : AlignmentStep
}

/**
 * How the notes a student played line up with the notes of a phrase, in order.
 *
 * Every expected note appears in exactly one [AlignmentStep.Match], [AlignmentStep.Substitution]
 * or [AlignmentStep.Deletion]; every detected note in exactly one Match, Substitution or
 * [AlignmentStep.Insertion]. Steps are in playing order.
 */
data class Alignment(val expectedCount: Int, val detectedCount: Int, val steps: List<AlignmentStep>) {

    val matches: List<AlignmentStep.Match> get() = steps.filterIsInstance<AlignmentStep.Match>()

    /** Expected notes that were played correctly. */
    val correctCount: Int get() = steps.count { it is AlignmentStep.Match }

    /** Expected notes that were played as a different (or unidentifiable) note. */
    val wrongCount: Int get() = steps.count { it is AlignmentStep.Substitution }

    /** Expected notes that were not played at all. */
    val missedCount: Int get() = steps.count { it is AlignmentStep.Deletion }

    /** Detected notes that don't correspond to any expected note. */
    val extraCount: Int get() = steps.count { it is AlignmentStep.Insertion }

    /** Edit distance: wrong + missed + extra. 0 means a perfect performance. */
    val editCount: Int get() = wrongCount + missedCount + extraCount

    val isPerfect: Boolean get() = editCount == 0 && correctCount == expectedCount
}

/**
 * Order-aware alignment of detected notes to expected notes (spec §11).
 *
 * Uses edit-distance dynamic programming (Needleman–Wunsch style). Each wrong note, missed note
 * and extra note costs 1; a correct note costs 0. Because alignment works on the order of the
 * notes, not their absolute times, a student who plays more slowly or quickly still aligns
 * correctly, while notes played in the wrong order cannot all count as correct.
 *
 * A detected note only matches when it is [reliable][DetectedNote.isReliable] and has the
 * expected pitch. Uncertain notes can be aligned to an expected note, but only as a
 * [AlignmentStep.Substitution].
 *
 * Deterministic: among alignments with the lowest cost, the one with the most correct notes
 * wins; any remaining tie is broken by preferring, at each step from the start, a paired step
 * (match or substitution), then a missed note, then an extra note.
 */
object Aligner {

    fun align(expected: Phrase, detected: List<DetectedNote>): Alignment =
        align(expected.notes.map { it.pitch!! }, detected)

    fun align(expected: List<Pitch>, detected: List<DetectedNote>): Alignment {
        val n = expected.size
        val m = detected.size

        fun isMatch(i: Int, j: Int) = detected[j].isReliable && detected[j].pitch == expected[i]

        // cost[i][j] / correct[i][j]: best alignment of expected[i..] with detected[j..],
        // lowest cost first, then most correct notes.
        val cost = Array(n + 1) { IntArray(m + 1) }
        val correct = Array(n + 1) { IntArray(m + 1) }
        for (i in n downTo 0) {
            for (j in m downTo 0) {
                if (i == n && j == m) continue
                var bestCost = Int.MAX_VALUE
                var bestCorrect = -1
                fun consider(c: Int, k: Int) {
                    if (c < bestCost || (c == bestCost && k > bestCorrect)) {
                        bestCost = c
                        bestCorrect = k
                    }
                }
                if (i < n && j < m) {
                    val hit = isMatch(i, j)
                    consider(cost[i + 1][j + 1] + if (hit) 0 else 1, correct[i + 1][j + 1] + if (hit) 1 else 0)
                }
                if (i < n) consider(cost[i + 1][j] + 1, correct[i + 1][j])
                if (j < m) consider(cost[i][j + 1] + 1, correct[i][j + 1])
                cost[i][j] = bestCost
                correct[i][j] = bestCorrect
            }
        }

        // Walk forward from the start, taking the first optimal step in a fixed order.
        val steps = ArrayList<AlignmentStep>(n + m)
        var i = 0
        var j = 0
        while (i < n || j < m) {
            val target = cost[i][j] to correct[i][j]
            if (i < n && j < m) {
                val hit = isMatch(i, j)
                val paired = (cost[i + 1][j + 1] + if (hit) 0 else 1) to (correct[i + 1][j + 1] + if (hit) 1 else 0)
                if (paired == target) {
                    steps += if (hit) AlignmentStep.Match(i, j) else AlignmentStep.Substitution(i, j)
                    i++
                    j++
                    continue
                }
            }
            if (i < n && (cost[i + 1][j] + 1 to correct[i + 1][j]) == target) {
                steps += AlignmentStep.Deletion(i)
                i++
                continue
            }
            steps += AlignmentStep.Insertion(j)
            j++
        }
        return Alignment(n, m, steps)
    }
}

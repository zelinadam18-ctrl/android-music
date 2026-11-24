package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.floor

/**
 * The score for one attempt at a phrase, with every component that went into it.
 *
 * @property noteAccuracy 0–1, see [PhraseScorer].
 * @property orderAccuracy 0–1, see [PhraseScorer].
 * @property timingAccuracy 0–1, from [Timing].
 * @property tempoAccuracy 0–1, from [Timing].
 * @property rawScore the weighted sum on a 0–100 scale, before rounding.
 * @property score [rawScore] rounded once, half up. This whole number is what is displayed,
 *   stored, and compared with the unlock (50) and star (90) thresholds.
 */
data class PhraseScore(
    val noteAccuracy: Double,
    val orderAccuracy: Double,
    val timingAccuracy: Double,
    val tempoAccuracy: Double,
    val rawScore: Double,
    val score: Int,
    val alignment: Alignment,
    val timing: TimingResult,
)

/**
 * Turns a performance into a transparent 0–100 phrase score (spec §11). See `docs/scoring.md`.
 *
 * ```
 * raw = 45 · noteAccuracy + 20 · orderAccuracy + 20 · timingAccuracy + 15 · tempoAccuracy
 * score = round half up (raw)
 * ```
 *
 * - **Note accuracy** = (correct − [EXTRA_NOTE_PENALTY] · extra) ÷ expected, clamped to 0–1.
 *   Correct notes come from the order-aware [Aligner]; wrong and missed notes simply aren't
 *   correct, and each extra note takes away half a correct note.
 * - **Order accuracy** = correct ÷ (notes that would be correct if order didn't matter), or 0 when
 *   no expected pitch was played. "Notes that would be correct if order didn't matter" counts each
 *   reliably detected note whose pitch is still unused among the expected notes. Playing the
 *   right notes in the wrong order keeps that count high while the order-aware correct count
 *   drops, so wrong order can never reach 100.
 * - **Timing accuracy** and **tempo accuracy** come from [Timing]. Tempo has no penalty within
 *   ±15% of the reference tempo.
 *
 * The weights are a starting point, to be tuned against real recordings (#38).
 *
 * Deterministic: the same expected notes and detected notes always give the same score.
 */
object PhraseScorer {
    const val NOTE_WEIGHT = 45
    const val ORDER_WEIGHT = 20
    const val TIMING_WEIGHT = 20
    const val TEMPO_WEIGHT = 15
    const val EXTRA_NOTE_PENALTY = 0.5

    fun score(phrase: Phrase, tempoBpm: Double, detected: List<DetectedNote>): PhraseScore {
        val expected = phrase.notes.map { it.pitch!! }
        val alignment = Aligner.align(expected, detected)
        val timing = Timing.measure(phrase, tempoBpm, detected, alignment)

        val noteAccuracy = ((alignment.correctCount - EXTRA_NOTE_PENALTY * alignment.extraCount) / expected.size)
            .coerceIn(0.0, 1.0)
        val orderAccuracy = orderAccuracy(expected, detected, alignment)

        val raw = NOTE_WEIGHT * noteAccuracy +
            ORDER_WEIGHT * orderAccuracy +
            TIMING_WEIGHT * timing.timingAccuracy +
            TEMPO_WEIGHT * timing.tempoAccuracy

        return PhraseScore(
            noteAccuracy = noteAccuracy,
            orderAccuracy = orderAccuracy,
            timingAccuracy = timing.timingAccuracy,
            tempoAccuracy = timing.tempoAccuracy,
            rawScore = raw,
            score = roundScore(raw),
            alignment = alignment,
            timing = timing,
        )
    }

    /**
     * Rounds a raw 0–100 score once, half up, to the whole number used everywhere:
     * 49.5 → 50 (unlocks), 49.4 → 49 (doesn't).
     */
    fun roundScore(raw: Double): Int {
        require(raw.isFinite()) { "Score must be a finite number, but was $raw" }
        // Nudge by a tiny epsilon so that a value meant to be exactly x.5, but computed as
        // x.4999999999999, still rounds up.
        return floor(raw + 0.5 + 1e-9).toInt().coerceIn(0, 100)
    }

    private fun orderAccuracy(expected: List<Pitch>, detected: List<DetectedNote>, alignment: Alignment): Double {
        val unused = expected.groupingBy { it }.eachCount().toMutableMap()
        var orderless = 0
        for (note in detected) {
            val pitch = note.pitch ?: continue
            if (note.uncertain) continue
            val left = unused[pitch] ?: 0
            if (left > 0) {
                unused[pitch] = left - 1
                orderless++
            }
        }
        return if (orderless == 0) 0.0 else alignment.correctCount.toDouble() / orderless
    }
}

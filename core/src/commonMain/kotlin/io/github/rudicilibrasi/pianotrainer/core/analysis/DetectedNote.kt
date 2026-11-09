package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch

/**
 * One note the student played, as found in their recording.
 *
 * @property pitch the nearest piano key, or null when no pitch could be identified at all.
 * @property startMs where the note starts, measured from the start of the recording.
 * @property endMs where the note ends, measured from the start of the recording.
 * @property confidence how sure the detector is about [pitch], from 0 (not at all) to 1.
 * @property uncertain true when the detector could not identify the note reliably. An uncertain
 *   note is shown to the student as "?" and never counts as a correct note.
 * @property cents how far the played frequency was from [pitch], in cents (−50 to +50), when
 *   known. Positive is sharp.
 */
data class DetectedNote(
    val pitch: Pitch?,
    val startMs: Double,
    val endMs: Double,
    val confidence: Double,
    val uncertain: Boolean,
    val cents: Double? = null,
) {
    init {
        require(startMs.isFinite() && endMs.isFinite() && endMs >= startMs) {
            "Note must end at or after it starts, but was $startMs..$endMs ms"
        }
        require(confidence in 0.0..1.0) { "Confidence must be between 0 and 1, but was $confidence" }
    }

    /** True when this note can be trusted: it has a pitch and isn't marked [uncertain]. */
    val isReliable: Boolean get() = pitch != null && !uncertain

    val durationMs: Double get() = endMs - startMs
}

package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote

/**
 * The reference audio, phrases, detection and scoring must all use the same note data (#42):
 * render a phrase's reference the way the app does (#19, #21), round-trip it through a 16-bit WAV
 * (#18), analyse it (#37) and score it (#41).
 */
object ConsistencyCheck {
    private val synthesizer = Synthesizer()

    /** The reference for [phrase] at [tempoBpm], analysed exactly as a recording would be. */
    suspend fun analyseReference(phrase: Phrase, tempoBpm: Double): List<DetectedNote> {
        val wav = Wav.encode(synthesizer.renderPhrase(phrase, tempoBpm), synthesizer.sampleRate)
        return AudioAnalyzer().analyze(wav).notes
    }

    /** Problems with [phrase] scoring against its own reference, or null if it's a clean 100. */
    suspend fun checkSelf(phrase: Phrase, tempoBpm: Double): String? {
        val detected = analyseReference(phrase, tempoBpm)
        val expected = phrase.notes.map { it.pitch }
        if (detected.map { it.pitch } != expected) return "detected ${detected.map { it.pitch ?: "?" }}, expected $expected"
        if (detected.any { it.uncertain }) return "uncertain notes at ${detected.withIndex().filter { it.value.uncertain }.map { it.index + 1 }}"
        val score = PhraseScorer.score(phrase, tempoBpm, detected).score
        return if (score == 100) null else "scored $score against itself"
    }

    /** The score of [played]'s reference judged as an attempt at [expected]. */
    suspend fun crossScore(played: Phrase, expected: Phrase, tempoBpm: Double): Int =
        PhraseScorer.score(expected, tempoBpm, analyseReference(played, tempoBpm)).score
}

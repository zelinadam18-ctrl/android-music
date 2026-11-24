package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.random.Random

/**
 * The original app's random-note exercise (spec Phase One appendix, #64): a short phrase of
 * random white keys to play, outside any song.
 */
object FreePractice {
    const val NOTE_COUNT = 6
    const val TEMPO_BPM = 80.0

    /** The white keys from G3 to G5 that notes are drawn from. */
    val WHITE_KEYS: List<Pitch> = (Pitch.parse("G3").midi..Pitch.parse("G5").midi).map(::Pitch).filter { it.name.length == 1 }

    /** A new phrase of [NOTE_COUNT] random white keys, as quarter notes. "New notes" calls this again. */
    fun newPhrase(random: Random = Random.Default): Phrase =
        Phrase(0, List(NOTE_COUNT) { NoteEvent(WHITE_KEYS.random(random), 1.0) }, startBeat = 0.0)
}

/**
 * Saves a Free Practice attempt to history. It never touches song progress: it only has the
 * attempt repository, and doesn't call the progression code at all.
 */
class RecordFreePractice(private val attempts: AttemptRepository) {
    suspend operator fun invoke(attempt: NewAttempt): Long {
        require(attempt.mode == AttemptMode.FreePractice) { "Only Free Practice attempts are saved here" }
        require(attempt.songId == null && attempt.phraseIndex == null) { "Free Practice isn't tied to a song" }
        return attempts.insert(attempt)
    }
}

package io.github.rudicilibrasi.pianotrainer.data

import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat

/**
 * A phrase's reference recording, ready to play (#21).
 *
 * @property path where the WAV is stored, relative to [AppStorage] (`reference/…`).
 * @property wav the WAV bytes.
 * @property noteStartsMs when each note of the phrase starts (rests not counted), for the note
 *   highlight (#30) and the comparison graph (#58).
 */
class ReferenceAudio(val path: String, val wav: ByteArray, val noteStartsMs: List<Double>)

/**
 * Renders each phrase's reference from the song data and caches it under `reference/` (spec §4,
 * §8, #21), so the audio always matches the notes on screen.
 *
 * The cache key holds the song id, the phrase number, a hash of the phrase's notes and tempo, and
 * [Synthesizer.VERSION]. Editing a song file, or changing how notes are synthesized, gives a new
 * key and so a freshly rendered file; a missing or unreadable cached file is simply rendered again.
 */
class ReferenceAudioCache(
    private val storage: AppStorage,
    private val synthesizer: Synthesizer = Synthesizer(),
) {
    /** How many times a reference was actually rendered, rather than read from the cache. */
    var renders: Int = 0
        private set

    fun reference(songId: String?, phrase: Phrase, tempoBpm: Double): ReferenceAudio {
        val path = AppStorage.referencePath(key(songId, phrase, tempoBpm))
        val starts = phrase.timedEvents(tempoBpm).filter { !it.event.isRest }.map { it.startMs }
        val cached = (storage.read(path) as? StoredFile.Found)?.bytes?.takeIf { isWav(it) }
        if (cached != null) return ReferenceAudio(path, cached, starts)

        val wav = Wav.encode(synthesizer.renderPhrase(phrase, tempoBpm), synthesizer.sampleRate)
        renders++
        // Even if the cache can't be written (disk full), the reference still plays from memory.
        storage.write(path, wav)
        return ReferenceAudio(path, wav, starts)
    }

    companion object {
        /** `twinkle_p1_5f3a…_v1`, or `free_…` for Free Practice notes. */
        fun key(songId: String?, phrase: Phrase, tempoBpm: Double): String {
            val notes = SongFormat.formatEvents(phrase.events)
            val hash = fnv1a64("$notes@$tempoBpm").toULong().toString(16).padStart(16, '0')
            val what = if (songId == null) "free" else "${songId}_p${phrase.number}"
            return "${what}_${hash}_v${Synthesizer.VERSION}"
        }

        private fun isWav(bytes: ByteArray) = runCatching { Wav.decode(bytes) }.isSuccess

        private fun fnv1a64(text: String): Long {
            var hash = -0x340d631b7bdddcdbL // 14695981039346656037
            for (byte in text.encodeToByteArray()) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash
        }
    }
}

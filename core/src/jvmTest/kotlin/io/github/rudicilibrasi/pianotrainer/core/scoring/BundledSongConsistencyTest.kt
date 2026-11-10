package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #44 over every bundled song: each phrase's reference scores 100 against itself, and phrase N's
 * reference scores clearly lower as an attempt at phrase N + 1. New songs are included as soon as
 * they are listed in `index.json`.
 */
class BundledSongConsistencyTest {

    private val songsDir = File("../composeApp/src/commonMain/composeResources/files/songs")

    @Test
    fun everyBundledPhraseScores100AgainstItselfAndLowerAgainstTheNext() = runBlocking {
        assertTrue(songsDir.isDirectory, "song folder not found at ${songsDir.absolutePath}")
        val ids = SongFormat.parseIndex(File(songsDir, "index.json").readText())
        val problems = ArrayList<String>()
        for (id in ids) {
            val song = SongFormat.parseSong(File(songsDir, "$id.json").readText(), "$id.json")
            for (phrase in song.phrases) {
                ConsistencyCheck.checkSelf(phrase, song.tempoBpm)?.let { problems += "${song.title}, phrase ${phrase.number}: $it" }
                val next = song.phrases.getOrNull(phrase.index + 1) ?: continue
                // Identical phrases (repeats) can't score lower; only check ones that differ.
                if (next.notes.map { it.pitch } == phrase.notes.map { it.pitch }) continue
                val cross = ConsistencyCheck.crossScore(played = phrase, expected = next, song.tempoBpm)
                if (cross > MAX_CROSS_SCORE) problems += "${song.title}: phrase ${phrase.number}'s reference scored $cross as phrase ${next.number}"
            }
        }
        println("Checked reference consistency for ${ids.size} bundled songs")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private companion object {
        /** "Clearly lower": well short of the 90 needed for a star. */
        const val MAX_CROSS_SCORE = 80
    }
}

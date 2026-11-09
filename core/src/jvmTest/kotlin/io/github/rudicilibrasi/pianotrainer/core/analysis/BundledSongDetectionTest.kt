package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Onset and note detection on the synthesized reference of every phrase of every bundled song.
 * Onsets must match the note starts within 30 ms with none missing or extra, and the detected
 * notes must be exactly the phrase's notes. New songs are included as soon as they are listed in
 * `index.json`.
 */
class BundledSongDetectionTest {

    private val songsDir = File("../composeApp/src/commonMain/composeResources/files/songs")

    @Test
    fun everyBundledPhraseHasExactlyItsNoteOnsets() {
        assertTrue(songsDir.isDirectory, "song folder not found at ${songsDir.absolutePath}")
        val ids = SongFormat.parseIndex(File(songsDir, "index.json").readText())
        val problems = ids.flatMap { id ->
            val song = SongFormat.parseSong(File(songsDir, "$id.json").readText(), "$id.json")
            song.phrases.mapNotNull { phrase ->
                OnsetCheck.check(phrase, song.tempoBpm)?.let { "${song.title}, phrase ${phrase.number}: $it" }
            }
        }
        println("Checked onsets in ${ids.size} bundled songs")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun everyBundledPhraseIsDetectedExactly() {
        val ids = SongFormat.parseIndex(File(songsDir, "index.json").readText())
        val detector = NoteDetector(44100)
        val problems = ids.flatMap { id ->
            val song = SongFormat.parseSong(File(songsDir, "$id.json").readText(), "$id.json")
            song.phrases.mapNotNull { phrase ->
                val (samples, notes) = NoteCheck.render(phrase, song.tempoBpm)
                NoteCheck.check(notes, detector.detect(samples))?.let { "${song.title}, phrase ${phrase.number}: $it" }
            }
        }
        println("Checked note detection in ${ids.size} bundled songs")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}

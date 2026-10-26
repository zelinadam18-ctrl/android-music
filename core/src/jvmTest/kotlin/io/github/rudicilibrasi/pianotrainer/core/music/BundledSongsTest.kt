package io.github.rudicilibrasi.pianotrainer.core.music

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates every song bundled with the app, so a mistake in a song file fails CI with a message
 * naming the song and phrase. Runs on the JVM because it reads the files straight from the
 * composeApp module's resources.
 */
class BundledSongsTest {

    private val songsDir = File("../composeApp/src/commonMain/composeResources/files/songs")

    @Test
    fun everyBundledSongIsValid() {
        assertTrue(songsDir.isDirectory, "song folder not found at ${songsDir.absolutePath}")
        val ids = SongFormat.parseIndex(File(songsDir, "index.json").readText())

        val unlisted = songsDir.listFiles { file -> file.extension == "json" && file.name != "index.json" }!!
            .map { it.nameWithoutExtension }
            .filter { it !in ids }
        val songs = ids.map { id ->
            val file = File(songsDir, "$id.json")
            if (!file.isFile) fail("index.json lists \"$id\" but $id.json doesn't exist")
            SongFormat.parseSong(file.readText(), file.name).also { song ->
                if (song.id != id) fail("${file.name}: id is \"${song.id}\" but the file is named \"$id\"")
            }
        }

        val issues = SongValidator.validateCatalog(songs)
        issues.forEach(::println)
        unlisted.forEach { println("WARNING: $it.json is not listed in index.json, so the app won't show it") }
        println("Checked ${songs.size} bundled songs: ${issues.count { it.severity == SongIssue.Severity.ERROR }} errors, " +
            "${issues.count { it.severity == SongIssue.Severity.WARNING } + unlisted.size} warnings")

        val errors = issues.filter { it.severity == SongIssue.Severity.ERROR }
        if (errors.isNotEmpty()) fail("Song errors:\n" + errors.joinToString("\n") { "  ${it.message}" })
    }
}

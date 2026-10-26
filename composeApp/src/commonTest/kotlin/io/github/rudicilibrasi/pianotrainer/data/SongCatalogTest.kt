package io.github.rudicilibrasi.pianotrainer.data

import io.github.rudicilibrasi.pianotrainer.core.music.SongFormatException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SongCatalogTest {

    private fun song(id: String, title: String) = """
        {
          "id": "$id", "title": "$title", "tempoBpm": 100, "timeSignature": "4/4", "key": "C major",
          "source": "traditional", "license": "Public domain", "sourceVerified": false,
          "phrases": ["C4/1 D4/1 E4/2"]
        }
    """

    private fun catalog(files: Map<String, String>) = SongCatalog { path ->
        (files[path] ?: throw IllegalStateException("missing resource $path")).encodeToByteArray()
    }

    @Test
    fun loadsSongsInIndexOrder() = runTest {
        val songs = catalog(
            mapOf(
                "files/songs/index.json" to """{ "songs": ["b-song", "a-song"] }""",
                "files/songs/a-song.json" to song("a-song", "A Song"),
                "files/songs/b-song.json" to song("b-song", "B Song"),
            ),
        ).loadSongs()
        assertEquals(listOf("B Song", "A Song"), songs.map { it.title })
    }

    @Test
    fun anEmptyIndexMeansNoSongs() = runTest {
        assertEquals(emptyList(), catalog(mapOf("files/songs/index.json" to """{ "songs": [] }""")).loadSongs())
    }

    @Test
    fun rejectsASongWhoseIdDoesNotMatchItsFile() = runTest {
        val error = assertFailsWith<SongFormatException> {
            catalog(
                mapOf(
                    "files/songs/index.json" to """{ "songs": ["twinkle"] }""",
                    "files/songs/twinkle.json" to song("mary", "Mary Had a Little Lamb"),
                ),
            ).loadSongs()
        }
        assertTrue(error.message!!.startsWith("twinkle.json: "), error.message)
    }
}

package io.github.rudicilibrasi.pianotrainer.data

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormatException
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pianotrainer.composeapp.generated.resources.Res

/**
 * Loads the bundled songs: `files/songs/index.json` lists the song ids in menu order, and each
 * song is in `files/songs/<id>.json` (see `docs/song-format.md`).
 *
 * @param readFile reads a file from the app's bundled resources, given its path.
 */
class SongCatalog(private val readFile: suspend (path: String) -> ByteArray) {

    /**
     * Loads every song listed in the index, in menu order.
     *
     * @throws SongFormatException if the index or a song file is malformed or a song's id doesn't
     *   match its file name.
     */
    suspend fun loadSongs(): List<Song> {
        val ids = SongFormat.parseIndex(readFile("$SONGS_DIR/index.json").decodeToString())
        return ids.map { id ->
            val fileName = "$id.json"
            val song = SongFormat.parseSong(readFile("$SONGS_DIR/$fileName").decodeToString(), fileName)
            if (song.id != id) throw SongFormatException("$fileName: id is \"${song.id}\" but the file is listed as \"$id\"")
            song
        }
    }

    companion object {
        const val SONGS_DIR = "files/songs"

        /** The catalog of songs bundled with the app. */
        @OptIn(ExperimentalResourceApi::class)
        fun bundled(): SongCatalog = SongCatalog { path -> Res.readBytes(path) }
    }
}

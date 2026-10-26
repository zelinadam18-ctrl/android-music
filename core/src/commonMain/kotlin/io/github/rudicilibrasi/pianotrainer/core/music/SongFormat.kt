package io.github.rudicilibrasi.pianotrainer.core.music

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Thrown when a song file can't be read. The message names the file (and phrase, if relevant). */
class SongFormatException(message: String) : IllegalArgumentException(message)

/**
 * Reads song files (see `docs/song-format.md`).
 *
 * Each song is a JSON file whose phrases are written in a compact `pitch/beats` syntax, e.g.
 * `"C4/1 C4/1 G4/2 R/1"`. `index.json` lists the song ids in menu order.
 */
object SongFormat {

    private val json = Json

    @Serializable
    private class SongFile(
        val id: String,
        val title: String,
        val composer: String? = null,
        val tempoBpm: Double,
        val timeSignature: String,
        val key: String,
        val pickupBeats: Double = 0.0,
        val source: String,
        val license: String,
        val sourceVerified: Boolean,
        val arrangerNotes: String? = null,
        val phrases: List<String>,
    )

    @Serializable
    private class IndexFile(val songs: List<String>)

    /** Parses `index.json` into the list of song ids, in menu order. */
    fun parseIndex(text: String, fileName: String = "index.json"): List<String> {
        val index = decode<IndexFile>(text, fileName)
        val duplicates = index.songs.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) fail(fileName, "lists ${duplicates.joinToString()} more than once")
        return index.songs
    }

    /** Parses one song file. [fileName] is only used in error messages. */
    fun parseSong(text: String, fileName: String): Song {
        val file = decode<SongFile>(text, fileName)
        val phrases = file.phrases.mapIndexed { index, phrase ->
            try {
                parseEvents(phrase)
            } catch (e: IllegalArgumentException) {
                fail(fileName, "phrase ${index + 1}: ${e.message}")
            }
        }
        val timeSignature = try {
            parseTimeSignature(file.timeSignature)
        } catch (e: IllegalArgumentException) {
            fail(fileName, e.message.orEmpty())
        }
        return try {
            Song(
                id = file.id,
                title = file.title,
                composer = file.composer,
                tempoBpm = file.tempoBpm,
                timeSignature = timeSignature,
                key = file.key,
                source = file.source,
                license = file.license,
                sourceVerified = file.sourceVerified,
                phrases = phrases,
                pickupBeats = file.pickupBeats,
                arrangerNotes = file.arrangerNotes,
            )
        } catch (e: IllegalArgumentException) {
            fail(fileName, e.message.orEmpty())
        }
    }

    /**
     * Parses a space-separated list of events such as `"C4/1 F#4/0.5 R/1 E4/1.5"`: a pitch (or `R`
     * for a rest), a slash, and the duration in quarter-note beats.
     */
    fun parseEvents(text: String): List<NoteEvent> {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(tokens.isNotEmpty()) { "is empty" }
        return tokens.mapIndexed { index, token ->
            val parts = token.split('/')
            require(parts.size == 2) { "event ${index + 1} \"$token\" should look like C4/1 or R/1" }
            val beats = parts[1].toDoubleOrNull()
            require(beats != null && beats.isFinite() && beats > 0.0) {
                "event ${index + 1} \"$token\" has an invalid duration \"${parts[1]}\""
            }
            val pitch = if (parts[0] == "R") {
                null
            } else {
                try {
                    Pitch.parse(parts[0])
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("event ${index + 1} \"$token\": ${e.message}")
                }
            }
            NoteEvent(pitch, beats)
        }
    }

    /** Formats events back into the compact syntax (the inverse of [parseEvents]). */
    fun formatEvents(events: List<NoteEvent>): String = events.joinToString(" ") { event ->
        val beats = event.beats.toString().removeSuffix(".0")
        "${event.pitch?.label ?: "R"}/$beats"
    }

    private fun parseTimeSignature(text: String): TimeSignature {
        val parts = text.split('/')
        val beatsPerBar = parts.getOrNull(0)?.toIntOrNull()
        val beatUnit = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && beatsPerBar != null && beatUnit != null) {
            "time signature \"$text\" should look like 4/4 or 3/4"
        }
        return TimeSignature(beatsPerBar, beatUnit)
    }

    private inline fun <reified T> decode(text: String, fileName: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        fail(fileName, e.message.orEmpty().lineSequence().first())
    } catch (e: IllegalArgumentException) {
        fail(fileName, e.message.orEmpty().lineSequence().first())
    }

    private fun fail(fileName: String, message: String): Nothing = throw SongFormatException("$fileName: $message")
}

package io.github.rudicilibrasi.pianotrainer.core.music

/** A problem found in a song. Errors must be fixed; warnings are worth a second look. */
data class SongIssue(val severity: Severity, val message: String) {
    enum class Severity { WARNING, ERROR }

    override fun toString(): String = "$severity: $message"
}

/**
 * Checks a song against the catalog's rules (spec sections 3 and 4) so mistakes in song files are
 * caught by tests rather than by students:
 *
 * - phrases of 6–8 notes (4–5 or 9–10 is a warning; fewer than 4 or more than 10 is an error);
 * - only white keys (C major) between G3 and G5;
 * - only standard note lengths;
 * - a source and a public-domain license (an unverified source is a warning).
 */
object SongValidator {
    val LOWEST: Pitch = Pitch.parse("G3")
    val HIGHEST: Pitch = Pitch.parse("G5")

    /** Note and rest lengths in beats: whole, half, quarter, eighth and sixteenth, plain or dotted. */
    val SUPPORTED_BEATS: Set<Double> = setOf(6.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.75, 0.5, 0.375, 0.25)

    fun validate(song: Song): List<SongIssue> {
        val issues = mutableListOf<SongIssue>()
        fun error(message: String) = issues.add(SongIssue(SongIssue.Severity.ERROR, "${song.id}: $message"))
        fun warning(message: String) = issues.add(SongIssue(SongIssue.Severity.WARNING, "${song.id}: $message"))

        if (song.source.isBlank()) error("has no source")
        if (!song.license.contains("public domain", ignoreCase = true)) error("license \"${song.license}\" is not public domain")
        if (!song.sourceVerified) warning("notes have not yet been checked against the source by a person")

        for (phrase in song.phrases) {
            val where = "phrase ${phrase.number}"
            when (phrase.noteCount) {
                in 6..8 -> Unit
                4, 5, 9, 10 -> warning("$where has ${phrase.noteCount} notes; 6–8 is the target")
                else -> error("$where has ${phrase.noteCount} notes; phrases must have 4–10")
            }
            phrase.events.forEachIndexed { index, event ->
                val what = "$where, event ${index + 1} (${event})"
                if (event.beats !in SUPPORTED_BEATS) error("$what has an unsupported length of ${event.beats} beats")
                val pitch = event.pitch ?: return@forEachIndexed
                if (pitch.name.length > 1) error("$what is a black key; songs use C major (white keys only)")
                if (pitch < LOWEST || pitch > HIGHEST) error("$what is outside the beginner range ${LOWEST.label}–${HIGHEST.label}")
            }
        }
        return issues
    }

    /** Checks a whole catalog: every song, plus ids that appear more than once. */
    fun validateCatalog(songs: List<Song>): List<SongIssue> {
        val duplicates = songs.groupBy { it.id }.filterValues { it.size > 1 }.keys
        return duplicates.map { SongIssue(SongIssue.Severity.ERROR, "$it: id is used by more than one song") } +
            songs.flatMap(::validate)
    }
}

package io.github.rudicilibrasi.pianotrainer.data

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/** The outcome of reading a stored file: never an exception. */
sealed interface StoredFile {
    data class Found(val bytes: ByteArray) : StoredFile
    data object Missing : StoredFile
    data class Unreadable(val reason: String) : StoredFile
}

/**
 * The app's private file storage (spec §13, #20): cached reference audio under `reference/` and
 * the student's recordings under `recordings/`.
 *
 * Everything outside this class uses **relative** paths such as
 * `recordings/2026-10-25T17-03-12_twinkle_p3.wav`, which is what the database stores. Only
 * [absolute] turns one into a full path, at the moment a platform API needs it, so stored paths
 * stay valid even if the app's private folder moves.
 *
 * Missing or unreadable files come back as results, never crashes.
 *
 * @param root the app's private folder: `context.filesDir`.
 */
class AppStorage(private val root: Path, private val fileSystem: FileSystem = FileSystem.SYSTEM) {

    /** Writes [bytes] to [relativePath], replacing any old file. Returns false if it couldn't be written. */
    fun write(relativePath: String, bytes: ByteArray): Boolean {
        val target = resolve(relativePath)
        // Write to a temporary file first, so a crash never leaves half a WAV under the real name.
        val temporary = "${target.name}.partial".let { target.parent?.div(it) ?: it.toPath() }
        return try {
            target.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(temporary) { write(bytes) }
            fileSystem.atomicMove(temporary, target)
            true
        } catch (e: IOException) {
            runCatching { fileSystem.delete(temporary) }
            false
        }
    }

    fun read(relativePath: String): StoredFile {
        val path = resolve(relativePath)
        return try {
            if (!fileSystem.exists(path)) StoredFile.Missing else StoredFile.Found(fileSystem.read(path) { readByteArray() })
        } catch (e: IOException) {
            StoredFile.Unreadable(e.message ?: "couldn't be read")
        }
    }

    fun exists(relativePath: String): Boolean = runCatching { fileSystem.exists(resolve(relativePath)) }.getOrDefault(false)

    /** Deletes [relativePath] if it's there. Returns false if it couldn't be deleted. */
    fun delete(relativePath: String): Boolean = runCatching { fileSystem.delete(resolve(relativePath)); true }.getOrDefault(false)

    /** Every file directly under [folder] (e.g. [RECORDINGS]), as relative paths. */
    fun list(folder: String): List<String> {
        val dir = resolve(folder)
        return runCatching {
            if (!fileSystem.exists(dir)) emptyList() else fileSystem.list(dir).filter { fileSystem.metadata(it).isRegularFile }.map { "$folder/${it.name}" }
        }.getOrDefault(emptyList()).sorted()
    }

    /** The full path of [relativePath], only for handing to a platform API such as a player. */
    fun absolute(relativePath: String): String = resolve(relativePath).toString()

    private fun resolve(relativePath: String): Path {
        require(isRelative(relativePath)) { "\"$relativePath\" must be a relative path inside the app's storage" }
        return root / relativePath
    }

    companion object {
        const val REFERENCE = "reference"
        const val RECORDINGS = "recordings"

        /** True for a path like `recordings/x.wav`: not absolute, no `..`, no backslashes or drive letters. */
        fun isRelative(path: String): Boolean =
            path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') && !path.contains(':') &&
                path.split('/').none { it == ".." || it.isEmpty() }

        /**
         * `recordings/2026-10-25T17-03-12_twinkle_p3.wav`: when (UTC, to the second), which song and phrase
         * (or `free` for Free Practice), and [suffix] to keep two recordings in the same second apart.
         */
        fun recordingPath(createdAtIso: String, songId: String?, phraseNumber: Int?, suffix: Int = 0): String {
            val time = createdAtIso.substringBefore('.').removeSuffix("Z").replace(':', '-')
            val what = if (songId == null || phraseNumber == null) "free" else "${safe(songId)}_p$phraseNumber"
            val extra = if (suffix > 0) "-$suffix" else ""
            return "$RECORDINGS/${time}_$what$extra.wav"
        }

        /** `reference/twinkle_p1_<hash>.wav`; the cache key itself is built by #21. */
        fun referencePath(key: String): String = "$REFERENCE/${safe(key)}.wav"

        private fun safe(text: String): String = text.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")
    }
}

package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.ProgressUpdate
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import kotlinx.coroutines.flow.Flow

/** Read-only access to saved attempts, for history screens (#62, #63). */
interface AttemptHistory {
    suspend fun get(id: Long): Attempt?
    fun observeAll(): Flow<List<Attempt>>
    fun observeForSong(songId: String): Flow<List<Attempt>>
    fun observeForPhrase(songId: String, phraseIndex: Int): Flow<List<Attempt>>
}

/** Read-only access to saved progress, for every screen except the one that records attempts. */
interface ProgressReader {
    fun observe(song: Song): Flow<SongProgress>
    fun observeAll(songs: List<Song>): Flow<Map<String, SongProgress>>
    suspend fun get(song: Song): SongProgress
}

sealed interface StartLessonResult {
    data class Ready(val song: Song, val phrase: Phrase, val progress: SongProgress) : StartLessonResult

    /** The phrase is locked; [message] is ready to show. */
    data class Locked(val phraseIndex: Int, val message: String) : StartLessonResult

    data class NoSuchPhrase(val phraseIndex: Int) : StartLessonResult
}

/**
 * Opens a lesson on one phrase, refusing locked ones (spec §5). Every route into a lesson goes
 * through here (navigation, deep links, restored app state, back/forward), so the lock can't be
 * bypassed by the UI.
 */
class StartLesson(private val progress: ProgressReader) {
    suspend operator fun invoke(song: Song, phraseIndex: Int): StartLessonResult {
        val phrase = song.phrases.getOrNull(phraseIndex) ?: return StartLessonResult.NoSuchPhrase(phraseIndex)
        val current = progress.get(song)
        if (!current.isUnlocked(phraseIndex)) {
            return StartLessonResult.Locked(phraseIndex, lockedMessage(phraseIndex))
        }
        return StartLessonResult.Ready(song, phrase, current)
    }
}

sealed interface RecordAttemptResult {
    /** Saved: the attempt's id and how progress changed. */
    data class Recorded(val attemptId: Long, val update: ProgressUpdate) : RecordAttemptResult

    /** Nothing was saved because the phrase is locked. */
    data class Locked(val phraseIndex: Int, val message: String) : RecordAttemptResult
}

/**
 * The only way progress changes (spec §5, §13): saves a lesson attempt and applies the phrase
 * progression (#48) and song score and star rules (#49) in **one database transaction**, so the
 * attempt and the progress it caused are saved together or not at all.
 *
 * Call it only after analysis has finished successfully; a failed or cancelled analysis never
 * reaches it, so progress stays unchanged.
 */
class RecordAttempt(
    private val database: PianoDatabase,
    private val attempts: AttemptRepository,
    private val progress: ProgressRepository,
) {
    suspend operator fun invoke(song: Song, attempt: NewAttempt): RecordAttemptResult {
        require(attempt.mode == AttemptMode.Lesson) { "Only lesson attempts change progress; save free practice with AttemptRepository" }
        require(attempt.songId == song.id) { "Attempt is for song \"${attempt.songId}\", not \"${song.id}\"" }
        val phraseIndex = requireNotNull(attempt.phraseIndex)
        require(phraseIndex in song.phrases.indices) { "Song \"${song.id}\" has no phrase ${phraseIndex + 1}" }

        return database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val before = progress.get(song)
                if (!before.isUnlocked(phraseIndex)) {
                    RecordAttemptResult.Locked(phraseIndex, lockedMessage(phraseIndex))
                } else {
                    val id = attempts.insert(attempt)
                    val update = Progression.recordScore(before, phraseIndex, attempt.scores.score)
                    progress.save(song.id, update.progress, attempt.createdAt)
                    RecordAttemptResult.Recorded(id, update)
                }
            }
        }
    }
}

private fun lockedMessage(phraseIndex: Int) =
    "Phrase ${phraseIndex + 1} is locked. Score ${Progression.UNLOCK_SCORE} or more on phrase $phraseIndex to unlock it."

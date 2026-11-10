package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Saved progress on one song (spec §7). The phrase-level detail is in [PhraseProgressEntity];
 * the summary columns here are kept in step with it on every save, so lists can read them
 * without loading every phrase.
 *
 * Times are milliseconds since the Unix epoch.
 */
@Entity(tableName = "song_progress", primaryKeys = ["songId"])
data class SongProgressEntity(
    val songId: String,
    val startedAt: Long?,
    val currentPhraseIndex: Int,
    val highestUnlockedIndex: Int,
    val completedPhraseCount: Int,
    val attemptCount: Int,
    val overallScore: Int?,
    val completed: Boolean,
    val starEarned: Boolean,
    val starEarnedAt: Long?,
    val lastAttemptAt: Long?,
)

/** Saved progress on one phrase of a song. */
@Entity(tableName = "phrase_progress", primaryKeys = ["songId", "phraseIndex"])
data class PhraseProgressEntity(
    val songId: String,
    val phraseIndex: Int,
    val noteCount: Int,
    val bestScore: Int?,
    val latestScore: Int?,
    val attemptCount: Int,
    val unlocked: Boolean,
)

@Dao
abstract class ProgressDao {
    @Query("SELECT * FROM song_progress WHERE songId = :songId")
    abstract fun observeSong(songId: String): Flow<SongProgressEntity?>

    @Query("SELECT * FROM song_progress WHERE songId = :songId")
    abstract suspend fun getSong(songId: String): SongProgressEntity?

    @Query("SELECT * FROM song_progress")
    abstract fun observeSongs(): Flow<List<SongProgressEntity>>

    @Query("SELECT * FROM phrase_progress WHERE songId = :songId ORDER BY phraseIndex")
    abstract fun observePhrases(songId: String): Flow<List<PhraseProgressEntity>>

    @Query("SELECT * FROM phrase_progress WHERE songId = :songId ORDER BY phraseIndex")
    abstract suspend fun getPhrases(songId: String): List<PhraseProgressEntity>

    @Query("SELECT * FROM phrase_progress ORDER BY songId, phraseIndex")
    abstract fun observeAllPhrases(): Flow<List<PhraseProgressEntity>>

    @Upsert
    abstract suspend fun upsertSong(song: SongProgressEntity)

    @Upsert
    abstract suspend fun upsertPhrases(phrases: List<PhraseProgressEntity>)

    @Query("DELETE FROM phrase_progress WHERE songId = :songId AND phraseIndex >= :fromIndex")
    abstract suspend fun deletePhrasesFrom(songId: String, fromIndex: Int)

    /** Writes a song's summary and all its phrases together, so readers never see half a save. */
    @Transaction
    open suspend fun save(song: SongProgressEntity, phrases: List<PhraseProgressEntity>) {
        upsertSong(song)
        upsertPhrases(phrases)
        deletePhrasesFrom(song.songId, phrases.size)
    }
}

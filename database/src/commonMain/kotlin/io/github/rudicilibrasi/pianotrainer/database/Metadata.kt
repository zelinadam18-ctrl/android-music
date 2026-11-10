package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Small app-wide values stored as text, such as when the database was first created. Also the
 * simplest entity for checking that the database works on every platform.
 */
@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface MetadataDao {
    @Upsert
    suspend fun put(entry: MetadataEntity)

    @Query("SELECT value FROM metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM metadata WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM metadata WHERE `key` = :key")
    suspend fun delete(key: String)
}

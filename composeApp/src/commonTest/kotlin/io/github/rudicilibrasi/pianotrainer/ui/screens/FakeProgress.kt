package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.database.ProgressDates
import io.github.rudicilibrasi.pianotrainer.database.ProgressReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory progress for screen tests. [set] behaves like a recorded attempt. */
class FakeProgress : ProgressReader {
    private val saved = MutableStateFlow<Map<String, SongProgress>>(emptyMap())

    fun set(songId: String, progress: SongProgress) {
        saved.value = saved.value + (songId to progress)
    }

    override fun observe(song: Song): Flow<SongProgress> = saved.map { it[song.id] ?: SongProgress.initial(song) }

    override fun observeAll(songs: List<Song>): Flow<Map<String, SongProgress>> =
        saved.map { all -> songs.associate { it.id to (all[it.id] ?: SongProgress.initial(it)) } }

    override suspend fun get(song: Song): SongProgress = saved.value[song.id] ?: SongProgress.initial(song)

    private val savedDates = HashMap<String, ProgressDates>()

    fun setDates(songId: String, dates: ProgressDates) {
        savedDates[songId] = dates
    }

    override suspend fun dates(songId: String): ProgressDates? = savedDates[songId]
}

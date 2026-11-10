package io.github.rudicilibrasi.pianotrainer.database

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.PhraseProgress
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Song and phrase progress that survives restarts (spec §7). Screens observe it as [Flow]s, so
 * they update as soon as an attempt is recorded.
 *
 * A song with nothing saved reads as [SongProgress.initial]. If a song file gains or loses
 * phrases after progress was saved, the saved phrases that still exist are kept and new ones
 * start locked (the first phrase is always unlocked).
 *
 * Only the use-case that records attempts (#53) should call [save].
 */
class ProgressRepository(private val dao: ProgressDao) : ProgressReader {

    /** Progress on [song], updating whenever it is saved. */
    override fun observe(song: Song): Flow<SongProgress> =
        dao.observePhrases(song.id).combine(dao.observeSong(song.id)) { phrases, summary ->
            toSongProgress(song, phrases, summary)
        }.distinctUntilChanged()

    /** Progress on each of [songs], keyed by song id, updating whenever any of it is saved. */
    override fun observeAll(songs: List<Song>): Flow<Map<String, SongProgress>> =
        dao.observeAllPhrases().combine(dao.observeSongs()) { phrases, summaries ->
            val phrasesBySong = phrases.groupBy { it.songId }
            val summaryBySong = summaries.associateBy { it.songId }
            songs.associate { song -> song.id to toSongProgress(song, phrasesBySong[song.id].orEmpty(), summaryBySong[song.id]) }
        }.distinctUntilChanged()

    override suspend fun get(song: Song): SongProgress = toSongProgress(song, dao.getPhrases(song.id), dao.getSong(song.id))

    /** Dates on a song's saved progress (milliseconds since the Unix epoch). */
    suspend fun dates(songId: String): ProgressDates? = dao.getSong(songId)?.let {
        ProgressDates(startedAt = it.startedAt, starEarnedAt = it.starEarnedAt, lastAttemptAt = it.lastAttemptAt)
    }

    /**
     * Saves [progress] for [songId] after an attempt made at [nowMillis]. The start date is set on
     * the first save of a started song and the star date when the star is first earned; neither
     * changes afterwards.
     */
    suspend fun save(songId: String, progress: SongProgress, nowMillis: Long) {
        val previous = dao.getSong(songId)
        val summary = SongProgressEntity(
            songId = songId,
            startedAt = previous?.startedAt ?: nowMillis.takeIf { progress.started },
            currentPhraseIndex = progress.currentPhraseIndex,
            highestUnlockedIndex = progress.highestUnlockedIndex,
            completedPhraseCount = progress.completedPhraseCount,
            attemptCount = progress.attempts,
            overallScore = progress.overallScore,
            completed = progress.completed,
            starEarned = progress.starEarned,
            starEarnedAt = previous?.starEarnedAt ?: nowMillis.takeIf { progress.starEarned },
            lastAttemptAt = if (progress.attempts > (previous?.attemptCount ?: 0)) nowMillis else previous?.lastAttemptAt,
        )
        val phrases = progress.phrases.mapIndexed { index, phrase ->
            PhraseProgressEntity(
                songId = songId,
                phraseIndex = index,
                noteCount = phrase.noteCount,
                bestScore = phrase.bestScore,
                latestScore = phrase.latestScore,
                attemptCount = phrase.attempts,
                unlocked = phrase.unlocked,
            )
        }
        dao.save(summary, phrases)
    }

    private fun toSongProgress(song: Song, saved: List<PhraseProgressEntity>, summary: SongProgressEntity?): SongProgress {
        val byIndex = saved.associateBy { it.phraseIndex }
        val phrases = song.phrases.map { phrase ->
            val row = byIndex[phrase.index]
            PhraseProgress(
                noteCount = phrase.noteCount,
                unlocked = phrase.index == 0 || row?.unlocked == true,
                bestScore = row?.bestScore,
                latestScore = row?.latestScore,
                attempts = row?.attemptCount ?: 0,
            )
        }
        return SongProgress(phrases, starEarned = summary?.starEarned == true)
    }
}

data class ProgressDates(val startedAt: Long?, val starEarnedAt: Long?, val lastAttemptAt: Long?)

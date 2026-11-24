package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lesson attempts count towards progress; free-practice attempts never do (#64). */
enum class AttemptMode { Lesson, FreePractice }

/** The score and its components, as computed when the attempt was analysed. */
data class AttemptScores(
    val score: Int,
    val rawScore: Double,
    val noteAccuracy: Double,
    val orderAccuracy: Double,
    val timingAccuracy: Double,
    val tempoAccuracy: Double,
    val tempoRatio: Double?,
)

/**
 * One saved attempt (spec §13): everything needed to show its results again later, exactly as
 * they were, even if the scoring or detection code changes afterwards.
 *
 * @property songId null for free practice, which isn't tied to a song.
 * @property phraseIndex 0-based phrase within [songId], or null for free practice.
 * @property createdAt milliseconds since the Unix epoch.
 * @property referenceWavPath path relative to the app's storage folder (#20), e.g.
 *   `reference/twinkle_p1_….wav`, or null when there was no reference.
 * @property userWavPath relative path of the student's recording, e.g. `recordings/….wav`.
 * @property analysisVersion which version of the analysis produced [detectedNotes] (#37).
 */
data class Attempt(
    val id: Long,
    val songId: String?,
    val phraseIndex: Int?,
    val createdAt: Long,
    val mode: AttemptMode,
    val expectedNotes: List<Pitch>,
    val detectedNotes: List<DetectedNote>,
    val referenceWavPath: String?,
    val userWavPath: String?,
    val scores: AttemptScores,
    val feedback: PhraseFeedback,
    val analysisVersion: Int,
)

/** An attempt about to be saved; the database assigns its [Attempt.id]. */
data class NewAttempt(
    val songId: String?,
    val phraseIndex: Int?,
    val createdAt: Long,
    val mode: AttemptMode,
    val expectedNotes: List<Pitch>,
    val detectedNotes: List<DetectedNote>,
    val referenceWavPath: String?,
    val userWavPath: String?,
    val scores: AttemptScores,
    val feedback: PhraseFeedback,
    val analysisVersion: Int,
) {
    init {
        require((songId == null) == (phraseIndex == null)) { "An attempt has both a song and a phrase, or neither" }
        require(mode == AttemptMode.FreePractice || songId != null) { "A lesson attempt needs a song and phrase" }
        listOfNotNull(referenceWavPath, userWavPath).forEach { path ->
            require(!path.startsWith("/") && !path.contains(":\\")) { "WAV paths must be relative to the app's storage folder, but got \"$path\"" }
        }
    }
}

/**
 * The stored form of an attempt. Rows are only ever inserted, never updated, so every attempt is
 * kept separately. Note lists and feedback are stored as JSON.
 */
@Entity(tableName = "attempt", indices = [Index("songId", "createdAt")])
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String?,
    val phraseIndex: Int?,
    val createdAt: Long,
    val mode: String,
    val expectedNotesJson: String,
    val detectedNotesJson: String,
    val referenceWavPath: String?,
    val userWavPath: String?,
    val score: Int,
    val rawScore: Double,
    val noteAccuracy: Double,
    val orderAccuracy: Double,
    val timingAccuracy: Double,
    val tempoAccuracy: Double,
    val tempoRatio: Double?,
    val feedbackJson: String,
    val analysisVersion: Int,
)

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: AttemptEntity): Long

    @Query("SELECT * FROM attempt WHERE id = :id")
    suspend fun get(id: Long): AttemptEntity?

    @Query("SELECT * FROM attempt ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempt WHERE songId = :songId ORDER BY createdAt DESC, id DESC")
    fun observeForSong(songId: String): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempt WHERE songId = :songId AND phraseIndex = :phraseIndex ORDER BY createdAt DESC, id DESC")
    fun observeForPhrase(songId: String, phraseIndex: Int): Flow<List<AttemptEntity>>
}

/**
 * Saved attempts (spec §13). Attempts are only ever added, never changed, so history shows each
 * one exactly as it was.
 */
class AttemptRepository(private val dao: AttemptDao) : AttemptHistory {

    /** Saves [attempt] and returns its id. */
    suspend fun insert(attempt: NewAttempt): Long = dao.insert(attempt.toEntity())

    override suspend fun get(id: Long): Attempt? = dao.get(id)?.toAttempt()

    /** Every attempt, newest first. */
    override fun observeAll(): Flow<List<Attempt>> = dao.observeAll().map { rows -> rows.map { it.toAttempt() } }

    /** Attempts on one song, newest first. */
    override fun observeForSong(songId: String): Flow<List<Attempt>> = dao.observeForSong(songId).map { rows -> rows.map { it.toAttempt() } }

    /** Attempts on one phrase, newest first, for comparing with earlier attempts (#63). */
    override fun observeForPhrase(songId: String, phraseIndex: Int): Flow<List<Attempt>> =
        dao.observeForPhrase(songId, phraseIndex).map { rows -> rows.map { it.toAttempt() } }
}

@Serializable
private data class StoredNote(
    val midi: Int?,
    val startMs: Double,
    val endMs: Double,
    val confidence: Double,
    val uncertain: Boolean,
    val cents: Double? = null,
)

@Serializable
private data class StoredFeedback(val summary: String, val wentWell: List<String>, val toImprove: List<String>)

private val json = Json { ignoreUnknownKeys = true }

private fun NewAttempt.toEntity() = AttemptEntity(
    songId = songId,
    phraseIndex = phraseIndex,
    createdAt = createdAt,
    mode = mode.name,
    expectedNotesJson = json.encodeToString(expectedNotes.map { it.midi }),
    detectedNotesJson = json.encodeToString(
        detectedNotes.map { StoredNote(it.pitch?.midi, it.startMs, it.endMs, it.confidence, it.uncertain, it.cents) },
    ),
    referenceWavPath = referenceWavPath,
    userWavPath = userWavPath,
    score = scores.score,
    rawScore = scores.rawScore,
    noteAccuracy = scores.noteAccuracy,
    orderAccuracy = scores.orderAccuracy,
    timingAccuracy = scores.timingAccuracy,
    tempoAccuracy = scores.tempoAccuracy,
    tempoRatio = scores.tempoRatio,
    feedbackJson = json.encodeToString(StoredFeedback(feedback.summary, feedback.wentWell, feedback.toImprove)),
    analysisVersion = analysisVersion,
)

private fun AttemptEntity.toAttempt(): Attempt {
    val feedback = json.decodeFromString<StoredFeedback>(feedbackJson)
    return Attempt(
        id = id,
        songId = songId,
        phraseIndex = phraseIndex,
        createdAt = createdAt,
        mode = AttemptMode.valueOf(mode),
        expectedNotes = json.decodeFromString<List<Int>>(expectedNotesJson).map(::Pitch),
        detectedNotes = json.decodeFromString<List<StoredNote>>(detectedNotesJson).map {
            DetectedNote(it.midi?.let(::Pitch), it.startMs, it.endMs, it.confidence, it.uncertain, it.cents)
        },
        referenceWavPath = referenceWavPath,
        userWavPath = userWavPath,
        scores = AttemptScores(score, rawScore, noteAccuracy, orderAccuracy, timingAccuracy, tempoAccuracy, tempoRatio),
        feedback = PhraseFeedback(feedback.summary, feedback.wentWell, feedback.toImprove),
        analysisVersion = analysisVersion,
    )
}

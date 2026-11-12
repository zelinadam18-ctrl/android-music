package io.github.rudicilibrasi.pianotrainer

import androidx.room.RoomDatabase
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.AudioRecorder
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import okio.Path.Companion.toPath
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.database.AttemptRepository
import io.github.rudicilibrasi.pianotrainer.database.PianoDatabase
import io.github.rudicilibrasi.pianotrainer.database.ProgressReader
import io.github.rudicilibrasi.pianotrainer.database.ProgressRepository
import io.github.rudicilibrasi.pianotrainer.database.RecordAttempt
import io.github.rudicilibrasi.pianotrainer.database.RecordFreePractice
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import io.github.rudicilibrasi.pianotrainer.database.buildPianoDatabase

/**
 * Creates the app's long-lived objects (database, repositories, use cases, …) once at start-up
 * and hands them to screens. Kept as plain constructor wiring; it can move to a DI library such as
 * Koin if it grows unwieldy.
 *
 * Create exactly one per process: on Android from the `Application`, on iOS once in
 * `MainViewController`.
 *
 * @param databaseBuilder the platform's database builder: `databaseBuilder(context)` on Android,
 *   `databaseBuilder()` on iOS.
 * @param audioPlayer the platform's player: `AndroidAudioPlayer` or `IosAudioPlayer`.
 * @param audioRecorder the platform's recorder: `AndroidAudioRecorder` or `IosAudioRecorder`.
 * @param openAppSettings opens this app's page in the system Settings, e.g. to allow the microphone.
 * @param storageRoot the app's private folder for WAV files: Android `filesDir`, iOS Application Support.
 */
class AppContainer(
    databaseBuilder: RoomDatabase.Builder<PianoDatabase>,
    val audioPlayer: AudioPlayer,
    val audioRecorder: AudioRecorder,
    val openAppSettings: () -> Unit = {},
    storageRoot: String,
) {
    /** WAV files under `reference/` and `recordings/`, always addressed by relative path. */
    val storage: AppStorage = AppStorage(storageRoot.toPath())

    /** Each phrase's reference audio, rendered from the song data and cached under `reference/` (#21). */
    val referenceAudio: ReferenceAudioCache = ReferenceAudioCache(storage)

    val songCatalog: SongCatalog = SongCatalog.bundled()

    private val database: PianoDatabase by lazy { buildPianoDatabase(databaseBuilder) }
    private val attemptRepository by lazy { AttemptRepository(database.attemptDao()) }
    private val progressRepository by lazy { ProgressRepository(database.progressDao()) }

    /** Read-only progress for screens; only [recordAttempt] changes it. */
    val progress: ProgressReader get() = progressRepository

    /** Read-only attempts for history screens. */
    val attemptHistory: AttemptHistory get() = attemptRepository

    val startLesson: StartLesson by lazy { StartLesson(progressRepository) }

    val recordAttempt: RecordAttempt by lazy { RecordAttempt(database, attemptRepository, progressRepository) }

    /** Saves Free Practice attempts; never touches progress. */
    val recordFreePractice: RecordFreePractice by lazy { RecordFreePractice(attemptRepository) }
}

package io.github.rudicilibrasi.pianotrainer

import androidx.room.RoomDatabase
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
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
 */
class AppContainer(databaseBuilder: RoomDatabase.Builder<PianoDatabase>) {
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

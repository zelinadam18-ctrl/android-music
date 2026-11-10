package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * The database in the app's Application Support folder, which iOS keeps (and backs up) but
 * doesn't show to the user. Pass the result to [buildPianoDatabase].
 */
fun databaseBuilder(): RoomDatabase.Builder<PianoDatabase> =
    Room.databaseBuilder<PianoDatabase>(name = applicationSupportDirectory() + "/" + PianoDatabase.FILE_NAME)

@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportDirectory(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "Application Support directory is unavailable" }
}

package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory

@OptIn(ExperimentalForeignApi::class)
actual class TestDatabaseFile actual constructor() {
    private val dir = NSTemporaryDirectory() + "piano-db-" + NSProcessInfo.processInfo.globallyUniqueString

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    actual fun builder(): RoomDatabase.Builder<PianoDatabase> =
        Room.databaseBuilder<PianoDatabase>(name = "$dir/${PianoDatabase.FILE_NAME}")

    actual fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }
}

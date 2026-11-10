package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.Room
import androidx.room.RoomDatabase
import org.junit.Assume
import java.io.File
import java.nio.file.Files

actual class TestDatabaseFile actual constructor() {
    private val dir: File = Files.createTempDirectory("piano-db").toFile()

    actual fun builder(): RoomDatabase.Builder<PianoDatabase> {
        // The bundled SQLite driver ships JVM natives for Linux, Windows and Apple-silicon Macs,
        // but not Intel Macs. CI runs on Linux, so skip there rather than fail.
        Assume.assumeFalse(
            "Bundled SQLite has no JVM binary for Intel Macs; these tests run in CI on Linux",
            System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64"),
        )
        return Room.databaseBuilder<PianoDatabase>(name = File(dir, PianoDatabase.FILE_NAME).absolutePath)
    }

    actual fun delete() {
        dir.deleteRecursively()
    }
}

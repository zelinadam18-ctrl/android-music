package io.github.rudicilibrasi.pianotrainer.data

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppStorageTest {

    private val fileSystem = FakeFileSystem()
    private val root = "/app/files".toPath()
    private fun storage() = AppStorage(root, fileSystem)

    @Test
    fun writeReadAndDeleteARecording() {
        val storage = storage()
        val path = "recordings/2026-10-25T17-03-12_twinkle_p3.wav"
        assertTrue(storage.write(path, byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<StoredFile.Found>(storage.read(path)).bytes)
        assertTrue(storage.exists(path))
        assertEquals(listOf(path), storage.list(AppStorage.RECORDINGS))
        assertTrue(storage.delete(path))
        assertEquals(StoredFile.Missing, storage.read(path))
        assertFalse(storage.exists(path))
    }

    @Test
    fun aFileSavedBeforeARestartIsFoundAfterIt() {
        storage().write("reference/twinkle_p1.wav", byteArrayOf(9))
        // A new AppStorage over the same folder, as after the app restarts.
        assertContentEquals(byteArrayOf(9), assertIs<StoredFile.Found>(storage().read("reference/twinkle_p1.wav")).bytes)
    }

    @Test
    fun overwritingLeavesNoPartialFileBehind() {
        val storage = storage()
        storage.write("recordings/a.wav", byteArrayOf(1))
        storage.write("recordings/a.wav", byteArrayOf(2, 2))
        assertContentEquals(byteArrayOf(2, 2), assertIs<StoredFile.Found>(storage.read("recordings/a.wav")).bytes)
        assertEquals(listOf("recordings/a.wav"), storage.list("recordings"))
    }

    @Test
    fun aMissingFileIsAResultNotACrash() {
        assertEquals(StoredFile.Missing, storage().read("recordings/never.wav"))
        assertEquals(emptyList(), storage().list("recordings"))
    }

    @Test
    fun anUnreadableFileIsAResultNotACrash() {
        val storage = storage()
        // A folder where a file was expected can't be read as one.
        fileSystem.createDirectories(root / "recordings/oops.wav")
        assertIs<StoredFile.Unreadable>(storage.read("recordings/oops.wav"))
    }

    @Test
    fun onlyRelativePathsInsideStorageAreAccepted() {
        val storage = storage()
        for (bad in listOf("/etc/passwd", "../outside.wav", "recordings/../../x.wav", "C:\\x.wav", "", "recordings//x.wav")) {
            assertFalse(AppStorage.isRelative(bad), bad)
            assertFailsWith<IllegalArgumentException>(bad) { storage.read(bad) }
        }
        assertTrue(AppStorage.isRelative("recordings/2026-10-25T17-03-12_twinkle_p3.wav"))
        assertEquals("/app/files/recordings/x.wav", storage.absolute("recordings/x.wav"))
    }

    @Test
    fun recordingPathsSayWhenAndWhat() {
        assertEquals("recordings/2026-10-25T17-03-12_twinkle_p3.wav", AppStorage.recordingPath("2026-10-25T17:03:12.512Z", "twinkle", 3))
        assertEquals("recordings/2026-10-25T17-03-12_free.wav", AppStorage.recordingPath("2026-10-25T17:03:12Z", null, null))
        assertEquals("recordings/2026-10-25T17-03-12_twinkle_p3-2.wav", AppStorage.recordingPath("2026-10-25T17:03:12Z", "twinkle", 3, suffix = 2))
        assertTrue(AppStorage.isRelative(AppStorage.recordingPath("2026-10-25T17:03:12Z", "a/b", 1)), "song ids can't escape the folder")
        assertEquals("reference/twinkle_p1_ab12.wav", AppStorage.referencePath("twinkle_p1_ab12"))
    }
}

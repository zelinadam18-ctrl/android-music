package io.github.rudicilibrasi.pianotrainer.database

import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A builder for a fresh database file in a temporary folder, and a way to delete it. */
expect class TestDatabaseFile() {
    fun builder(): RoomDatabase.Builder<PianoDatabase>
    fun delete()
}

class PianoDatabaseTest {

    private val file = TestDatabaseFile()

    @AfterTest
    fun cleanUp() = file.delete()

    @Test
    fun anEmptyDatabaseOpens() = runTest {
        val database = buildPianoDatabase(file.builder())
        assertNull(database.metadataDao().get("anything"))
        database.close()
    }

    @Test
    fun anEntityRoundTrips() = runTest {
        val database = buildPianoDatabase(file.builder())
        val dao = database.metadataDao()
        dao.put(MetadataEntity("created", "2026-11-09"))
        assertEquals("2026-11-09", dao.get("created"))
        assertEquals("2026-11-09", dao.observe("created").first())

        dao.put(MetadataEntity("created", "changed"))
        assertEquals("changed", dao.get("created"))

        dao.delete("created")
        assertNull(dao.get("created"))
        database.close()
    }

    @Test
    fun dataSurvivesClosingAndReopening() = runTest {
        val first = buildPianoDatabase(file.builder())
        first.metadataDao().put(MetadataEntity("kept", "yes"))
        first.close()

        val second = buildPianoDatabase(file.builder())
        assertEquals("yes", second.metadataDao().get("kept"))
        second.close()
    }
}

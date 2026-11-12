package io.github.rudicilibrasi.pianotrainer.data

import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferenceAudioCacheTest {

    private val fileSystem = FakeFileSystem()
    private val storage = AppStorage("/app/files".toPath(), fileSystem)

    private fun phrase(events: String, index: Int = 0) = Phrase(index, SongFormat.parseEvents(events), 0.0)
    private val twinkle1 = phrase("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2")

    @Test
    fun theFilesDurationMatchesThePhrasePlusTheReleaseTail() {
        val reference = ReferenceAudioCache(storage).reference("twinkle", twinkle1, 90.0)
        val clip = Wav.decode(reference.wav)
        val seconds = clip.samples.size.toDouble() / clip.sampleRate
        // 8 beats at 90 BPM is 5.333 s, plus the synthesizer's 80 ms release.
        assertTrue(abs(seconds - (8 * 60.0 / 90 + 0.08)) < 0.01, "$seconds s")
        assertTrue(reference.path.startsWith("reference/twinkle_p1_"))
        assertTrue(AppStorage.isRelative(reference.path))
        // Quarter notes at 90 BPM start every 666.67 ms.
        assertEquals(7, reference.noteStartsMs.size)
        reference.noteStartsMs.forEachIndexed { i, start -> assertTrue(abs(start - i * 60_000.0 / 90) < 1e-6, "note $i at $start") }
    }

    @Test
    fun aCachedReferenceIsReusedAcrossRestarts() {
        val first = ReferenceAudioCache(storage)
        val a = first.reference("twinkle", twinkle1, 90.0)
        assertEquals(1, first.renders)
        assertEquals(listOf(a.path), storage.list(AppStorage.REFERENCE))
        // After a restart: a new cache over the same storage reads the file instead of rendering.
        val second = ReferenceAudioCache(storage)
        val b = second.reference("twinkle", twinkle1, 90.0)
        assertEquals(0, second.renders)
        assertContentEquals(a.wav, b.wav)
    }

    @Test
    fun changingANoteInTheSongProducesANewReferenceFile() {
        val cache = ReferenceAudioCache(storage)
        val before = cache.reference("twinkle", twinkle1, 90.0)
        val edited = cache.reference("twinkle", phrase("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 F4/2"), 90.0)
        assertNotEquals(before.path, edited.path)
        assertEquals(2, cache.renders)
        // A different tempo is a different reference too.
        assertNotEquals(before.path, cache.reference("twinkle", twinkle1, 100.0).path)
    }

    @Test
    fun aDeletedOrCorruptCachedFileIsRenderedAgain() {
        val cache = ReferenceAudioCache(storage)
        val reference = cache.reference("twinkle", twinkle1, 90.0)
        storage.write(reference.path, byteArrayOf(1, 2, 3))
        cache.reference("twinkle", twinkle1, 90.0)
        storage.delete(reference.path)
        val again = cache.reference("twinkle", twinkle1, 90.0)
        assertEquals(3, cache.renders)
        assertContentEquals(reference.wav, again.wav)
    }

    @Test
    fun theKeyIncludesSongPhraseNotesAndSynthesizerVersion() {
        val key = ReferenceAudioCache.key("twinkle", phrase("C4/1", index = 2), 90.0)
        assertTrue(key.startsWith("twinkle_p3_"), key)
        assertTrue(key.endsWith("_v${Synthesizer.VERSION}"), key)
        assertTrue(ReferenceAudioCache.key(null, twinkle1, 80.0).startsWith("free_"))
        assertEquals(key, ReferenceAudioCache.key("twinkle", phrase("C4/1", index = 2), 90.0), "deterministic")
    }
}

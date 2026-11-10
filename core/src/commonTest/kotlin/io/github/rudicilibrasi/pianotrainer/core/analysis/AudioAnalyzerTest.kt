package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioAnalyzerTest {

    private val sampleRate = 44100
    private val twinkle = Phrase(0, SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"), 0.0)

    @Test
    fun analysesAWavFileIntoNotesAndGraphs() = runTest {
        val (samples, expected) = NoteCheck.render(twinkle, 100.0, sampleRate)
        val result = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(Wav.encode(samples, sampleRate))

        assertEquals(AudioAnalyzer.ANALYSIS_VERSION, result.analysisVersion)
        assertEquals(sampleRate, result.sampleRate)
        assertTrue(abs(result.durationSeconds - samples.size.toDouble() / sampleRate) < 1e-3)
        // 16-bit WAV quantisation doesn't change what is detected.
        assertNull(NoteCheck.check(expected, result.notes))
        assertEquals(expected.size, result.onsets.size)
    }

    @Test
    fun graphsAreDownsampledForDisplay() = runTest {
        // 10 seconds would be about 860 STFT frames; graphs keep at most 400 columns.
        val (samples, _) = NoteCheck.render(Phrase(0, SongFormat.parseEvents("C4/4 E4/4 G4/4 C5/4 G4/4"), 0.0), 120.0, sampleRate)
        val graphs = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, sampleRate)).graphs

        assertEquals(AudioAnalyzer.MAX_COLUMNS, graphs.columnCount)
        assertEquals(graphs.columnCount, graphs.spectrogramDb.size)
        assertEquals(graphs.columnCount, graphs.pitchHz.size)
        assertTrue(graphs.rowCount in 1..AudioAnalyzer.MAX_ROWS)
        assertTrue(graphs.rowCount * graphs.rowHz >= AudioAnalyzer.MAX_GRAPH_HZ)
        assertTrue(abs(graphs.columnCount * graphs.columnSeconds - samples.size.toDouble() / sampleRate) < 1e-9)

        // During the first note (C4 from 0.3 s to 2.3 s) the pitch track reads C4 and the level is high.
        val column = (1.0 / graphs.columnSeconds).toInt()
        assertEquals(Pitch.parse("C4"), Pitch.nearest(graphs.pitchHz[column]!!)!!.pitch)
        assertTrue(graphs.amplitudeDb[column] > -30f)
        // The silent lead-in has no pitch and a very low level.
        assertNull(graphs.pitchHz[1])
        assertTrue(graphs.amplitudeDb[1] < -100f)
    }

    @Test
    fun shortRecordingsKeepOneColumnPerFrame() = runTest {
        val graphs = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(FloatArray(sampleRate / 2), sampleRate)).graphs
        assertTrue(graphs.columnCount in 1 until AudioAnalyzer.MAX_COLUMNS)
    }

    @Test
    fun silenceHasNoNotes() = runTest {
        val result = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(FloatArray(sampleRate * 2), sampleRate))
        assertEquals(emptyList(), result.notes)
        assertTrue(result.graphs.pitchHz.all { it == null })
    }

    @Test
    fun reportsProgressFrom0To1() = runTest {
        val (samples, _) = NoteCheck.render(twinkle, 100.0, sampleRate)
        val progress = ArrayList<Float>()
        AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, sampleRate)) { progress += it }
        assertEquals(0f, progress.first())
        assertEquals(1f, progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun canBeCancelled() = runTest {
        val (samples, _) = NoteCheck.render(twinkle, 100.0, sampleRate)
        val job = async(StandardTestDispatcher(testScheduler)) {
            AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, sampleRate))
        }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun rejectsSomethingThatIsNotAWav() = runTest {
        assertFailsWith<IllegalArgumentException> {
            AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(ByteArray(100) { 7 })
        }
    }
}

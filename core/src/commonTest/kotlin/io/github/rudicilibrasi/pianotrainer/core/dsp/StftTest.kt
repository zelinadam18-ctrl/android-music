package io.github.rudicilibrasi.pianotrainer.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

class StftTest {

    private fun sine(frequencyHz: Double, seconds: Double, sampleRate: Int, amplitude: Double = 1.0) =
        FloatArray((seconds * sampleRate).toInt()) { (amplitude * sin(2 * PI * frequencyHz * it / sampleRate)).toFloat() }

    private fun Spectrogram.peakBin(frame: Int): Int = magnitudesDb[frame].indices.maxBy { magnitudesDb[frame][it] }

    @Test
    fun defaultFrameSizeIsAbout46Milliseconds() {
        assertEquals(2048, Stft.defaultFrameSize(44100))
        assertEquals(2048, Stft.defaultFrameSize(48000))
        assertEquals(1024, Stft.defaultFrameSize(22050))
        assertEquals(4096, Stft.defaultFrameSize(96000))
    }

    @Test
    fun findsASineWithinOneBin() {
        for ((frequency, sampleRate) in listOf(1000.0 to 44100, 261.63 to 44100, 784.0 to 48000)) {
            val spectrogram = Stft.compute(sine(frequency, 1.0, sampleRate), sampleRate)
            val middle = spectrogram.frameCount / 2
            val peakHz = spectrogram.binFrequencyHz(spectrogram.peakBin(middle))
            assertTrue(abs(peakHz - frequency) <= spectrogram.binWidthHz, "$frequency Hz at $sampleRate Hz: peak at $peakHz")
        }
    }

    @Test
    fun fullScaleSineIsAboutZeroDecibels() {
        // 1000 Hz is not centred on a bin, so allow for the Hann window's scalloping loss (≤ 1.42 dB).
        val spectrogram = Stft.compute(sine(1000.0, 1.0, 44100), 44100)
        val middle = spectrogram.frameCount / 2
        val peakDb = spectrogram.magnitudesDb[middle][spectrogram.peakBin(middle)]
        assertTrue(peakDb <= 0.01f && peakDb >= -1.5f, "peak was $peakDb dB")

        val quieter = Stft.compute(sine(1000.0, 1.0, 44100, amplitude = 0.1), 44100)
        val quieterDb = quieter.magnitudesDb[middle][quieter.peakBin(middle)]
        assertEquals(peakDb - 20f, quieterDb, absoluteTolerance = 0.01f)
    }

    @Test
    fun silenceIsAtTheFloor() {
        val spectrogram = Stft.compute(FloatArray(10000), 44100)
        for (frame in spectrogram.magnitudesDb) for (value in frame) assertEquals(Stft.FLOOR_DB, value)
    }

    @Test
    fun axesUseHertzAndSeconds() {
        val spectrogram = Stft.compute(FloatArray(44100), 44100)
        assertEquals(2048, spectrogram.frameSize)
        assertEquals(512, spectrogram.hopSize)
        assertEquals(1025, spectrogram.binCount)
        assertEquals(44100.0 / 2048, spectrogram.binFrequencyHz(1), absoluteTolerance = 1e-9)
        assertEquals(22050.0, spectrogram.binFrequencyHz(1024), absoluteTolerance = 1e-9)
        assertEquals(1024.0 / 44100, spectrogram.frameTimeSeconds(0), absoluteTolerance = 1e-12)
        assertEquals(512.0 / 44100, spectrogram.frameTimeSeconds(1) - spectrogram.frameTimeSeconds(0), absoluteTolerance = 1e-12)
    }

    @Test
    fun framesCoverTheWholeSignal() {
        assertEquals(1, Stft.compute(FloatArray(100), 44100).frameCount)
        assertEquals(1, Stft.compute(FloatArray(2048), 44100).frameCount)
        assertEquals(2, Stft.compute(FloatArray(2049), 44100).frameCount)
        assertEquals(2, Stft.compute(FloatArray(2048 + 512), 44100).frameCount)
        val spectrogram = Stft.compute(FloatArray(44100), 44100)
        val lastFrameEnd = (spectrogram.frameCount - 1) * spectrogram.hopSize + spectrogram.frameSize
        assertTrue(lastFrameEnd >= 44100 && lastFrameEnd - spectrogram.hopSize < 44100)
    }

    @Test
    fun rejectsInvalidSizes() {
        assertFailsWith<IllegalArgumentException> { Stft.compute(FloatArray(10), 44100, frameSize = 1000) }
        assertFailsWith<IllegalArgumentException> { Stft.compute(FloatArray(10), 44100, frameSize = 1024, hopSize = 0) }
        assertFailsWith<IllegalArgumentException> { Stft.compute(FloatArray(10), 44100, frameSize = 1024, hopSize = 2048) }
    }

    @Test
    fun tenSecondsOfAudioIsFast() {
        val samples = sine(440.0, 10.0, 44100)
        Stft.compute(samples, 44100) // warm up
        val elapsed = measureTime { Stft.compute(samples, 44100) }
        println("STFT of 10 s at 44.1 kHz took $elapsed")
        // Generous bound so slow CI machines don't fail; phones are checked in #39.
        assertTrue(elapsed.inWholeMilliseconds < 3000, "took $elapsed")
    }
}

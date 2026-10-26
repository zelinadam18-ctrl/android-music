package io.github.rudicilibrasi.pianotrainer.core.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Magnitudes of a signal's short-time Fourier transform, in decibels.
 *
 * `magnitudesDb[frame][bin]` is 0 dB for a full-scale sine centred on that bin, and never lower
 * than [floorDb]. Frame times are the centres of each frame.
 */
class Spectrogram(
    val sampleRate: Int,
    val frameSize: Int,
    val hopSize: Int,
    val magnitudesDb: Array<FloatArray>,
    val floorDb: Float,
) {
    val frameCount: Int get() = magnitudesDb.size
    val binCount: Int get() = frameSize / 2 + 1

    /** Width of one frequency bin in Hz. */
    val binWidthHz: Double get() = sampleRate.toDouble() / frameSize

    fun binFrequencyHz(bin: Int): Double = bin * binWidthHz

    fun frameTimeSeconds(frame: Int): Double = (frame * hopSize + frameSize / 2.0) / sampleRate
}

/** Short-time Fourier transform with a Hann window and overlapping frames. */
object Stft {
    const val FLOOR_DB = -120f

    /** Frame size of about 46 ms (2048 samples at 44.1 kHz), rounded to a power of two. */
    fun defaultFrameSize(sampleRate: Int): Int {
        require(sampleRate > 0) { "Sample rate must be positive, but was $sampleRate" }
        val target = sampleRate * 2048.0 / 44100
        var size = 1
        while (size * 2 <= target) size *= 2
        return if (target - size <= size * 2 - target) size else size * 2
    }

    /**
     * Computes the spectrogram of [samples]. Frames start every [hopSize] samples (75% overlap by
     * default). The last frame is zero-padded so the whole signal is covered.
     */
    fun compute(
        samples: FloatArray,
        sampleRate: Int,
        frameSize: Int = defaultFrameSize(sampleRate),
        hopSize: Int = frameSize / 4,
    ): Spectrogram {
        require(Fft.isPowerOfTwo(frameSize)) { "Frame size must be a power of two, but was $frameSize" }
        require(hopSize in 1..frameSize) { "Hop size must be between 1 and the frame size, but was $hopSize" }

        val window = Window.hann(frameSize)
        // A full-scale sine centred on a bin has magnitude sum(window) / 2 there; call that 0 dB.
        val reference = window.sum() / 2
        val frameCount = if (samples.size <= frameSize) 1 else 1 + (samples.size - frameSize + hopSize - 1) / hopSize

        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        val frames = Array(frameCount) { frame ->
            val start = frame * hopSize
            for (i in 0 until frameSize) {
                val index = start + i
                re[i] = if (index < samples.size) samples[index] * window[i] else 0.0
                im[i] = 0.0
            }
            Fft.transform(re, im)
            FloatArray(frameSize / 2 + 1) { bin ->
                val magnitude = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                max(FLOOR_DB, (20 * log10(magnitude / reference)).toFloat())
            }
        }
        return Spectrogram(sampleRate, frameSize, hopSize, frames, FLOOR_DB)
    }
}

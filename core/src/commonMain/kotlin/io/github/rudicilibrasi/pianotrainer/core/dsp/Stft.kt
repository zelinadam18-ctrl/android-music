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
        // Frames are real, so two at a time share one complex FFT: frame a as the real part,
        // frame b as the imaginary part, separated afterwards by conjugate symmetry.
        val plan = FftPlan(frameSize)
        val bins = frameSize / 2 + 1
        val frames = arrayOfNulls<FloatArray>(frameCount)
        fun level(magnitude: Double) = max(FLOOR_DB, (20 * log10(magnitude / reference)).toFloat())
        var frame = 0
        while (frame < frameCount) {
            val startA = frame * hopSize
            val startB = startA + hopSize
            val hasB = frame + 1 < frameCount
            for (i in 0 until frameSize) {
                val a = startA + i
                val b = startB + i
                re[i] = if (a < samples.size) samples[a] * window[i] else 0.0
                im[i] = if (hasB && b < samples.size) samples[b] * window[i] else 0.0
            }
            plan.transform(re, im)
            val outA = FloatArray(bins)
            val outB = if (hasB) FloatArray(bins) else null
            for (k in 0 until bins) {
                val m = (frameSize - k) and (frameSize - 1)
                // A[k] = (Z[k] + conj(Z[N−k])) / 2,  B[k] = (Z[k] − conj(Z[N−k])) / 2i
                val aRe = (re[k] + re[m]) / 2
                val aIm = (im[k] - im[m]) / 2
                outA[k] = level(sqrt(aRe * aRe + aIm * aIm))
                if (outB != null) {
                    val bRe = (im[k] + im[m]) / 2
                    val bIm = (re[m] - re[k]) / 2
                    outB[k] = level(sqrt(bRe * bRe + bIm * bIm))
                }
            }
            frames[frame] = outA
            if (outB != null) frames[frame + 1] = outB
            frame += 2
        }
        @Suppress("UNCHECKED_CAST")
        return Spectrogram(sampleRate, frameSize, hopSize, frames as Array<FloatArray>, FLOOR_DB)
    }
}

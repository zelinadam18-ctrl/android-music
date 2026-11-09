package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.dsp.Fft
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pitch estimate for one moment of a recording.
 *
 * @property timeSeconds centre of the analysed frame, from the start of the recording.
 * @property frequencyHz fundamental frequency, or null when the frame is unvoiced (silence, noise,
 *   or nothing periodic enough to trust).
 * @property confidence 0–1. How periodic the frame is: 1 − the YIN dip depth at the chosen period.
 *   0 for unvoiced frames.
 */
data class PitchFrame(val timeSeconds: Double, val frequencyHz: Double?, val confidence: Double) {
    val voiced: Boolean get() = frequencyHz != null
}

/**
 * Fundamental-frequency (f0) detection with the YIN algorithm (de Cheveigné & Kawahara, 2002),
 * tuned for piano (spec §9, §10).
 *
 * YIN looks for the period at which the signal best repeats itself, instead of picking the
 * strongest FFT peak, so it isn't fooled by piano notes whose upper partials are louder than the
 * fundamental. For each frame:
 *
 * 1. Difference function `d(τ) = Σ (x[j] − x[j+τ])²` over an integration window, computed with
 *    FFT cross-correlation so long recordings stay fast on phones.
 * 2. Cumulative mean normalised difference `d'(τ) = d(τ) · τ / Σ_{k≤τ} d(k)`.
 * 3. The period is the first local minimum of `d'` below [threshold] whose depth is also close
 *    to the deepest dip in the search range ([octaveTolerance]). That second check stops a
 *    tone with a weak fundamental and a strong second partial from being read an octave too high.
 *    If the signal still repeats clearly better at twice that period, the longer period wins.
 * 4. Parabolic interpolation refines the period to a fraction of a sample.
 *
 * Frames quieter than [silenceRms] are unvoiced without further work. White noise has no deep
 * dip, so it comes out unvoiced too.
 *
 * @param minHz lowest fundamental searched for. 50 Hz is below G1.
 * @param maxHz highest fundamental searched for. 2000 Hz is above B6.
 * @param hopSize samples between frame starts; null picks about 11.6 ms.
 */
class PitchDetector(
    val sampleRate: Int,
    val minHz: Double = 50.0,
    val maxHz: Double = 2000.0,
    val threshold: Double = 0.15,
    val octaveTolerance: Double = 0.05,
    val silenceRms: Double = 0.001,
    hopSize: Int? = null,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive, but was $sampleRate" }
        require(minHz > 0 && maxHz > minHz && maxHz < sampleRate / 2.0) {
            "Search range must satisfy 0 < minHz < maxHz < Nyquist, but was $minHz..$maxHz Hz at $sampleRate Hz"
        }
    }

    /** Smallest and largest lag searched, in samples. */
    private val minLag = floor(sampleRate / maxHz).toInt().coerceAtLeast(2)
    private val maxLag = ceil(sampleRate / minHz).toInt()

    /** Integration window, about 23 ms (1024 samples at 44.1 kHz), and at least one longest period. */
    val windowSize: Int = maxOf((sampleRate * 1024.0 / 44100).roundToInt(), maxLag)

    /** Samples each frame reads: the window plus the longest lag. */
    val frameSize: Int = windowSize + maxLag

    val hopSize: Int = hopSize ?: (sampleRate * 512.0 / 44100).roundToInt().coerceAtLeast(1)

    private val fftSize: Int = run {
        var n = 1
        while (n < frameSize + windowSize) n = n shl 1
        n
    }

    /** Analyses a whole recording, one [PitchFrame] every [hopSize] samples. */
    fun detect(samples: FloatArray): List<PitchFrame> {
        if (samples.isEmpty()) return emptyList()
        val frameCount = if (samples.size <= frameSize) 1 else 1 + (samples.size - frameSize + hopSize - 1) / hopSize
        return List(frameCount) { frame ->
            val start = frame * hopSize
            analyseFrame(samples, start, (start + frameSize / 2.0) / sampleRate)
        }
    }

    /** Analyses one frame of [frameSize] samples starting at [start] (zero-padded past the end). */
    fun detectFrame(samples: FloatArray, start: Int = 0): PitchFrame =
        analyseFrame(samples, start, (start + frameSize / 2.0) / sampleRate)

    private fun analyseFrame(samples: FloatArray, start: Int, timeSeconds: Double): PitchFrame {
        val x = DoubleArray(frameSize) { i -> samples.getOrNull(start + i)?.toDouble() ?: 0.0 }

        var windowEnergy = 0.0
        for (j in 0 until windowSize) windowEnergy += x[j] * x[j]
        if (sqrt(windowEnergy / windowSize) < silenceRms) return PitchFrame(timeSeconds, null, 0.0)

        val d = differenceFunction(x)

        // Cumulative mean normalised difference.
        val cmnd = DoubleArray(maxLag + 1)
        cmnd[0] = 1.0
        var runningSum = 0.0
        for (tau in 1..maxLag) {
            runningSum += d[tau]
            cmnd[tau] = if (runningSum > 0.0) d[tau] * tau / runningSum else 1.0
        }

        var globalMin = Double.MAX_VALUE
        for (tau in minLag..maxLag) if (cmnd[tau] < globalMin) globalMin = cmnd[tau]
        if (globalMin >= threshold) return PitchFrame(timeSeconds, null, 0.0)

        val acceptable = minOf(threshold, globalMin + octaveTolerance)
        var tau = minLag
        while (tau <= maxLag && cmnd[tau] >= acceptable) tau++
        // Walk down to the bottom of this dip.
        while (tau < maxLag && cmnd[tau + 1] < cmnd[tau]) tau++
        tau = preferLongerPeriod(cmnd, tau)

        val period = refine(cmnd, tau)
        val confidence = (1.0 - cmnd[tau]).coerceIn(0.0, 1.0)
        return PitchFrame(timeSeconds, sampleRate / period, confidence)
    }

    /**
     * If the signal repeats clearly better at twice the chosen period, the chosen dip was the
     * second partial of a note with a weak fundamental: move down an octave. Repeats while it
     * keeps improving. Both dips of a clean tone are near 0, so the absolute margin keeps
     * those from dropping an octave.
     */
    private fun preferLongerPeriod(cmnd: DoubleArray, chosen: Int): Int {
        var tau = chosen
        while (true) {
            val centre = 2 * tau
            if (centre + 2 > maxLag) return tau
            var best = centre
            for (candidate in centre - 2..centre + 2) if (cmnd[candidate] < cmnd[best]) best = candidate
            val clearlyBetter = cmnd[best] < cmnd[tau] * SUBHARMONIC_RATIO && cmnd[tau] - cmnd[best] > SUBHARMONIC_MARGIN
            if (!clearlyBetter) return tau
            tau = best
        }
    }

    private companion object {
        const val SUBHARMONIC_RATIO = 0.5
        const val SUBHARMONIC_MARGIN = 0.01
    }

    /**
     * `d(τ) = e(0) + e(τ) − 2·r(τ)` for τ in 0..maxLag, where `r` is the cross-correlation of the
     * window with the frame and `e(τ)` the energy of the window shifted by τ.
     */
    private fun differenceFunction(x: DoubleArray): DoubleArray {
        val aRe = DoubleArray(fftSize)
        val aIm = DoubleArray(fftSize)
        val xRe = DoubleArray(fftSize)
        val xIm = DoubleArray(fftSize)
        for (j in 0 until windowSize) aRe[j] = x[j]
        for (j in x.indices) xRe[j] = x[j]
        Fft.transform(aRe, aIm)
        Fft.transform(xRe, xIm)
        // conj(A) · X, then an inverse FFT via conjugation: ifft(Z) = conj(fft(conj(Z))) / N.
        val zRe = DoubleArray(fftSize)
        val zIm = DoubleArray(fftSize)
        for (k in 0 until fftSize) {
            zRe[k] = aRe[k] * xRe[k] + aIm[k] * xIm[k]
            zIm[k] = -(aRe[k] * xIm[k] - aIm[k] * xRe[k])
        }
        Fft.transform(zRe, zIm)
        val r = DoubleArray(maxLag + 1) { tau -> zRe[tau] / fftSize }

        val prefix = DoubleArray(x.size + 1)
        for (j in x.indices) prefix[j + 1] = prefix[j] + x[j] * x[j]
        val e0 = prefix[windowSize]
        return DoubleArray(maxLag + 1) { tau ->
            val eTau = prefix[tau + windowSize] - prefix[tau]
            (e0 + eTau - 2 * r[tau]).coerceAtLeast(0.0)
        }
    }

    /** Parabolic interpolation around the minimum at [tau]. */
    private fun refine(cmnd: DoubleArray, tau: Int): Double {
        if (tau <= 1 || tau >= maxLag) return tau.toDouble()
        val left = cmnd[tau - 1]
        val mid = cmnd[tau]
        val right = cmnd[tau + 1]
        val denominator = left - 2 * mid + right
        if (denominator <= 0.0) return tau.toDouble()
        val shift = 0.5 * (left - right) / denominator
        return tau + shift.coerceIn(-0.5, 0.5)
    }
}

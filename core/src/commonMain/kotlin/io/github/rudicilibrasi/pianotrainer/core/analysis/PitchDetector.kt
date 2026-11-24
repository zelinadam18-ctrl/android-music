package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.dsp.FftPlan
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

    /**
     * Cross-correlation length. The window (length [windowSize]) is correlated with the frame for
     * lags up to [maxLag], so indices reach at most `windowSize − 1 + maxLag = frameSize − 1`:
     * a circular correlation of at least [frameSize] points never wraps.
     */
    private val fftSize: Int = run {
        var n = 1
        while (n < frameSize) n = n shl 1
        n
    }

    /**
     * Buffers reused across the frames of one [detect] call. Two slots, because the correlations
     * of two frames are computed with one inverse FFT.
     */
    private inner class Workspace {
        val plan = FftPlan(fftSize)
        val x = Array(2) { DoubleArray(frameSize) }
        val re = Array(2) { DoubleArray(fftSize) }
        val im = Array(2) { DoubleArray(fftSize) }
        val prefix = DoubleArray(frameSize + 1)
        val d = DoubleArray(maxLag + 1)
        val cmnd = DoubleArray(maxLag + 1)
    }

    /** Analyses a whole recording, one [PitchFrame] every [hopSize] samples. */
    fun detect(samples: FloatArray): List<PitchFrame> {
        if (samples.isEmpty()) return emptyList()
        val frameCount = if (samples.size <= frameSize) 1 else 1 + (samples.size - frameSize + hopSize - 1) / hopSize
        val workspace = Workspace()
        val frames = arrayOfNulls<PitchFrame>(frameCount)
        fun timeOf(frame: Int) = (frame * hopSize + frameSize / 2.0) / sampleRate
        var pending = -1 // a sounding frame waiting in slot 0 for a partner
        for (frame in 0 until frameCount) {
            val slot = if (pending < 0) 0 else 1
            if (!load(samples, frame * hopSize, workspace.x[slot])) {
                frames[frame] = PitchFrame(timeOf(frame), null, 0.0)
                continue
            }
            correlationSpectrum(workspace, slot)
            if (slot == 0) {
                pending = frame
                continue
            }
            inverseBoth(workspace)
            frames[pending] = pitchOf(workspace, 0, timeOf(pending))
            frames[frame] = pitchOf(workspace, 1, timeOf(frame))
            pending = -1
        }
        if (pending >= 0) {
            workspace.plan.transform(workspace.re[0], workspace.im[0])
            frames[pending] = pitchOf(workspace, 0, timeOf(pending))
        }
        @Suppress("UNCHECKED_CAST")
        return (frames as Array<PitchFrame>).asList()
    }

    /** Analyses one frame of [frameSize] samples starting at [start] (zero-padded past the end). */
    fun detectFrame(samples: FloatArray, start: Int = 0): PitchFrame {
        val timeSeconds = (start + frameSize / 2.0) / sampleRate
        val workspace = Workspace()
        if (!load(samples, start, workspace.x[0])) return PitchFrame(timeSeconds, null, 0.0)
        correlationSpectrum(workspace, 0)
        workspace.plan.transform(workspace.re[0], workspace.im[0])
        return pitchOf(workspace, 0, timeSeconds)
    }

    /** Copies the frame at [start] into [x] (zero-padded past the end). False if it is silent. */
    private fun load(samples: FloatArray, start: Int, x: DoubleArray): Boolean {
        for (i in 0 until frameSize) x[i] = if (start + i < samples.size) samples[start + i].toDouble() else 0.0
        var windowEnergy = 0.0
        for (j in 0 until windowSize) windowEnergy += x[j] * x[j]
        return sqrt(windowEnergy / windowSize) >= silenceRms
    }

    /**
     * Combines the correlation spectra in both slots so one forward FFT inverts both. Each
     * correlation is real, so with Y = P₀ + i·P₁, ifft(Y) = r₀ + i·r₁. The slots hold conj(P₀) and
     * conj(P₁); the forward FFT of conj(Y) = conj(P₀) − i·conj(P₁) is N·conj(r₀ + i·r₁), so
     * afterwards slot 0's real part is N·r₀ and minus its imaginary part is N·r₁, which is copied
     * to slot 1's real part.
     */
    private fun inverseBoth(workspace: Workspace) {
        val re0 = workspace.re[0]
        val im0 = workspace.im[0]
        val re1 = workspace.re[1]
        val im1 = workspace.im[1]
        for (k in 0 until fftSize) {
            val newRe = re0[k] + im1[k]
            val newIm = im0[k] - re1[k]
            re0[k] = newRe
            im0[k] = newIm
        }
        workspace.plan.transform(re0, im0)
        for (tau in 0..maxLag) re1[tau] = -im0[tau]
    }

    /** The pitch of the frame in [slot], whose real part now holds N times its correlation. */
    private fun pitchOf(workspace: Workspace, slot: Int, timeSeconds: Double): PitchFrame {
        val d = differenceFunction(workspace, slot)

        // Cumulative mean normalised difference.
        val cmnd = workspace.cmnd
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
     * Fills [slot] with conj(P), where P is the spectrum of the cross-correlation `r` of the
     * window with the frame, ready for an inverse FFT done as a forward one:
     * ifft(P) = conj(fft(conj(P))) / N.
     */
    private fun correlationSpectrum(workspace: Workspace, slot: Int) {
        val x = workspace.x[slot]
        val re = workspace.re[slot]
        val im = workspace.im[slot]
        val n = fftSize
        // Both inputs are real, so one complex FFT carries both: the window a as the real part,
        // the frame x as the imaginary part.
        for (j in 0 until n) {
            re[j] = if (j < windowSize) x[j] else 0.0
            im[j] = if (j < frameSize) x[j] else 0.0
        }
        workspace.plan.transform(re, im)
        // Separate them by conjugate symmetry, A[k] = (Z[k] + conj(Z[N−k])) / 2 and
        // X[k] = (Z[k] − conj(Z[N−k])) / 2i, and form conj(Z′) where Z′ = conj(A) · X, so a
        // forward FFT gives the inverse: ifft(Z′) = conj(fft(conj(Z′))) / N. Pairs k and N − k are
        // done together because each needs the other's original values.
        for (k in 0..n / 2) {
            val m = (n - k) and (n - 1)
            val zkRe = re[k]; val zkIm = im[k]
            val zmRe = re[m]; val zmIm = im[m]
            fun product(pRe: Double, pIm: Double, qRe: Double, qIm: Double, out: Int) {
                // A = (p + conj(q)) / 2, X = (p − conj(q)) / 2i, for p = Z[k], q = Z[N−k].
                val aRe = (pRe + qRe) / 2
                val aIm = (pIm - qIm) / 2
                val xRe = (pIm + qIm) / 2
                val xIm = (qRe - pRe) / 2
                // conj(A) · X, then conjugated.
                re[out] = aRe * xRe + aIm * xIm
                im[out] = -(aRe * xIm - aIm * xRe)
            }
            product(zkRe, zkIm, zmRe, zmIm, k)
            if (m != k) product(zmRe, zmIm, zkRe, zkIm, m)
        }
    }

    /**
     * `d(τ) = e(0) + e(τ) − 2·r(τ)` for τ in 0..maxLag, where `r` is the cross-correlation of the
     * window with the frame (slot's real part holds N·r) and `e(τ)` the energy of the window
     * shifted by τ.
     */
    private fun differenceFunction(workspace: Workspace, slot: Int): DoubleArray {
        val x = workspace.x[slot]
        val re = workspace.re[slot]
        val n = fftSize
        val prefix = workspace.prefix
        for (j in 0 until frameSize) prefix[j + 1] = prefix[j] + x[j] * x[j]
        val e0 = prefix[windowSize]
        val d = workspace.d
        for (tau in 0..maxLag) {
            val eTau = prefix[tau + windowSize] - prefix[tau]
            d[tau] = (e0 + eTau - 2 * re[tau] / n).coerceAtLeast(0.0)
        }
        return d
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

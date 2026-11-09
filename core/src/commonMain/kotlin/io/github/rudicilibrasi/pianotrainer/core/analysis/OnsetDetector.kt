package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.dsp.Stft
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One place where a note starts.
 *
 * @property timeSeconds onset time from the start of the recording.
 * @property strength the spectral-flux peak, relative to the strongest onset in the recording (0–1].
 */
data class Onset(val timeSeconds: Double, val strength: Double)

/**
 * Finds where each note starts with spectral-flux onset detection (spec §10).
 *
 * Unlike a detector that only looks for pitch changes, this finds repeated notes of the same
 * pitch (C4 — C4 at the start of Twinkle, E4 — E4 — E4 in Jingle Bells), because every new
 * hammer strike makes the spectrum jump up again even when the pitch doesn't change.
 *
 * 1. Short-time spectrum (Hann window, about 23 ms frames every 5 ms), as log magnitude clipped at
 *    [floorDb] so low-level noise bins don't add up.
 * 2. Spectral flux: for each frame, the sum over frequency bins of the **positive** changes in log
 *    magnitude since the previous frame. Decaying notes give no flux; new notes give a peak.
 * 3. Adaptive threshold: a moving median of the flux over ±[medianWindowSeconds], times
 *    [medianMultiplier], plus [delta] × the largest flux in the recording.
 * 4. Peaks: local maxima above the threshold. Peaks closer than [minGapSeconds] (about 70 ms)
 *    are merged, keeping the stronger one.
 */
class OnsetDetector(
    val sampleRate: Int,
    val floorDb: Double = -80.0,
    val medianWindowSeconds: Double = 0.1,
    val medianMultiplier: Double = 1.5,
    val delta: Double = 0.05,
    val minGapSeconds: Double = 0.07,
) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive, but was $sampleRate" }
    }

    /** About 23 ms (1024 samples at 44.1 kHz), as a power of two. */
    val frameSize: Int = Stft.defaultFrameSize(sampleRate) / 2

    /** About 5 ms. */
    val hopSize: Int = (sampleRate * 0.005).roundToInt().coerceIn(1, frameSize)

    /** Spectral flux per frame; index 0 is always 0. Exposed for graphs and debugging. */
    fun flux(samples: FloatArray): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(0)
        val spectrogram = Stft.compute(samples, sampleRate, frameSize, hopSize)
        val floor = floorDb.toFloat()
        val flux = DoubleArray(spectrogram.frameCount)
        for (t in 1 until spectrogram.frameCount) {
            val previous = spectrogram.magnitudesDb[t - 1]
            val current = spectrogram.magnitudesDb[t]
            var sum = 0.0
            for (bin in current.indices) {
                // dB / 20 is log10 of the magnitude.
                val rise = (max(current[bin], floor) - max(previous[bin], floor)) / 20.0
                if (rise > 0) sum += rise
            }
            flux[t] = sum
        }
        return flux
    }

    /** Time of frame [t]'s flux value: halfway between the centres of frames t − 1 and t. */
    fun fluxTimeSeconds(t: Int): Double = ((t - 0.5) * hopSize + frameSize / 2.0) / sampleRate

    fun detect(samples: FloatArray): List<Onset> {
        val flux = flux(samples)
        val peak = flux.maxOrNull() ?: return emptyList()
        if (peak <= MIN_PEAK_FLUX) return emptyList()

        val radius = (medianWindowSeconds * sampleRate / hopSize).roundToInt().coerceAtLeast(1)
        val minGap = minGapSeconds * sampleRate / hopSize
        val onsets = ArrayList<Pair<Int, Double>>()
        for (t in 1 until flux.size) {
            val value = flux[t]
            if (value < flux[t - 1] || (t + 1 < flux.size && value <= flux[t + 1])) continue
            if (value < medianMultiplier * movingMedian(flux, t, radius) + delta * peak) continue
            val last = onsets.lastOrNull()
            if (last != null && t - last.first < minGap) {
                if (value > last.second) onsets[onsets.lastIndex] = t to value
            } else {
                onsets += t to value
            }
        }
        return onsets.map { (t, value) -> Onset(fluxTimeSeconds(t), value / peak) }
    }

    private fun movingMedian(values: DoubleArray, centre: Int, radius: Int): Double {
        val from = maxOf(0, centre - radius)
        val to = minOf(values.size - 1, centre + radius)
        val window = values.copyOfRange(from, to + 1)
        window.sort()
        return window[window.size / 2]
    }

    private companion object {
        /** Below this, the recording has no onsets at all (silence). */
        const val MIN_PEAK_FLUX = 1e-6
    }
}

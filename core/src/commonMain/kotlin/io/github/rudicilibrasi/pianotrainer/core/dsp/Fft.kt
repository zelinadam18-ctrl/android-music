package io.github.rudicilibrasi.pianotrainer.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Fast Fourier transform (iterative radix-2, pure Kotlin so it runs on every platform). */
object Fft {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /**
     * Transforms the complex signal ([re], [im]) in place. Both arrays must have the same
     * power-of-two length. No scaling is applied.
     */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(im.size == n) { "Real and imaginary parts must have the same length (${re.size} vs ${im.size})" }
        require(isPowerOfTwo(n)) { "FFT length must be a power of two, but was $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re.swap(i, j)
                im.swap(i, j)
            }
        }

        // Butterflies.
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            val half = length / 2
            var start = 0
            while (start < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val tRe = re[b] * wRe - im[b] * wIm
                    val tIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextWRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextWRe
                }
                start += length
            }
            length *= 2
        }
    }

    /**
     * Magnitudes of the non-negative frequency bins (0 to n/2 inclusive) of a real signal whose
     * length is a power of two.
     */
    fun magnitudes(input: DoubleArray): DoubleArray {
        val re = input.copyOf()
        val im = DoubleArray(input.size)
        transform(re, im)
        return DoubleArray(input.size / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    private fun DoubleArray.swap(a: Int, b: Int) {
        val t = this[a]
        this[a] = this[b]
        this[b] = t
    }
}

/**
 * A radix-2 FFT of one fixed size with its bit-reversal and twiddle tables computed once, for
 * code that runs thousands of same-size transforms (STFT frames, pitch frames). Same result as
 * [Fft.transform]. Not shared between threads: create one per analysis.
 */
internal class FftPlan(val size: Int) {
    init {
        require(Fft.isPowerOfTwo(size)) { "FFT length must be a power of two, but was $size" }
    }

    private val reversed = IntArray(size).also { table ->
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            table[i] = j
        }
    }

    /** cos and −sin of 2πk/size for k < size/2. */
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { -sin(2 * PI * it / size) }

    /** Transforms ([re], [im]) in place, no scaling. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = size
        for (i in 1 until n) {
            val j = reversed[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val half = length / 2
            val stride = n / length
            var start = 0
            while (start < n) {
                var t = 0
                for (k in 0 until half) {
                    val wRe = cosTable[t]
                    val wIm = sinTable[t]
                    t += stride
                    val a = start + k
                    val b = a + half
                    val tRe = re[b] * wRe - im[b] * wIm
                    val tIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                }
                start += length
            }
            length *= 2
        }
    }
}

object Window {
    /** Periodic Hann window, the usual choice for overlapping STFT frames. */
    fun hann(size: Int): DoubleArray {
        require(size > 0) { "Window size must be positive, but was $size" }
        return DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    }
}

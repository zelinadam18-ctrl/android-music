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

object Window {
    /** Periodic Hann window, the usual choice for overlapping STFT frames. */
    fun hann(size: Int): DoubleArray {
        require(size > 0) { "Window size must be positive, but was $size" }
        return DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    }
}

package io.github.rudicilibrasi.pianotrainer.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FftTest {

    @Test
    fun matchesADirectDftOnRandomInput() {
        val random = Random(42)
        val n = 64
        val inputRe = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val inputIm = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = inputRe.copyOf()
        val im = inputIm.copyOf()
        Fft.transform(re, im)
        for (k in 0 until n) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                expectedRe += inputRe[t] * cos(angle) - inputIm[t] * sin(angle)
                expectedIm += inputRe[t] * sin(angle) + inputIm[t] * cos(angle)
            }
            assertEquals(expectedRe, re[k], absoluteTolerance = 1e-9, message = "re[$k]")
            assertEquals(expectedIm, im[k], absoluteTolerance = 1e-9, message = "im[$k]")
        }
    }

    @Test
    fun impulseHasAFlatSpectrum() {
        val input = DoubleArray(16).also { it[0] = 1.0 }
        for (magnitude in Fft.magnitudes(input)) assertEquals(1.0, magnitude, absoluteTolerance = 1e-12)
    }

    @Test
    fun sineCentredOnABinPutsAllItsEnergyThere() {
        val n = 1024
        val bin = 37
        val magnitudes = Fft.magnitudes(DoubleArray(n) { sin(2 * PI * bin * it / n) })
        assertEquals(n / 2 + 1, magnitudes.size)
        assertEquals(n / 2.0, magnitudes[bin], absoluteTolerance = 1e-9)
        for (k in magnitudes.indices) if (k != bin) assertEquals(0.0, magnitudes[k], absoluteTolerance = 1e-9)
    }

    @Test
    fun preservesEnergy() {
        // Parseval: sum |x|² = (1/n) · sum |X|².
        val random = Random(7)
        val n = 2048
        val signal = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = signal.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val timeEnergy = signal.sumOf { it * it }
        val frequencyEnergy = (0 until n).sumOf { re[it] * re[it] + im[it] * im[it] } / n
        assertEquals(timeEnergy, frequencyEnergy, absoluteTolerance = 1e-9 * timeEnergy)
    }

    @Test
    fun rejectsLengthsThatAreNotPowersOfTwo() {
        assertFailsWith<IllegalArgumentException> { Fft.transform(DoubleArray(12), DoubleArray(12)) }
        assertFailsWith<IllegalArgumentException> { Fft.transform(DoubleArray(0), DoubleArray(0)) }
        assertFailsWith<IllegalArgumentException> { Fft.transform(DoubleArray(8), DoubleArray(4)) }
    }

    @Test
    fun hannWindowIsZeroAtTheStartAndOneInTheMiddle() {
        val window = Window.hann(8)
        assertEquals(0.0, window[0], absoluteTolerance = 1e-12)
        assertEquals(1.0, window[4], absoluteTolerance = 1e-12)
        assertEquals(window[1], window[7], absoluteTolerance = 1e-12)
    }
}

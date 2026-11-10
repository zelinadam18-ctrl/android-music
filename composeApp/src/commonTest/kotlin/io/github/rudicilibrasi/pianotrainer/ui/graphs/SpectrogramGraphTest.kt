package io.github.rudicilibrasi.pianotrainer.ui.graphs

import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrogramGraphTest {

    @Test
    fun magmaRunsFromNearBlackToPaleYellowGettingBrighter() {
        assertEquals(0xFF000004.toInt(), Magma.argb(0f))
        assertEquals(0xFFFCFDBF.toInt(), Magma.argb(1f))
        assertEquals(Magma.argb(0f), Magma.argb(-1f))
        fun brightness(argb: Int) = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)
        val samples = (0..20).map { brightness(Magma.argb(it / 20f)) }
        assertEquals(samples.sorted(), samples, "brighter as it gets louder")
    }

    @Test
    fun aNoteShowsUpAtItsFundamentalAndPartials() = runTest {
        val a4 = Pitch.parse("A4") // 440 Hz; partials at about 880, 1320, 1760 Hz
        val samples = Synthesizer(44100).render(listOf(SynthNote(a4, 0.5, 1.0)), 2.0)
        val result = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, 44100))
        val model = SpectrogramModel.of(result)

        // 0–2 kHz by default, with Hz labels every 500.
        assertTrue(model.shownHz in 1900.0..2100.0, "shows ${model.shownHz} Hz")
        assertEquals(listOf("0", "500", "1000", "1500"), model.hzTicks.map { it.second }.take(4))
        val hzPerRow = model.shownHz / model.rows

        val during = model.intensity[(1.0 / model.durationSeconds * model.columns).toInt()]
        val before = model.intensity[(0.2 / model.durationSeconds * model.columns).toInt()]
        val fundamental = (440 / hzPerRow).toInt()
        val loudestRow = during.indices.maxBy { during[it] }
        assertTrue(loudestRow in fundamental - 1..fundamental + 1, "loudest row $loudestRow, expected about $fundamental")
        assertTrue(during[(880 / hzPerRow).toInt()] > 0.6f, "second partial visible")
        assertTrue(before.all { it == 0f }, "silence is dark")
        assertTrue(model.intensity.all { column -> column.all { it in 0f..1f } })
    }

    @Test
    fun pixelsPutLowFrequenciesAtTheBottom() = runTest {
        val samples = Synthesizer(44100).render(listOf(SynthNote(Pitch.parse("C4"), 0.0, 0.5)), 0.6)
        val model = SpectrogramModel.of(AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, 44100)))
        val pixels = model.pixels()
        assertEquals(model.columns * model.rows, pixels.size)
        // The bottom-left pixel is row 0 of column 0.
        assertEquals(Magma.argb(model.intensity[0][0]), pixels[(model.rows - 1) * model.columns])
        assertEquals(Magma.argb(model.intensity[0][model.rows - 1]), pixels[0])
    }
}

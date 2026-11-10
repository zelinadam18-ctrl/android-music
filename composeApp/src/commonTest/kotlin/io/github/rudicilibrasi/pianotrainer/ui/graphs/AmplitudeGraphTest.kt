package io.github.rudicilibrasi.pianotrainer.ui.graphs

import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeAxisTest {

    @Test
    fun picksRoundStepsWithAtMostSixTicks() {
        assertEquals(listOf("0 s", "1 s", "2 s", "3 s", "4 s"), TimeAxis.ticks(4.3).map { it.label })
        assertEquals(listOf("0 s", "0.5 s", "1 s", "1.5 s", "2 s"), TimeAxis.ticks(2.2).map { it.label })
        assertEquals(listOf("0 s", "2 s", "4 s", "6 s", "8 s", "10 s"), TimeAxis.ticks(10.4).map { it.label })
        assertTrue(TimeAxis.ticks(119.0).size <= 6)
        assertEquals(listOf("0 s"), TimeAxis.ticks(0.0).map { it.label })
    }

    @Test
    fun labels() {
        assertEquals("0.25 s", TimeAxis.label(0.25))
        assertEquals("1.5 s", TimeAxis.label(1.5))
        assertEquals("12 s", TimeAxis.label(12.0))
    }
}

class AmplitudeGraphTest {

    @Test
    fun noteStartMarksLineUpWithTheNotes() = runTest {
        // Notes at 0.5, 1.5 and 2.5 s in a 3.5 s recording.
        val notes = listOf("C4", "E4", "G4").mapIndexed { i, label -> SynthNote(Pitch.parse(label), 0.5 + i, 0.8) }
        val samples = Synthesizer(44100).render(notes, 3.5)
        val result = AudioAnalyzer(StandardTestDispatcher(testScheduler)).analyze(AudioClip(samples, 44100))
        val model = AmplitudeGraphModel.of(result)

        assertEquals(3, model.onsetXs.size)
        val duration = result.durationSeconds
        model.onsetXs.zip(listOf(0.5, 1.5, 2.5)).forEach { (x, seconds) ->
            assertTrue(abs(x * duration - seconds) < 0.03, "mark at ${x * duration} s for a note at $seconds s")
        }
        // Loud during a note, at the floor in the silence before the first one.
        fun levelAt(seconds: Double) = model.levels.minBy { abs(it.first * duration - seconds) }.second
        assertTrue(levelAt(0.7) > 0.6f, "level ${levelAt(0.7)}")
        assertEquals(0f, levelAt(0.2))
        assertTrue(model.levels.all { it.second in 0f..1f && it.first in 0f..1f })
        assertEquals(model.ticks.size, model.tickXs.size)
    }
}

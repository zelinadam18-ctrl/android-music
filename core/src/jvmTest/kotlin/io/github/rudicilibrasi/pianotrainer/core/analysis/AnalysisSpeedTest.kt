package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * A rough guard against the analysis getting much slower. The real target (#37) is under 2 s for
 * a 10-second recording on a mid-range phone; a desktop JVM should be well under that.
 */
class AnalysisSpeedTest {

    @Test
    fun tenSecondsOfAudioAnalysesQuickly() = runBlocking {
        val phrase = Phrase(0, SongFormat.parseEvents(List(20) { "C4/1 E4/1 G4/1 E4/1" }.joinToString(" ")), 0.0)
        val (samples, _) = NoteCheck.render(phrase, 480.0) // 80 notes in 10 s
        val clip = AudioClip(samples, 44100)
        val analyzer = AudioAnalyzer()
        analyzer.analyze(clip) // warm up the JIT
        // Best of three, so a busy machine (parallel builds, CI neighbours) doesn't fail the test.
        val elapsed = List(3) { measureTime { analyzer.analyze(clip) } }.min()
        println("Analysed ${samples.size / 44100.0} s of audio in $elapsed")
        assertTrue(elapsed.inWholeMilliseconds < 2000, "took $elapsed")
    }
}

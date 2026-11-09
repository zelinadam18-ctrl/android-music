package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks that onsets found in a synthesized phrase match its note starts one for one. */
object OnsetCheck {
    const val TOLERANCE_MS = 30.0

    /** Returns a description of what went wrong, or null if every note start was found. */
    fun check(phrase: Phrase, tempoBpm: Double, sampleRate: Int = 44100, leadInSeconds: Double = 0.3): String? {
        val synthesizer = Synthesizer(sampleRate)
        val notes = phrase.timedEvents(tempoBpm).mapNotNull { timed ->
            timed.event.pitch?.let { SynthNote(it, leadInSeconds + timed.startMs / 1000, timed.durationMs / 1000) }
        }
        val samples = synthesizer.render(notes, leadInSeconds + phrase.durationMs(tempoBpm) / 1000)
        val expectedMs = notes.map { it.startSeconds * 1000 }
        val foundMs = OnsetDetector(sampleRate).detect(samples).map { it.timeSeconds * 1000 }
        if (foundMs.size != expectedMs.size) {
            return "expected ${expectedMs.size} onsets at ${expectedMs.map { it.toInt() }} ms, found ${foundMs.size} at ${foundMs.map { it.toInt() }} ms"
        }
        val off = expectedMs.zip(foundMs).filter { (e, f) -> abs(e - f) > TOLERANCE_MS }
        return if (off.isEmpty()) null else "onsets more than $TOLERANCE_MS ms off (expected → found): ${off.map { (e, f) -> "${e.toInt()}→${f.toInt()}" }}"
    }
}

class OnsetDetectorTest {

    private val sampleRate = 44100

    private fun phrase(events: String) = Phrase(0, SongFormat.parseEvents(events), 0.0)

    private fun assertOnsetsMatch(events: String, tempoBpm: Double) {
        val problem = OnsetCheck.check(phrase(events), tempoBpm, sampleRate)
        assertEquals(null, problem, "\"$events\" at $tempoBpm BPM")
    }

    @Test
    fun separatesRepeatedNotesAtTheStartOfTwinkle() {
        assertOnsetsMatch("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", 100.0)
    }

    @Test
    fun separatesThreeRepeatedNotesInJingleBells() {
        assertOnsetsMatch("E4/1 E4/1 E4/2 E4/1 E4/1 E4/2 E4/1 G4/1 C4/1.5 D4/0.5 E4/4", 120.0)
    }

    @Test
    fun findsNotesInMaryHadALittleLamb() {
        assertOnsetsMatch("E4/1 D4/1 C4/1 D4/1 E4/1 E4/1 E4/2", 100.0)
    }

    @Test
    fun handlesDottedRhythmsAndRests() {
        assertOnsetsMatch("E4/1.5 D4/0.5 D4/2 R/1 G4/1 G4/0.5 G4/0.5 C5/1", 108.0)
    }

    @Test
    fun handlesFastRepeatedEighthNotes() {
        // 250 ms apart at 120 BPM.
        assertOnsetsMatch("G4/0.5 G4/0.5 G4/0.5 G4/0.5 E4/0.5 E4/0.5 C4/1", 120.0)
    }

    @Test
    fun handlesLowAndHighNotes() {
        assertOnsetsMatch("G3/1 G3/1 C6/1 C6/1 A3/1 E5/1", 90.0)
    }

    @Test
    fun silenceHasNoOnsets() {
        assertEquals(emptyList(), OnsetDetector(sampleRate).detect(FloatArray(sampleRate)))
        assertEquals(emptyList(), OnsetDetector(sampleRate).detect(FloatArray(0)))
    }

    @Test
    fun oneSustainedNoteHasOneOnset() {
        val samples = Synthesizer(sampleRate).render(listOf(SynthNote(Pitch.parse("C4"), 0.4, 2.0)), 2.5)
        val onsets = OnsetDetector(sampleRate).detect(samples)
        assertEquals(1, onsets.size, "onsets $onsets")
        assertTrue(abs(onsets[0].timeSeconds - 0.4) < 0.03)
        assertEquals(1.0, onsets[0].strength)
    }

    @Test
    fun onsetsStillFoundOverQuietBackgroundNoise() {
        val random = Random(7)
        val notes = listOf("C4", "C4", "G4", "G4").mapIndexed { i, label -> SynthNote(Pitch.parse(label), 0.3 + i * 0.5, 0.45) }
        val samples = Synthesizer(sampleRate).render(notes, 2.5)
        for (i in samples.indices) samples[i] += (random.nextDouble() * 2 - 1).toFloat() * 0.002f
        val onsets = OnsetDetector(sampleRate).detect(samples)
        assertEquals(4, onsets.size, "onsets $onsets")
        onsets.forEachIndexed { i, onset -> assertTrue(abs(onset.timeSeconds - (0.3 + i * 0.5)) < 0.03, "onset $i at ${onset.timeSeconds}") }
    }

    @Test
    fun worksAtOtherSampleRates() {
        assertEquals(null, OnsetCheck.check(phrase("C4/1 C4/1 G4/1 G4/1"), 100.0, sampleRate = 16000))
    }
}

package io.github.rudicilibrasi.pianotrainer.core.analysis

import io.github.rudicilibrasi.pianotrainer.core.audio.AdditivePianoTone
import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks that the notes detected in a synthesized phrase are exactly the phrase's notes. */
object NoteCheck {
    const val START_TOLERANCE_MS = 30.0
    const val LEAD_IN_SECONDS = 0.3

    fun render(phrase: Phrase, tempoBpm: Double, sampleRate: Int = 44100): Pair<FloatArray, List<SynthNote>> {
        val notes = phrase.timedEvents(tempoBpm).mapNotNull { timed ->
            timed.event.pitch?.let { SynthNote(it, LEAD_IN_SECONDS + timed.startMs / 1000, timed.durationMs / 1000) }
        }
        return Synthesizer(sampleRate).render(notes, LEAD_IN_SECONDS + phrase.durationMs(tempoBpm) / 1000) to notes
    }

    /** Returns what went wrong, or null if the detected notes match exactly. */
    fun check(expected: List<SynthNote>, detected: List<DetectedNote>): String? {
        if (detected.size != expected.size) {
            return "expected ${expected.map { it.pitch }}, detected ${detected.map { it.pitch ?: "?" }}"
        }
        val problems = expected.zip(detected).mapIndexedNotNull { i, (e, d) ->
            when {
                d.pitch != e.pitch -> "note ${i + 1}: expected ${e.pitch}, detected ${d.pitch ?: "?"}"
                d.uncertain -> "note ${i + 1} (${e.pitch}) is uncertain (confidence ${d.confidence})"
                abs(d.startMs - e.startSeconds * 1000) > START_TOLERANCE_MS ->
                    "note ${i + 1} (${e.pitch}) starts at ${d.startMs.toInt()} ms, expected ${(e.startSeconds * 1000).toInt()} ms"
                else -> null
            }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}

class NoteDetectorTest {

    private val sampleRate = 44100
    private val detector = NoteDetector(sampleRate)

    private fun phrase(events: String) = Phrase(0, SongFormat.parseEvents(events), 0.0)

    private fun assertDetectedExactly(events: String, tempoBpm: Double, transform: (FloatArray) -> Unit = {}) {
        val (samples, notes) = NoteCheck.render(phrase(events), tempoBpm, sampleRate)
        transform(samples)
        assertEquals(null, NoteCheck.check(notes, detector.detect(samples)), "\"$events\" at $tempoBpm BPM")
    }

    @Test
    fun detectsTwinkleExactly() = assertDetectedExactly("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", 100.0)

    @Test
    fun detectsJingleBellsExactly() = assertDetectedExactly("E4/1 E4/1 E4/2 E4/1 E4/1 E4/2 E4/1 G4/1 C4/1.5 D4/0.5 E4/4", 120.0)

    @Test
    fun detectsOdeToJoyExactly() = assertDetectedExactly("E4/1 E4/1 F4/1 G4/1 G4/1 F4/1 E4/1 D4/1 C4/1 C4/1 D4/1 E4/1 E4/1.5 D4/0.5 D4/2", 100.0)

    @Test
    fun detectsRestsAndARangeOfOctaves() = assertDetectedExactly("G3/1 R/1 B3/1 D4/1 G4/1 R/0.5 B4/0.5 D5/1 G5/2", 96.0)

    @Test
    fun detectedNotesHaveHighConfidenceAndSmallCents() {
        val (samples, _) = NoteCheck.render(phrase("C4/1 E4/1 G4/1 C5/1"), 100.0, sampleRate)
        val notes = detector.detect(samples)
        assertEquals(4, notes.size)
        for (note in notes) {
            assertTrue(note.confidence > 0.8, "confidence ${note.confidence}")
            assertTrue(abs(note.cents!!) < 10, "cents ${note.cents}")
            assertTrue(note.endMs > note.startMs + 200, "note ${note.startMs}..${note.endMs}")
        }
    }

    @Test
    fun silenceHasNoNotes() {
        assertEquals(emptyList(), detector.detect(FloatArray(sampleRate * 2)))
        assertEquals(emptyList(), detector.detect(FloatArray(0)))
    }

    @Test
    fun aVeryQuietRecordingIsStillDetected() {
        // About −50 dB below the reference: a phone far from the piano.
        assertDetectedExactly("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", 100.0) { samples ->
            for (i in samples.indices) samples[i] *= 0.003f
        }
    }

    @Test
    fun backgroundNoiseIsIgnored() {
        val random = Random(11)
        // White noise about 34 dB below the notes' peak, all the way through the recording.
        assertDetectedExactly("E4/1 D4/1 C4/1 D4/1 E4/1 E4/1 E4/2", 100.0) { samples ->
            for (i in samples.indices) samples[i] += (random.nextDouble() * 2 - 1).toFloat() * 0.016f
        }
    }

    @Test
    fun aSlightlyOutOfTuneNoteIsStillTheSameNote() {
        val buffer = FloatArray(sampleRate * 2)
        val c4 = Pitch.parse("C4")
        AdditivePianoTone().addNote(buffer, sampleRate, c4.frequencyHz * 2.0.pow(30.0 / 1200), startSample = sampleRate / 4, heldSamples = sampleRate)
        val notes = detector.detect(buffer)
        assertEquals(1, notes.size, "notes $notes")
        val note = notes.single()
        assertEquals(c4, note.pitch)
        assertFalse(note.uncertain)
        assertTrue(abs(note.cents!! - 30.0) < 5, "cents ${note.cents}")
    }

    @Test
    fun aWanderingPitchIsUncertain() {
        // A tone sliding from C4 up to G4: no single note to report.
        val start = sampleRate / 4
        val length = sampleRate
        val buffer = FloatArray(sampleRate * 2)
        var phase = 0.0
        for (i in 0 until length) {
            val hz = 261.63 * 2.0.pow(7.0 / 12 * i / length)
            phase += 2 * PI * hz / sampleRate
            val envelope = minOf(1.0, i / 100.0) * minOf(1.0, (length - i) / 2000.0)
            buffer[start + i] = (0.5 * envelope * (sin(phase) + 0.5 * sin(2 * phase))).toFloat()
        }
        val notes = detector.detect(buffer)
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.all { it.uncertain }, "notes $notes")
    }

    @Test
    fun uncertainNotesAreNotReliable() {
        val note = DetectedNote(null, 0.0, 100.0, 0.0, uncertain = true)
        assertFalse(note.isReliable)
        assertNotNull(DetectedNote(Pitch.parse("C4"), 0.0, 100.0, 0.9, uncertain = false).takeIf { it.isReliable })
    }

    @Test
    fun worksAtOtherSampleRates() {
        val rate = 22050
        val (samples, notes) = NoteCheck.render(phrase("C4/1 C4/1 G4/1 G4/1"), 100.0, rate)
        assertEquals(null, NoteCheck.check(notes, NoteDetector(rate).detect(samples)))
    }
}

class NoteDetectorAtStartTest {

    @Test
    fun referenceAudioThatStartsImmediatelyIsDetectedExactly() {
        val phrase = Phrase(0, SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"), 0.0)
        val samples = Synthesizer(44100).renderPhrase(phrase, 100.0)
        val notes = NoteDetector(44100).detect(samples)
        assertEquals(phrase.notes.map { it.pitch }, notes.map { it.pitch })
        assertTrue(notes.none { it.uncertain })
    }
}

package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackTest {

    private val tempoBpm = 120.0 // 500 ms per beat
    private val phrase = Phrase(0, SongFormat.parseEvents("C4/1 D4/1 E4/1 F4/1 G4/1 A4/1 G4/1"), 0.0)
    private val firstPhrase = PhraseContext(isLastPhrase = false, nextPhraseAlreadyUnlocked = false)

    /** Notes played at the given labels and start times; "?" is a note that couldn't be identified. */
    private fun played(vararg notes: Pair<String, Double>) = notes.map { (label, startMs) ->
        if (label == "?") {
            DetectedNote(null, startMs, startMs + 400, 0.2, uncertain = true)
        } else {
            DetectedNote(Pitch.parse(label), startMs, startMs + 400, 0.9, uncertain = false)
        }
    }

    /** One note per label, [msPerNote] apart. */
    private fun played(labels: String, msPerNote: Double = 500.0) =
        played(*labels.split(" ").mapIndexed { i, label -> label to i * msPerNote }.toTypedArray())

    private fun feedback(detected: List<DetectedNote>, context: PhraseContext = firstPhrase): PhraseFeedback =
        FeedbackGenerator.generate(phrase, detected, PhraseScorer.score(phrase, tempoBpm, detected), context)

    @Test
    fun perfectPerformance() {
        assertEquals(
            PhraseFeedback(
                summary = "You scored 100/100. The next phrase is now unlocked.",
                wentWell = listOf(
                    "You played 7 out of 7 notes correctly.",
                    "Your note order was correct.",
                    "Your tempo was close to the reference.",
                    "Your rhythm was steady.",
                ),
                toImprove = emptyList(),
            ),
            feedback(played("C4 D4 E4 F4 G4 A4 G4")),
        )
    }

    @Test
    fun oneWrongNote() {
        val result = feedback(played("C4 D4 E4 E4 G4 A4 G4"))
        assertEquals("You scored 94/100. The next phrase is now unlocked.", result.summary)
        assertEquals("You played 6 out of 7 notes correctly.", result.wentWell.first())
        assertEquals(listOf("You played E4 where F4 was expected (note 4)."), result.toImprove)
    }

    @Test
    fun oneMissedNote() {
        val result = feedback(played("C4" to 0.0, "D4" to 500.0, "F4" to 1500.0, "G4" to 2000.0, "A4" to 2500.0, "G4" to 3000.0))
        assertEquals("You played 6 out of 7 notes correctly.", result.wentWell.first())
        assertEquals(listOf("You missed E4 (note 3)."), result.toImprove)
    }

    @Test
    fun oneExtraNote() {
        val result = feedback(
            played("C4" to 0.0, "D4" to 500.0, "D4" to 750.0, "E4" to 1000.0, "F4" to 1500.0, "G4" to 2000.0, "A4" to 2500.0, "G4" to 3000.0),
        )
        assertEquals("You played 7 out of 7 notes correctly.", result.wentWell.first())
        assertEquals(listOf("You added an extra D4."), result.toImprove)
    }

    @Test
    fun twoSwappedNotesAreReportedOnceAsASwap() {
        val result = feedback(played("C4 D4 F4 E4 G4 A4 G4"))
        // Reported once, not as a missed note plus an extra note.
        assertEquals("Notes 3 and 4 were swapped.", result.toImprove.first())
        assertTrue(result.toImprove.none { "missed" in it || "extra" in it }, "toImprove ${result.toImprove}")
        assertFalse("Your note order was correct." in result.wentWell)
    }

    @Test
    fun playingAQuarterSlower() {
        val result = feedback(played("C4 D4 E4 F4 G4 A4 G4", msPerNote = 625.0))
        assertEquals("You scored 96/100. The next phrase is now unlocked.", result.summary)
        assertEquals(listOf("You played about 25% slower than the reference."), result.toImprove)
        assertTrue("Your rhythm was steady." in result.wentWell)
        assertFalse("Your tempo was close to the reference." in result.wentWell)
    }

    @Test
    fun playingFaster() {
        val result = feedback(played("C4 D4 E4 F4 G4 A4 G4", msPerNote = 350.0))
        assertEquals(listOf("You played about 30% faster than the reference."), result.toImprove)
    }

    @Test
    fun unevenRhythm() {
        val result = feedback(
            played("C4" to 0.0, "D4" to 300.0, "E4" to 1250.0, "F4" to 1350.0, "G4" to 2250.0, "A4" to 2350.0, "G4" to 3000.0),
        )
        assertTrue("Try keeping a more consistent rhythm." in result.toImprove, "toImprove ${result.toImprove}")
        assertFalse("Your rhythm was steady." in result.wentWell)
    }

    @Test
    fun uncertainNotesAreReportedHonestly() {
        val result = feedback(played("C4 D4 E4 F4 ? A4 G4"))
        assertEquals(listOf("We couldn't hear note 5 clearly."), result.toImprove)
        assertEquals("You played 6 out of 7 notes correctly.", result.wentWell.first())
    }

    @Test
    fun emptyRecording() {
        assertEquals(
            PhraseFeedback(
                summary = "You scored 0/100. You need at least 50 to unlock the next phrase.",
                wentWell = emptyList(),
                toImprove = listOf("We didn't hear any notes. Is the phone close to the piano?"),
            ),
            feedback(emptyList()),
        )
    }

    @Test
    fun unlockMessagesFollowTheRoundedScore() {
        val detected = played("C4 D4 E4 F4 G4 A4 G4")
        val score = PhraseScorer.score(phrase, tempoBpm, detected)
        fun summary(points: Int, context: PhraseContext) = FeedbackGenerator.generate(phrase, detected, score.copy(score = points), context).summary

        assertEquals("You scored 48/100. You need at least 50 to unlock the next phrase.", summary(48, firstPhrase))
        assertEquals("You scored 76/100. The next phrase is now unlocked.", summary(76, firstPhrase))
        assertEquals("You scored 50/100. The next phrase is now unlocked.", summary(50, firstPhrase))
        assertEquals("You scored 49/100. You need at least 50 to unlock the next phrase.", summary(49, firstPhrase))

        val replaying = PhraseContext(isLastPhrase = false, nextPhraseAlreadyUnlocked = true)
        assertEquals("You scored 30/100.", summary(30, replaying))
        assertEquals("You scored 80/100. The next phrase is unlocked.", summary(80, replaying))

        val last = PhraseContext(isLastPhrase = true, nextPhraseAlreadyUnlocked = false)
        assertEquals("You scored 92/100. You completed the last phrase of this song.", summary(92, last))
        assertEquals("You scored 40/100. You need at least 50 to complete this phrase.", summary(40, last))
    }

    @Test
    fun noMessageContradictsTheAnalysis() {
        val performances = listOf(
            played("C4 D4 E4 F4 G4 A4 G4"),
            played("G4 A4 G4 F4 E4 D4 C4"),
            played("C4 D4 F4 E4 G4 A4 G4"),
            played("C4 ? E4 F4 B4 A4", msPerNote = 800.0),
            played("C4"),
            played("D5 E5"),
        )
        for (detected in performances) {
            val score = PhraseScorer.score(phrase, tempoBpm, detected)
            val result = FeedbackGenerator.generate(phrase, detected, score, firstPhrase)
            val all = result.wentWell + result.toImprove
            assertTrue(result.summary.startsWith("You scored ${score.score}/100."))
            assertTrue("You played ${score.alignment.correctCount} out of 7 notes correctly." in all, "messages $all")
            if (score.orderAccuracy < 1.0) assertFalse("Your note order was correct." in all)
            if (score.timing.tempoFlagged) assertFalse("Your tempo was close to the reference." in all)
            if (score.timing.tempoRatio == null) assertTrue(all.none { "rhythm" in it || "tempo" in it || "slower" in it || "faster" in it })
            if (score.alignment.isPerfect) assertEquals(emptyList(), result.toImprove)
            assertTrue(result.wentWell.none { it in result.toImprove })
        }
    }
}

package io.github.rudicilibrasi.pianotrainer.core.scoring

import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phrases in the style of the planned songs (#12–#17), until they are bundled; the JVM test
 * BundledSongConsistencyTest runs the same checks over every bundled song.
 */
class ConsistencyTest {

    private fun phrase(events: String, index: Int = 0) = Phrase(index, SongFormat.parseEvents(events), 0.0)

    private val phrases = listOf(
        "C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2" to 100.0, // Twinkle
        "E4/1 D4/1 C4/1 D4/1 E4/1 E4/1 E4/2" to 100.0, // Mary
        "E4/1 E4/1 F4/1 G4/1 G4/1 F4/1 E4/1 D4/1" to 108.0, // Ode to Joy
        "E4/1 E4/1 E4/2 E4/1 E4/1 E4/2" to 120.0, // Jingle Bells
        "G4/0.75 G4/0.25 A4/1 G4/1 C5/1 B4/2" to 96.0, // Happy Birthday: dotted eighth + sixteenth
        "C4/0.25 E4/0.25 G4/0.25 C5/0.25 E5/0.25 G4/0.25 C5/0.25 E5/0.25" to 66.0, // Prelude-style sixteenths
        "D4/1 R/1 F#4/1 A4/1 D5/2" to 90.0, // a rest and a black key
    )

    @Test
    fun everyReferencePhraseScores100AgainstItself() = runTest {
        val problems = phrases.mapNotNull { (events, tempo) -> ConsistencyCheck.checkSelf(phrase(events), tempo)?.let { "\"$events\": $it" } }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun aDifferentPhraseScoresClearlyLower() = runTest {
        val tempo = 100.0
        val line1 = phrase("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", 0)
        val line2 = phrase("F4/1 F4/1 E4/1 E4/1 D4/1 D4/1 C4/2", 1)
        val cross = ConsistencyCheck.crossScore(played = line1, expected = line2, tempo)
        assertTrue(cross < 60, "phrase 1's reference scored $cross as phrase 2")
    }
}

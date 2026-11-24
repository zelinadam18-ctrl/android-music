package io.github.rudicilibrasi.pianotrainer.ui.staff

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.scoring.Aligner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every case from #39's alignment tests, on the detected staff. */
class DetectedStaffTest {

    private val phrase = Phrase(0, SongFormat.parseEvents("C4/1 D4/1 E4/1 F4/1 G4/1"), 0.0)
    private val layout = StaffLayout(phrase.events)
    private val expectedXs = layout.events.map { it.x }

    private fun played(labels: String) = labels.split(" ").mapIndexed { i, label ->
        if (label == "?") {
            DetectedNote(null, i * 500.0, i * 500.0 + 400, 0.1, uncertain = true)
        } else {
            DetectedNote(Pitch.parse(label), i * 500.0, i * 500.0 + 400, 0.9, uncertain = false)
        }
    }

    private fun model(labels: String?): DetectedStaffModel {
        val detected = labels?.let(::played) ?: emptyList()
        return DetectedStaffModel.of(layout, Aligner.align(phrase, detected), detected)
    }

    @Test
    fun aPerfectPerformanceSitsExactlyUnderTheExpectedNotes() {
        val model = model("C4 D4 E4 F4 G4")
        assertEquals(List(5) { PlayedMark.Correct }, model.slots.map { it.mark })
        assertEquals(expectedXs, model.slots.map { it.x })
        assertEquals(listOf("C4", "D4", "E4", "F4", "G4"), model.slots.map { it.label })
        assertEquals(layout.events.map { it.y }, model.slots.map { it.y }, "same pitch, same height")
    }

    @Test
    fun aNoteFarBelowThePhraseGetsRoomInsteadOfSpillingOut() {
        // Seen on a phone: noise detected as G2 under a C4–G4 phrase drew its head below the
        // staff's canvas, over the legend, with its label on top of the ledger lines.
        val model = model("G2 D4 E4 F4 G4")
        val g2 = model.slots[0]
        val headY = g2.y!!
        // C4, A3, F3, D3, B2 and G2's own line, through its head.
        assertEquals(6, g2.ledgerLineYs.size)
        assertEquals(headY, g2.ledgerLineYs.maxOrNull())
        assertTrue(model.labelTop > headY + 0.5, "label row ${model.labelTop} below the head at $headY")
        assertTrue(model.height > model.labelTop)
        // The rest of the staff doesn't move: the in-range notes keep the expected staff's heights.
        assertEquals(layout.events.drop(1).map { it.y }, model.slots.drop(1).map { it.y })
    }

    @Test
    fun aWrongNoteIsAtItsOwnPitchUnderTheExpectedOne() {
        val slot = model("C4 D4 A4 F4 G4").slots[2]
        assertEquals(PlayedMark.Wrong, slot.mark)
        assertEquals(expectedXs[2], slot.x)
        assertEquals("A4", slot.label)
        assertEquals(TrebleStaff_step("A4"), slot.step)
    }

    @Test
    fun aMissedNoteLeavesAMarkedGap() {
        val model = model("C4 D4 F4 G4")
        val missed = model.slots.single { it.mark == PlayedMark.Missed }
        assertEquals(expectedXs[2], missed.x)
        assertNull(missed.y, "no head drawn")
        assertEquals("—", missed.label)
    }

    @Test
    fun anExtraNoteGoesBetweenItsNeighbours() {
        val model = model("C4 D4 B4 E4 F4 G4")
        val extra = model.slots.single { it.mark == PlayedMark.Extra }
        assertTrue(extra.x > expectedXs[1] && extra.x < expectedXs[2], "between D4 and E4: ${extra.x}")
        assertEquals("B4", extra.label)
        assertEquals(5, model.slots.count { it.mark == PlayedMark.Correct })
    }

    @Test
    fun swappedAndReversedOrderShowMostlyNonCorrectMarks() {
        assertTrue(model("C4 D4 F4 E4 G4").slots.count { it.mark != PlayedMark.Correct } >= 2)
        assertEquals(1, model("G4 F4 E4 D4 C4").slots.count { it.mark == PlayedMark.Correct })
        // Played slots stay in left-to-right order even when out of order.
        val xs = model("G4 F4 E4 D4 C4").slots.map { it.x }
        assertEquals(xs.sorted(), xs)
    }

    @Test
    fun anEmptyRecordingMarksEveryNoteMissed() {
        val model = model(null)
        assertEquals(List(5) { PlayedMark.Missed }, model.slots.map { it.mark })
    }

    @Test
    fun uncertainNotesGetAQuestionMark() {
        val slot = model("C4 D4 ? F4 G4").slots[2]
        assertEquals(PlayedMark.Uncertain, slot.mark)
        assertEquals("?", slot.label)
        assertNull(slot.y)
    }

    @Test
    fun everyMarkHasADifferentSymbol() {
        assertEquals(PlayedMark.entries.size, PlayedMark.entries.map { it.symbol }.toSet().size)
        assertTrue(model("C4 D4 A4 F4").description.startsWith("What you played: C4 correct, D4 correct, A4 wrong note"))
    }

    @Test
    fun extraNotesAfterTheEndWidenTheStaff() {
        val model = model("C4 D4 E4 F4 G4 A4 B4")
        assertTrue(model.slots.last().x > expectedXs.last())
        assertTrue(model.width >= layout.width)
    }

    private fun TrebleStaff_step(label: String) = io.github.rudicilibrasi.pianotrainer.core.notation.TrebleStaff.position(Pitch.parse(label)).step
}

package io.github.rudicilibrasi.pianotrainer.ui.staff

import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaffLayoutTest {

    private fun layout(events: String) = StaffLayout(SongFormat.parseEvents(events))

    @Test
    fun twinklePhrase1() {
        val layout = layout("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2")
        assertEquals(listOf("C4", "C4", "G4", "G4", "A4", "A4", "G4"), layout.events.map { it.label })
        assertEquals(listOf(-2, -2, 2, 2, 3, 3, 2), layout.events.map { it.step })
        // Middle C has one ledger line, through its head.
        assertEquals(listOf(layout.y(-2)), layout.events[0].ledgerLineYs)
        assertTrue(layout.events.drop(2).all { it.ledgerLineYs.isEmpty() })
        assertEquals(List(6) { NoteHead.Black } + NoteHead.Half, layout.events.map { it.head })
        // Fits a phone in portrait: 7 notes at 3 spaces plus the clef is about 25 staff spaces.
        assertTrue(layout.width < 26, "width ${layout.width}")
    }

    @Test
    fun higherNotesAreHigherUpAndEachStepIsHalfASpace() {
        val layout = layout("E4/1 F4/1 F5/1")
        val (e4, f4, f5) = layout.events
        assertEquals(0.5, e4.y!! - f4.y!!, 1e-9)
        assertEquals(4.0, e4.y!! - f5.y!!, 1e-9)
        assertEquals(listOf(0, 2, 4, 6, 8).map(layout::y), layout.lineSteps.map(layout::y))
    }

    @Test
    fun g3ToG5HasLedgerLinesOnlyOutsideTheStaff() {
        val layout = layout("G3/1 A3/1 B3/1 C4/1 D4/1 E4/1 F5/1 G5/1 A5/1 B5/1 C6/1")
        val ledgers = layout.events.associate { it.label to it.ledgerLineYs.size }
        assertEquals(2, ledgers["G3"]) // C4 and A3 lines
        assertEquals(2, ledgers["A3"]) // A3 sits on the second ledger line
        assertEquals(1, ledgers["B3"])
        assertEquals(1, ledgers["C4"])
        assertEquals(0, ledgers["D4"])
        assertEquals(0, ledgers["E4"])
        assertEquals(0, ledgers["F5"])
        assertEquals(0, ledgers["G5"])
        assertEquals(1, ledgers["A5"])
        assertEquals(1, ledgers["B5"])
        assertEquals(2, ledgers["C6"])
    }

    @Test
    fun theDrawingIsTallEnoughForEveryNoteAndTheLabels() {
        val layout = layout("G3/1 C6/1")
        assertTrue(layout.events.all { it.y!! > 0.0 })
        assertTrue(layout.events.all { it.y!! < layout.labelTop })
        assertTrue(layout.clefY in 0.0..layout.labelTop)
        assertTrue(layout.height > layout.labelTop)
    }

    @Test
    fun notesAreEvenlySpacedWithRoomForSharps() {
        val layout = layout("C4/1 F#4/1 G4/1")
        val (c, fSharp, g) = layout.events
        assertTrue(fSharp.sharp)
        assertEquals(StaffLayout.NOTE_SPACING + StaffLayout.SHARP_SPACE, fSharp.x - c.x, 1e-9)
        assertEquals(StaffLayout.NOTE_SPACING, g.x - fSharp.x, 1e-9)
        assertEquals("F#4", fSharp.label)
    }

    @Test
    fun restsTakeSpaceButHaveNoHeadOrLabel() {
        val layout = layout("E4/1 R/1 D4/1")
        val rest = layout.events[1]
        assertEquals(null, rest.head)
        assertEquals(null, rest.label)
        assertEquals(null, rest.noteIndex)
        assertEquals(listOf(0, null, 1), layout.events.map { it.noteIndex })
        assertEquals(StaffLayout.NOTE_SPACING, layout.events[2].x - rest.x, 1e-9)
    }

    @Test
    fun noteHeadsFollowTheNoteLength() {
        assertEquals(listOf(NoteHead.Black, NoteHead.Black, NoteHead.Half, NoteHead.Whole), layout("C4/0.5 C4/1.5 C4/3 C4/4").events.map { it.head })
    }
}

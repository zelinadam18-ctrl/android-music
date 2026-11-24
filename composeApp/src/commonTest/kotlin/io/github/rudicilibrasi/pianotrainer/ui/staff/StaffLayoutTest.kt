package io.github.rudicilibrasi.pianotrainer.ui.staff

import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffLayoutTest {

    private fun layout(events: String, time: TimeSignature? = null, start: Double = 0.0, pickup: Double = 0.0) =
        StaffLayout(SongFormat.parseEvents(events), time, start, pickup)

    private val fourFour = TimeSignature(4, 4)

    // --- Pitch (#24) ---

    @Test
    fun twinklePhrase1() {
        val layout = layout("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", fourFour)
        assertEquals(listOf("C4", "C4", "G4", "G4", "A4", "A4", "G4"), layout.events.map { it.label })
        assertEquals(listOf(-2, -2, 2, 2, 3, 3, 2), layout.events.map { it.step })
        // Middle C has one ledger line, through its head.
        assertEquals(listOf(layout.y(-2)), layout.events[0].ledgerLineYs)
        assertTrue(layout.events.drop(2).all { it.ledgerLineYs.isEmpty() })
        assertEquals(List(6) { NoteHead.Black } + NoteHead.Half, layout.events.map { it.head })
        // One bar line, after the fourth note.
        assertEquals(1, layout.barLineXs.size)
        assertTrue(layout.barLineXs[0] > layout.events[3].x && layout.barLineXs[0] < layout.events[4].x)
        // Fits a phone in portrait (about 40 staff spaces at 9 dp is 360 dp).
        assertTrue(layout.width < 40, "width ${layout.width}")
    }

    @Test
    fun higherNotesAreHigherUpAndEachStepIsHalfASpace() {
        val layout = layout("E4/1 F4/1 F5/1")
        val (e4, f4, f5) = layout.events
        assertEquals(0.5, e4.y - f4.y, 1e-9)
        assertEquals(4.0, e4.y - f5.y, 1e-9)
    }

    @Test
    fun g3ToC6HasLedgerLinesOnlyOutsideTheStaff() {
        val layout = layout("G3/1 A3/1 B3/1 C4/1 D4/1 E4/1 F5/1 G5/1 A5/1 B5/1 C6/1")
        val ledgers: Map<String, Int> = layout.events.associate { it.label!! to it.ledgerLineYs.size }
        assertEquals(mapOf("G3" to 2, "A3" to 2, "B3" to 1, "C4" to 1, "D4" to 0, "E4" to 0, "F5" to 0, "G5" to 0, "A5" to 1, "B5" to 1, "C6" to 2), ledgers)
    }

    @Test
    fun aBeamPushingAStemUpwardStillFitsInTheDrawing() {
        // B5 is beamed with A3, so its stem goes up, far above the usual estimate.
        val layout = layout("A3/0.5 B5/0.5")
        assertTrue(layout.events.all { it.stem!!.up })
        assertTrue(layout.beams.all { minOf(it.startY, it.endY) - StaffLayout.BEAM_THICKNESS / 2 >= 0.0 }, "beams ${layout.beams}")
        assertTrue(layout.events.all { it.stem!!.tipY >= 0.0 })
    }

    @Test
    fun theDrawingIsTallEnoughForHeadsStemsAndLabels() {
        val layout = layout("G3/1 C6/1 A3/0.5 B5/0.25")
        for (event in layout.events) {
            assertTrue(event.y > 0.0 && event.y < layout.labelTop)
            val stem = assertNotNull(event.stem)
            assertTrue(stem.tipY > 0.0 && stem.tipY < layout.labelTop, "stem tip ${stem.tipY}")
        }
        assertTrue(layout.height > layout.labelTop)
    }

    // --- Rhythm (#25) ---

    @Test
    fun noteValuesAndDotsFromBeats() {
        assertEquals(NoteValue.Whole to 0, NoteValue.of(4.0))
        assertEquals(NoteValue.Half to 1, NoteValue.of(3.0))
        assertEquals(NoteValue.Half to 0, NoteValue.of(2.0))
        assertEquals(NoteValue.Quarter to 1, NoteValue.of(1.5))
        assertEquals(NoteValue.Quarter to 0, NoteValue.of(1.0))
        assertEquals(NoteValue.Eighth to 1, NoteValue.of(0.75))
        assertEquals(NoteValue.Eighth to 0, NoteValue.of(0.5))
        assertEquals(NoteValue.Sixteenth to 0, NoteValue.of(0.25))
        assertEquals(NoteValue.Whole to 1, NoteValue.of(6.0))
        // Needs a tie: shown as the longest value that fits.
        assertEquals(NoteValue.Quarter to 0, NoteValue.of(1.25))
    }

    @Test
    fun stemsGoUpBelowTheMiddleLineAndDownFromIt() {
        val layout = layout("A4/1 B4/1 C5/1 C4/2 G5/4")
        val (a4, b4, c5, c4, g5) = layout.events
        assertTrue(a4.stem!!.up)
        assertTrue(a4.stem!!.tipY < a4.y)
        assertTrue(!b4.stem!!.up && b4.stem!!.tipY > b4.y)
        assertTrue(!c5.stem!!.up)
        assertTrue(c4.stem!!.up)
        // 3.5 spaces long.
        assertEquals(3.5, c4.y - c4.stem!!.tipY, 1e-9)
        // Up stems on the right of the head, down stems on the left.
        assertTrue(a4.stem!!.x > a4.x && b4.stem!!.x < b4.x)
        assertNull(g5.stem, "whole notes have no stem")
    }

    @Test
    fun eighthsAndSixteenthsGetFlags() {
        // Each short note alone in its beat, so it keeps its flags.
        val layout = layout("C4/1 C4/0.5 R/0.5 C4/0.25 R/0.75 C5/0.5 R/0.5")
        val notes = layout.events.filter { !it.isRest }
        assertEquals(listOf(0, 1, 2, 1), notes.map { it.stem!!.flags })
        assertTrue(!notes[3].stem!!.up)
        assertEquals(emptyList(), layout.beams)
    }

    @Test
    fun dotsSitInASpace() {
        val layout = layout("E4/1.5 F4/1.5 R/1.5")
        val (onLine, inSpace, rest) = layout.events
        assertEquals(layout.y(1), onLine.dotY!!, 1e-9) // E4 is on a line: dot moves up to F4's space.
        assertEquals(inSpace.y, inSpace.dotY!!, 1e-9)
        assertTrue(onLine.dotX!! > onLine.x)
        assertNotNull(rest.dotY)
        assertEquals(1, rest.dots)
    }

    @Test
    fun restsHaveTheirOwnValuesAndHeights() {
        val layout = layout("R/4 R/2 R/1 R/0.5 R/0.25")
        assertTrue(layout.events.all { it.isRest && it.head == null && it.stem == null && it.label == null })
        assertEquals(listOf(NoteValue.Whole, NoteValue.Half, NoteValue.Quarter, NoteValue.Eighth, NoteValue.Sixteenth), layout.events.map { it.value })
        // The whole rest hangs from the fourth line; the others sit on the middle line.
        assertEquals(layout.y(6), layout.events[0].y, 1e-9)
        assertTrue(layout.events.drop(1).all { it.y == layout.y(4) })
    }

    @Test
    fun longerNotesTakeMoreRoom() {
        val spacings = listOf(0.25, 0.5, 1.0, 2.0, 4.0).map(StaffLayout::spacing)
        assertEquals(spacings.sorted(), spacings)
        assertTrue(StaffLayout.spacing(4.0) < 4 * StaffLayout.spacing(1.0), "less than proportional")
        val layout = layout("C4/2 D4/1 E4/1")
        val (half, quarter, next) = layout.events
        assertTrue(quarter.x - half.x > next.x - quarter.x)
    }

    @Test
    fun barLinesFollowTheTimeSignatureAndPhraseStart() {
        // 3/4, phrase starting on beat 2 of a bar: bars end after 2 beats, then every 3.
        val layout = layout("C4/1 D4/1 E4/1 F4/1 G4/1 A4/1", TimeSignature(3, 4), start = 4.0)
        assertEquals(2, layout.barLineXs.size)
        val xs = layout.events.map { it.x }
        assertTrue(layout.barLineXs[0] in xs[1]..xs[2])
        assertTrue(layout.barLineXs[1] in xs[4]..xs[5])
    }

    @Test
    fun aPickupShiftsTheBarLines() {
        // One-beat pickup in 4/4: the first bar line comes after the pickup note.
        val layout = layout("G4/1 C5/1 C5/1 C5/1 C5/1 D5/1", fourFour, start = 0.0, pickup = 1.0)
        assertEquals(2, layout.barLineXs.size)
        val xs = layout.events.map { it.x }
        assertTrue(layout.barLineXs[0] in xs[0]..xs[1])
        assertTrue(layout.barLineXs[1] in xs[4]..xs[5])
    }

    @Test
    fun noBarLineAtTheVeryEndOrWithoutATimeSignature() {
        assertEquals(emptyList(), layout("C4/4", fourFour).barLineXs)
        assertEquals(emptyList(), layout("C4/1 D4/1 E4/1 F4/1 G4/1").barLineXs)
        assertNull(layout("C4/1").timeSignatureX)
        assertNotNull(layout("C4/1", fourFour).timeSignatureX)
    }

    @Test
    fun sharpsGetExtraRoom() {
        val plain = layout("C4/1 G4/1 A4/1")
        val sharp = layout("C4/1 F#4/1 A4/1")
        assertTrue(sharp.events[1].sharp)
        assertEquals(StaffLayout.SHARP_SPACE, (sharp.events[1].x - sharp.events[0].x) - (plain.events[1].x - plain.events[0].x), 1e-9)
    }
}

class BeamTest {

    private fun layout(events: String, time: TimeSignature? = TimeSignature(4, 4)) = StaffLayout(SongFormat.parseEvents(events), time)

    @Test
    fun sixteenthsInABeatShareTwoBeams() {
        // Prelude in C: four sixteenths per beat, two beats.
        val layout = layout("C4/0.25 E4/0.25 G4/0.25 C5/0.25 E5/0.25 G4/0.25 C5/0.25 E5/0.25")
        assertTrue(layout.events.all { it.stem!!.flags == 0 }, "beamed notes have no flags")
        assertEquals(2, layout.beams.count { it.level == 0 })
        assertEquals(2, layout.beams.count { it.level == 1 })
        // Each primary beam runs from the first to the last stem of its beat.
        val first = layout.beams.first { it.level == 0 }
        assertEquals(layout.events[0].stem!!.x, first.startX, 1e-9)
        assertEquals(layout.events[3].stem!!.x, first.endX, 1e-9)
    }

    @Test
    fun aGroupHasOneStemDirectionFromItsAveragePosition() {
        // C4 is low but A5 pulls the average up to the middle line: both stems down.
        val down = layout("C4/0.5 A5/0.5").events
        assertTrue(down.all { !it.stem!!.up }, "${down.map { it.stem }}")
        val up = layout("C4/0.5 E4/0.5").events
        assertTrue(up.all { it.stem!!.up })
    }

    @Test
    fun stemsMeetTheBeamAndAreLongEnough() {
        val layout = layout("C4/0.5 G4/0.5 E4/0.5 C5/0.5")
        for ((group, beam) in listOf(layout.events.take(2), layout.events.drop(2)).zip(layout.beams)) {
            for (event in group) {
                val stem = event.stem!!
                // On the beam line, at its outer edge.
                val t = (stem.x - beam.startX) / (beam.endX - beam.startX)
                val beamCentre = beam.startY + t * (beam.endY - beam.startY)
                val edge = if (stem.up) beamCentre - StaffLayout.BEAM_THICKNESS / 2 else beamCentre + StaffLayout.BEAM_THICKNESS / 2
                assertEquals(edge, stem.tipY, 1e-9)
                assertTrue(kotlin.math.abs(event.y - stem.tipY) >= StaffLayout.BEAMED_STEM - 1e-9, "stem ${event.y - stem.tipY}")
            }
        }
    }

    @Test
    fun beamSlopeIsGentle() {
        // A leap of more than an octave still gives at most one space of rise.
        val beam = layout("C4/0.5 E5/0.5").beams.single()
        assertTrue(kotlin.math.abs(beam.endY - beam.startY) <= StaffLayout.MAX_BEAM_RISE + 1e-9)
        val flat = layout("G4/0.5 G4/0.5").beams.single()
        assertEquals(flat.startY, flat.endY, 1e-9)
    }

    @Test
    fun dottedEighthAndSixteenthGetABeamletOnTheSixteenth() {
        // Happy Birthday: "Hap-py" is a dotted eighth and a sixteenth in one beat.
        val layout = layout("G4/0.75 G4/0.25 A4/1 G4/1")
        val (dotted, sixteenth) = layout.events
        assertEquals(1, dotted.dots)
        assertEquals(0, dotted.stem!!.flags)
        assertEquals(0, sixteenth.stem!!.flags)
        assertEquals(1, layout.beams.count { it.level == 0 })
        val beamlet = layout.beams.single { it.level == 1 }
        // Short, ending at the sixteenth's stem and pointing back at the dotted eighth.
        assertEquals(sixteenth.stem!!.x, beamlet.endX, 1e-9)
        assertEquals(StaffLayout.BEAMLET_LENGTH, beamlet.endX - beamlet.startX, 1e-9)
    }

    @Test
    fun differentBeatsRestsAndLoneNotesAreNotBeamed() {
        // Eighths split across beats 1-2 (off-beat start), a rest in the middle, a lone eighth.
        val layout = layout("C4/1 C4/0.5 R/0.5 D4/0.5 E4/0.5 F4/1")
        assertEquals(1, layout.beams.size, "only D4-E4 share a beat with nothing between")
        assertEquals(1, layout.events[1].stem!!.flags, "lone eighth keeps its flag")
        assertEquals(0, layout.events[3].stem!!.flags)
    }

    @Test
    fun compoundTimeBeamsByDottedQuarter() {
        // 6/8: three eighths per beat.
        val layout = layout("C4/0.5 D4/0.5 E4/0.5 F4/0.5 G4/0.5 A4/0.5", TimeSignature(6, 8))
        assertEquals(2, layout.beams.size)
        assertTrue(layout.events.all { it.stem!!.flags == 0 })
    }
}

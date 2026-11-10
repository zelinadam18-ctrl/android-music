package io.github.rudicilibrasi.pianotrainer.ui.staff

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.notation.Accidental
import io.github.rudicilibrasi.pianotrainer.core.notation.TrebleStaff

/** Which note-head glyph to draw, from the note's length. */
enum class NoteHead { Black, Half, Whole }

/**
 * One event placed on the staff. All lengths are in staff spaces (the gap between two staff
 * lines), so the layout is independent of screen size.
 *
 * @property eventIndex index into the phrase's events (rests included), for highlighting.
 * @property noteIndex index among the phrase's notes (rests excluded), or null for a rest.
 * @property y vertical centre of the note head, from the top of the drawing.
 * @property ledgerLineYs vertical positions of any short ledger lines the note needs.
 */
data class PlacedEvent(
    val eventIndex: Int,
    val noteIndex: Int?,
    val x: Double,
    val y: Double?,
    val step: Int?,
    val head: NoteHead?,
    val sharp: Boolean,
    val ledgerLineYs: List<Double>,
    val label: String?,
)

/**
 * Where everything goes when a phrase is drawn on a treble staff (#25). Pure arithmetic in staff
 * spaces, so it can be tested without drawing anything; [StaffView] multiplies by the size of a
 * staff space on screen.
 *
 * The drawing is tall enough for the highest and lowest notes and their ledger lines, plus a row
 * of note labels ("C4") underneath. Rests take up horizontal space (their symbols are drawn in
 * #26) but have no head or label.
 */
class StaffLayout(phraseEvents: List<NoteEvent>) {
    /** Steps of the five staff lines, bottom (E4) to top (F5). */
    val lineSteps: List<Int> = (TrebleStaff.BOTTOM_LINE..TrebleStaff.TOP_LINE step 2).toList()

    private val steps = phraseEvents.mapNotNull { event -> event.pitch?.let { TrebleStaff.position(it).step } }

    /** Highest step drawn: the top line, or the highest note, plus room for its head. */
    private val topStep = maxOf(TrebleStaff.TOP_LINE + CLEF_HEADROOM_STEPS, (steps.maxOrNull() ?: 0) + 2)

    /** Lowest step drawn, with the same room below. */
    private val bottomStep = minOf(TrebleStaff.BOTTOM_LINE - CLEF_FOOTROOM_STEPS, (steps.minOrNull() ?: 0) - 2)

    /** Vertical position of [step]; each step is half a staff space. */
    fun y(step: Int): Double = (topStep - step) * 0.5

    val clefX: Double = 0.5

    /** Where the clef glyph's origin sits: on the G line (step 2), as SMuFL expects. */
    val clefY: Double = y(TrebleStaff.BOTTOM_LINE + 2)

    /** Top of the note-label row. */
    val labelTop: Double = y(bottomStep) + 0.5

    val events: List<PlacedEvent>

    /** Total width, ending a little after the last note. */
    val width: Double

    /** Total height, including the label row. */
    val height: Double = labelTop + LABEL_ROW_SPACES

    init {
        var x = FIRST_NOTE_X
        var noteIndex = 0
        events = phraseEvents.mapIndexed { index, event ->
            val pitch = event.pitch
            if (pitch == null) {
                PlacedEvent(index, null, x, null, null, null, sharp = false, ledgerLineYs = emptyList(), label = null).also { x += NOTE_SPACING }
            } else {
                val position = TrebleStaff.position(pitch)
                val sharp = position.accidental == Accidental.SHARP
                if (sharp) x += SHARP_SPACE
                PlacedEvent(
                    eventIndex = index,
                    noteIndex = noteIndex++,
                    x = x,
                    y = y(position.step),
                    step = position.step,
                    head = when {
                        event.beats >= 4.0 -> NoteHead.Whole
                        event.beats >= 2.0 -> NoteHead.Half
                        else -> NoteHead.Black
                    },
                    sharp = sharp,
                    ledgerLineYs = position.ledgerLines.map(::y),
                    label = pitch.label,
                ).also { x += NOTE_SPACING }
            }
        }
        width = x - NOTE_SPACING + END_MARGIN
    }

    companion object {
        /** Clef plus a gap before the first note. */
        const val FIRST_NOTE_X = 4.5
        const val NOTE_SPACING = 3.0

        /** Extra room in front of a note with a sharp. */
        const val SHARP_SPACE = 1.2

        const val END_MARGIN = 1.5

        /** Ledger lines are 1.6 spaces wide, centred on the note head. */
        const val LEDGER_HALF_WIDTH = 0.8

        const val LABEL_ROW_SPACES = 2.0

        /** The treble clef reaches about 3.5 spaces above the top line and 2.5 below the bottom. */
        private const val CLEF_HEADROOM_STEPS = 7
        private const val CLEF_FOOTROOM_STEPS = 5
    }
}

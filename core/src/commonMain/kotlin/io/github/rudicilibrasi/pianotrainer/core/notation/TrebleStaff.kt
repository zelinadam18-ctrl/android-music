package io.github.rudicilibrasi.pianotrainer.core.notation

import io.github.rudicilibrasi.pianotrainer.core.music.Pitch

enum class Accidental { SHARP }

enum class StemDirection { UP, DOWN }

/**
 * Where a note sits on the treble staff.
 *
 * @property step staff position in lines and spaces: 0 is the bottom line (E4), 1 the space above
 *   it (F4), and so on up to 8 for the top line (F5). Notes below the staff have negative steps
 *   (C4 is −2).
 * @property accidental sharp for black keys, null for white keys.
 */
data class StaffPosition(val step: Int, val accidental: Accidental?) {

    /** True if the note head sits on a line (even step), false if in a space. */
    val isOnLine: Boolean get() = step % 2 == 0

    /**
     * Steps of the short ledger lines needed to reach a note above or below the staff, nearest the
     * staff first. Empty for notes on the staff.
     */
    val ledgerLines: List<Int>
        get() = when {
            step <= TrebleStaff.BOTTOM_LINE - 2 -> (TrebleStaff.BOTTOM_LINE - 2 downTo step step 2).toList()
            step >= TrebleStaff.TOP_LINE + 2 -> (TrebleStaff.TOP_LINE + 2..step step 2).toList()
            else -> emptyList()
        }

    /** Notes below the middle line have stems up; notes on or above it have stems down. */
    val stemDirection: StemDirection
        get() = if (step < TrebleStaff.MIDDLE_LINE) StemDirection.UP else StemDirection.DOWN
}

/** Layout rules for the treble staff, kept separate from drawing so they can be unit-tested. */
object TrebleStaff {
    /** E4. */
    const val BOTTOM_LINE = 0

    /** B4. */
    const val MIDDLE_LINE = 4

    /** F5. */
    const val TOP_LINE = 8

    private const val LETTERS = "CDEFGAB"

    /** Diatonic number of E4 (letter index 2 in octave 4), the bottom line. */
    private const val BOTTOM_LINE_DIATONIC = 4 * 7 + 2

    fun position(pitch: Pitch): StaffPosition {
        val letter = pitch.name[0]
        val accidental = if (pitch.name.length > 1) Accidental.SHARP else null
        val diatonic = pitch.octave * 7 + LETTERS.indexOf(letter)
        return StaffPosition(diatonic - BOTTOM_LINE_DIATONIC, accidental)
    }
}

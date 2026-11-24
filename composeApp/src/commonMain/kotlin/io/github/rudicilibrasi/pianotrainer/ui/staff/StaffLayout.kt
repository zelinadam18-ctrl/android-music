package io.github.rudicilibrasi.pianotrainer.ui.staff

import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.notation.Accidental
import io.github.rudicilibrasi.pianotrainer.core.notation.StemDirection
import io.github.rudicilibrasi.pianotrainer.core.notation.TrebleStaff
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Tolerance for comparing lengths in beats. */
private const val EPSILON = 1e-6

/** A written note or rest value, with its length in quarter-note beats. */
enum class NoteValue(val beats: Double, val flags: Int) {
    Whole(4.0, 0),
    Half(2.0, 0),
    Quarter(1.0, 0),
    Eighth(0.5, 1),
    Sixteenth(0.25, 2);

    companion object {
        /**
         * The written value and number of dots (0 or 1) for a length in beats: 1.5 is a dotted
         * quarter, 3 a dotted half. A length no single value can show (e.g. 1.25, which needs a
         * tie) gets the longest value that fits, undotted.
         */
        fun of(beats: Double): Pair<NoteValue, Int> {
            for (value in entries) {
                if (abs(beats - value.beats) < EPSILON) return value to 0
                if (abs(beats - value.beats * 1.5) < EPSILON) return value to 1
            }
            return (entries.firstOrNull { it.beats <= beats + EPSILON } ?: Sixteenth) to 0
        }
    }
}

/** Which note-head glyph to draw. */
enum class NoteHead { Black, Half, Whole }

/** A note's stem, from the head to the tip, with any flags at the tip (none when beamed). */
data class Stem(val x: Double, val headY: Double, val tipY: Double, val up: Boolean, val flags: Int)

/**
 * One beam, as a thick line from ([startX], [startY]) to ([endX], [endY]) along its centre.
 * Level 0 is the primary beam joining every note of a group; level 1 joins sixteenths.
 */
data class Beam(val startX: Double, val startY: Double, val endX: Double, val endY: Double, val level: Int)

/**
 * One event placed on the staff. All lengths are in staff spaces (the gap between two staff
 * lines), so the layout is independent of screen size.
 *
 * @property eventIndex index into the phrase's events (rests included).
 * @property noteIndex index among the phrase's notes (rests excluded), or null for a rest.
 * @property y for a note, the vertical centre of its head; for a rest, where the rest glyph's
 *   origin goes.
 * @property dotY vertical position of the augmentation dot, or null when undotted. A note on a
 *   line has its dot moved up into the space above.
 */
data class PlacedEvent(
    val eventIndex: Int,
    val noteIndex: Int?,
    val x: Double,
    val y: Double,
    val step: Int?,
    val value: NoteValue,
    val dots: Int,
    val head: NoteHead?,
    val stem: Stem?,
    val sharp: Boolean,
    val ledgerLineYs: List<Double>,
    val dotX: Double?,
    val dotY: Double?,
    val label: String?,
) {
    val isRest: Boolean get() = noteIndex == null
}

/**
 * Where everything goes when a phrase is drawn on a treble staff (#24, #25). Pure arithmetic in
 * staff spaces, so it can be tested without drawing anything; [StaffView] multiplies by the size
 * of a staff space on screen.
 *
 * - Note heads, ledger lines and a "C4" label row under the staff.
 * - Stems follow [TrebleStaff] (#23): up below the middle line, down on or above it, 3.5 spaces
 *   long. Unbeamed eighths and sixteenths get one or two flags.
 * - Eighths and sixteenths in the same beat are beamed instead (#26): one stem direction per
 *   group from its average position, a beam sloping with the first and last notes (at most one
 *   space), stems lengthened to meet it, and a second beam (or a short beamlet) for sixteenths.
 * - Dotted values get a dot; rests are drawn at their usual heights.
 * - The time signature after the clef, and a bar line wherever a bar ends inside the phrase,
 *   counted from the phrase's [phraseStartBeat] and the song's [pickupBeats].
 * - Horizontal spacing grows with duration, so long notes take more room than short ones.
 *
 * @param phraseStartBeat beat at which the phrase starts, from the start of the song.
 * @param pickupBeats length of the song's pickup bar (anacrusis), if any.
 */
class StaffLayout(
    phraseEvents: List<NoteEvent>,
    val timeSignature: TimeSignature? = null,
    phraseStartBeat: Double = 0.0,
    pickupBeats: Double = 0.0,
) {
    /** Steps of the five staff lines, bottom (E4) to top (F5). */
    val lineSteps: List<Int> = (TrebleStaff.BOTTOM_LINE..TrebleStaff.TOP_LINE step 2).toList()

    private val noteSteps = phraseEvents.mapNotNull { event -> event.pitch?.let { TrebleStaff.position(it).step } }

    /**
     * The highest and lowest steps anything reaches: note heads, stem tips, beams and the clef.
     * Estimated from the notes first, then widened if beaming pushed a stem further (see below).
     */
    private var topStep: Int
    private var bottomStep: Int

    init {
        var top = TrebleStaff.TOP_LINE + CLEF_HEADROOM_STEPS
        var bottom = TrebleStaff.BOTTOM_LINE - CLEF_FOOTROOM_STEPS
        for (event in phraseEvents) {
            val pitch = event.pitch ?: continue
            val position = TrebleStaff.position(pitch)
            val hasStem = NoteValue.of(event.beats).first != NoteValue.Whole
            val tip = if (position.stemDirection == StemDirection.UP) position.step + STEM_STEPS else position.step - STEM_STEPS
            top = maxOf(top, position.step + 2, if (hasStem) tip + 1 else Int.MIN_VALUE)
            bottom = minOf(bottom, position.step - 2, if (hasStem) tip - 1 else Int.MAX_VALUE)
        }
        topStep = top
        bottomStep = bottom
    }

    /** Vertical position of [step]; each step is half a staff space. */
    fun y(step: Int): Double = (topStep - step) * 0.5

    val clefX: Double = 0.5

    /** Where the clef glyph's origin sits: on the G line (step 2), as SMuFL expects. */
    val clefY: Double get() = y(TrebleStaff.BOTTOM_LINE + 2)

    /** Left edge of the time signature, or null when none is shown. */
    val timeSignatureX: Double? = timeSignature?.let { CLEF_WIDTH }

    /** Baselines of the time signature's two digits: centred on the 2nd and 4th lines' spaces. */
    val timeSignatureTopY: Double get() = y(TrebleStaff.TOP_LINE - 2)
    val timeSignatureBottomY: Double get() = y(TrebleStaff.BOTTOM_LINE + 2)

    /** Horizontal positions of bar lines inside the phrase. */
    val barLineXs: List<Double>

    /** Top of the note-label row. */
    val labelTop: Double get() = y(bottomStep) + 0.5

    val events: List<PlacedEvent>

    val beams: List<Beam>

    /** Total width, ending a little after the last note. */
    val width: Double

    /** Total height, including the label row. */
    val height: Double get() = labelTop + LABEL_ROW_SPACES

    /** Top and bottom of the staff lines, for bar lines. */
    val staffTopY: Double get() = y(TrebleStaff.TOP_LINE)
    val staffBottomY: Double get() = y(TrebleStaff.BOTTOM_LINE)

    init {
        var placement = place(phraseEvents, phraseStartBeat, pickupBeats)
        // A beamed group can force a stem against its usual direction, past the estimate above:
        // make room and lay out again (everything moves down together, so once is enough).
        val stemYs = placement.events.mapNotNull { it.stem?.tipY } +
            placement.beams.flatMap { listOf(it.startY, it.endY) }.flatMap { listOf(it - BEAM_THICKNESS, it + BEAM_THICKNESS) }
        val minY = stemYs.minOrNull()
        val maxY = stemYs.maxOrNull()
        var grown = false
        if (minY != null && minY < MARGIN) {
            topStep += ceil((MARGIN - minY) * 2).toInt()
            grown = true
        }
        if (maxY != null && maxY > y(bottomStep)) {
            bottomStep -= ceil((maxY - y(bottomStep)) * 2).toInt()
            grown = true
        }
        if (grown) placement = place(phraseEvents, phraseStartBeat, pickupBeats)
        events = placement.events
        beams = placement.beams
        barLineXs = placement.bars
        width = placement.width
    }

    private class Placement(val events: List<PlacedEvent>, val beams: List<Beam>, val bars: List<Double>, val width: Double)

    private fun place(phraseEvents: List<NoteEvent>, phraseStartBeat: Double, pickupBeats: Double): Placement {
        var x = CLEF_WIDTH + (if (timeSignature != null) TIME_SIGNATURE_WIDTH else 0.0) + LEAD_IN
        var noteIndex = 0
        var beat = phraseStartBeat
        val bars = ArrayList<Double>()
        val barLength = timeSignature?.quarterBeatsPerBar
        val startBeats = ArrayList<Double>()

        val placedEvents = phraseEvents.mapIndexed { index, event ->
            startBeats += beat
            val (value, dots) = NoteValue.of(event.beats)
            val pitch = event.pitch
            val placed = if (pitch == null) {
                PlacedEvent(
                    eventIndex = index,
                    noteIndex = null,
                    x = x,
                    y = y(restStep(value)),
                    step = null,
                    value = value,
                    dots = dots,
                    head = null,
                    stem = null,
                    sharp = false,
                    ledgerLineYs = emptyList(),
                    dotX = if (dots > 0) x + REST_DOT_OFFSET else null,
                    dotY = if (dots > 0) y(TrebleStaff.MIDDLE_LINE + 1) else null,
                    label = null,
                )
            } else {
                val position = TrebleStaff.position(pitch)
                val sharp = position.accidental == Accidental.SHARP
                if (sharp) x += SHARP_SPACE
                val up = position.stemDirection == StemDirection.UP
                val stem = if (value == NoteValue.Whole) {
                    null
                } else {
                    Stem(
                        x = if (up) x + HEAD_HALF_WIDTH - STEM_INSET else x - HEAD_HALF_WIDTH + STEM_INSET,
                        headY = y(position.step) + if (up) -STEM_HEAD_OVERLAP else STEM_HEAD_OVERLAP,
                        tipY = y(if (up) position.step + STEM_STEPS else position.step - STEM_STEPS),
                        up = up,
                        flags = value.flags,
                    )
                }
                // Dots sit in a space: a note on a line has its dot moved up a step.
                val dotStep = if (position.isOnLine) position.step + 1 else position.step
                PlacedEvent(
                    eventIndex = index,
                    noteIndex = noteIndex++,
                    x = x,
                    y = y(position.step),
                    step = position.step,
                    value = value,
                    dots = dots,
                    head = when (value) {
                        NoteValue.Whole -> NoteHead.Whole
                        NoteValue.Half -> NoteHead.Half
                        else -> NoteHead.Black
                    },
                    stem = stem,
                    sharp = sharp,
                    ledgerLineYs = position.ledgerLines.map(::y),
                    dotX = if (dots > 0) x + HEAD_HALF_WIDTH + DOT_GAP + (if (up && value.flags > 0) FLAG_WIDTH else 0.0) else null,
                    dotY = if (dots > 0) y(dotStep) else null,
                    label = pitch.label,
                )
            }
            x += spacing(event.beats)
            beat += event.beats
            // A bar line after this event if a bar ends here, unless it's the end of the phrase.
            if (barLength != null && index < phraseEvents.lastIndex && isBarBoundary(beat - pickupBeats, barLength)) {
                bars += x - BAR_GAP / 2
                x += BAR_GAP / 2
            }
            placed
        }
        val beamUnit = if (timeSignature?.beatUnit == 8) 1.5 else 1.0
        val (beamedEvents, beamList) = beam(placedEvents, startBeats.map { it - pickupBeats }, beamUnit)
        return Placement(beamedEvents, beamList, bars, x - spacing(phraseEvents.lastOrNull()?.beats ?: 1.0) + LAST_NOTE_ROOM + END_MARGIN)
    }

    /**
     * Beams eighths and sixteenths that share a beat of [unit] beats. [beats] is each event's start,
     * counted from the first full bar.
     */
    private fun beam(placed: List<PlacedEvent>, beats: List<Double>, unit: Double): Pair<List<PlacedEvent>, List<Beam>> {
        val result = placed.toMutableList()
        val beams = ArrayList<Beam>()
        val groups = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var currentBeat = Long.MIN_VALUE
        for (i in placed.indices) {
            val event = placed[i]
            val beamable = !event.isRest && event.value.flags > 0
            val beatIndex = floor(beats[i] / unit + EPSILON).toLong()
            if (!beamable || beatIndex != currentBeat) {
                if (current.size >= 2) groups += current
                current = ArrayList()
                currentBeat = beatIndex
            }
            if (beamable) current += i
        }
        if (current.size >= 2) groups += current

        for (group in groups) {
            val notes = group.map { placed[it] }
            val up = notes.sumOf { it.step!! }.toDouble() / notes.size < TrebleStaff.MIDDLE_LINE
            val sign = if (up) -1.0 else 1.0 // Direction from head to tip, in y.
            fun stemX(e: PlacedEvent) = if (up) e.x + HEAD_HALF_WIDTH - STEM_INSET else e.x - HEAD_HALF_WIDTH + STEM_INSET
            val firstX = stemX(notes.first())
            val lastX = stemX(notes.last())
            // Follow the first and last notes, but gently: at most one space of rise.
            val rise = (notes.last().y - notes.first().y).coerceIn(-MAX_BEAM_RISE, MAX_BEAM_RISE)
            val slope = if (lastX > firstX) rise / (lastX - firstX) else 0.0
            fun lineAt(x: Double, offset: Double) = notes.first().y + slope * (x - firstX) + offset
            // Shift the beam so even the shortest stem is the normal length.
            val offset = sign * notes.maxOf { e -> BEAMED_STEM - sign * (lineAt(stemX(e), 0.0) - e.y) }
            for ((k, index) in group.withIndex()) {
                val e = notes[k]
                val x = stemX(e)
                result[index] = e.copy(
                    stem = Stem(
                        x = x,
                        headY = e.y - sign * STEM_HEAD_OVERLAP,
                        tipY = lineAt(x, offset),
                        up = up,
                        flags = 0,
                    ),
                    dotX = e.dotX?.let { e.x + HEAD_HALF_WIDTH + DOT_GAP },
                )
            }
            // Primary beam across the group, its outer edge at the stem tips.
            val inward = -sign * BEAM_THICKNESS / 2
            beams += Beam(firstX, lineAt(firstX, offset) + inward, lastX, lineAt(lastX, offset) + inward, level = 0)
            // Secondary beams: whole runs of sixteenths, or a beamlet towards the neighbour.
            val secondInward = inward - sign * BEAM_SPACING
            var k = 0
            while (k < notes.size) {
                if (notes[k].value != NoteValue.Sixteenth) {
                    k++
                    continue
                }
                var end = k
                while (end + 1 < notes.size && notes[end + 1].value == NoteValue.Sixteenth) end++
                val (from, to) = if (end > k) {
                    stemX(notes[k]) to stemX(notes[end])
                } else {
                    // A lone sixteenth: a short beamlet pointing at the note it belongs with.
                    val x = stemX(notes[k])
                    if (k > 0) (x - BEAMLET_LENGTH) to x else x to (x + BEAMLET_LENGTH)
                }
                beams += Beam(from, lineAt(from, offset) + secondInward, to, lineAt(to, offset) + secondInward, level = 1)
                k = end + 1
            }
        }
        return result to beams
    }

    companion object {
        const val CLEF_WIDTH = 3.5
        const val TIME_SIGNATURE_WIDTH = 2.2
        const val LEAD_IN = 1.2
        const val BAR_GAP = 2.0
        const val END_MARGIN = 1.0
        const val LAST_NOTE_ROOM = 2.0

        /** Extra room in front of a note with a sharp. */
        const val SHARP_SPACE = 1.2

        /** Ledger lines are 1.6 spaces wide, centred on the note head. */
        const val LEDGER_HALF_WIDTH = 0.8

        /** Half the width of a black note head (Bravura's noteheadBlack is 1.18 spaces wide). */
        const val HEAD_HALF_WIDTH = 0.59
        const val STEM_INSET = 0.06
        const val STEM_HEAD_OVERLAP = 0.17

        /** Stems are 3.5 spaces (7 steps) long. */
        const val STEM_STEPS = 7

        const val DOT_GAP = 0.4
        const val FLAG_WIDTH = 1.0
        const val REST_DOT_OFFSET = 1.4

        const val LABEL_ROW_SPACES = 2.0

        /** Room kept above the highest stem or beam. */
        private const val MARGIN = 0.5

        /** Beam thickness and the distance between beam centres, in staff spaces. */
        const val BEAM_THICKNESS = 0.5
        const val BEAM_SPACING = 0.75
        const val MAX_BEAM_RISE = 1.0
        const val BEAMLET_LENGTH = 1.1

        /** Shortest stem in a beamed group: the normal 3.5 spaces, so the beam clears the heads. */
        const val BEAMED_STEM = 3.5

        /** The treble clef reaches about 3.5 spaces above the top line and 2.5 below the bottom. */
        private const val CLEF_HEADROOM_STEPS = 7
        private const val CLEF_FOOTROOM_STEPS = 5

        /**
         * Room after an event of [beats]: grows with duration, but less than proportionally, as in
         * printed music (a quarter is 3 spaces, a half 3.9, a whole 5.1, an eighth 2.4).
         */
        fun spacing(beats: Double): Double = 1.2 + 1.8 * beats.coerceAtLeast(0.0).pow(0.6)

        /** Where each rest glyph's origin goes, per SMuFL: a whole rest hangs from the 4th line. */
        private fun restStep(value: NoteValue): Int =
            if (value == NoteValue.Whole) TrebleStaff.TOP_LINE - 2 else TrebleStaff.MIDDLE_LINE

        private fun isBarBoundary(beatsSinceFirstBar: Double, barLength: Double): Boolean {
            if (beatsSinceFirstBar < EPSILON) return abs(beatsSinceFirstBar) < EPSILON
            val bars = beatsSinceFirstBar / barLength
            return abs(bars - floor(bars + 0.5)) < EPSILON
        }
    }
}

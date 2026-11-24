package io.github.rudicilibrasi.pianotrainer.ui.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import org.jetbrains.compose.resources.Font
import pianotrainer.composeapp.generated.resources.Res
import pianotrainer.composeapp.generated.resources.bravura

/** SMuFL code points in the Bravura font. */
internal object Glyph {
    const val G_CLEF = "\uE050"
    const val NOTEHEAD_WHOLE = "\uE0A2"
    const val NOTEHEAD_HALF = "\uE0A3"
    const val NOTEHEAD_BLACK = "\uE0A4"
    const val SHARP = "\uE262"
    const val DOT = "\uE1E7"
    const val TIME_SIGNATURE_0 = 0xE080

    fun flag(flags: Int, up: Boolean): String? = when (flags) {
        1 -> if (up) "\uE240" else "\uE241"
        2 -> if (up) "\uE242" else "\uE243"
        else -> null
    }

    fun rest(value: NoteValue): String = when (value) {
        NoteValue.Whole -> "\uE4E3"
        NoteValue.Half -> "\uE4E4"
        NoteValue.Quarter -> "\uE4E5"
        NoteValue.Eighth -> "\uE4E6"
        NoteValue.Sixteenth -> "\uE4E7"
    }

    /** A number in time-signature digits. */
    fun timeSignature(number: Int): String =
        number.toString().map { Char(TIME_SIGNATURE_0 + (it - '0')) }.joinToString("")
}

/**
 * A phrase drawn on a treble staff (#24, #25): clef, time signature, staff lines, note heads,
 * stems, flags, dots, rests, ledger lines, bar lines, and a "C4"-style label under each note,
 * matching the letter list on the lesson screen. Eighths and sixteenths in the same beat are
 * beamed (#26).
 *
 * Glyphs come from the Bravura music font (SMuFL, SIL Open Font License; see
 * `files/licenses/bravura-ofl.txt`). A 6–8 note phrase fits a phone in portrait at the default
 * [staffSpace]; longer phrases scroll sideways.
 *
 * @param highlightNoteIndex index among the phrase's notes (rests not counted) to show in the
 *   highlight colour, e.g. the note sounding during reference playback (#30).
 * @param timeSignature shown after the clef, and used to place bar lines; null shows neither.
 * @param phraseStartBeat the phrase's start beat from the start of the song (Phrase.startBeat).
 * @param pickupBeats the song's pickup length, so bar lines fall in the right places.
 * @param scrollState shared with another staff so the two scroll together (see [ComparedStaves]).
 * @param minWidthSpaces draw at least this wide, in staff spaces, e.g. to match a second staff.
 * @param description what screen readers say for the staff, e.g. "Phrase 2: F4, F4, E4, …";
 *   by default the notes in order.
 */
@Composable
fun StaffView(
    events: List<NoteEvent>,
    modifier: Modifier = Modifier,
    highlightNoteIndex: Int? = null,
    timeSignature: TimeSignature? = null,
    phraseStartBeat: Double = 0.0,
    pickupBeats: Double = 0.0,
    staffSpace: Dp = 9.dp,
    scrollState: ScrollState = rememberScrollState(),
    minWidthSpaces: Double = 0.0,
    description: String? = null,
) {
    val layout = remember(events, timeSignature, phraseStartBeat, pickupBeats) {
        StaffLayout(events, timeSignature, phraseStartBeat, pickupBeats)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacePx = with(density) { staffSpace.toPx() }
    // SMuFL fonts are designed so that one em is four staff spaces.
    val music = TextStyle(fontFamily = FontFamily(Font(Res.font.bravura)), fontSize = with(density) { (spacePx * 4).toSp() })
    val labelStyle = MaterialTheme.typography.labelMedium
    val ink = MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.primary
    val spoken = description ?: ("Treble staff" + (timeSignature?.let { " in $it" } ?: "") + ": " +
        layout.events.joinToString(", ") { it.label ?: "rest" })

    Canvas(
        modifier = modifier
            .horizontalScroll(scrollState)
            .size(staffSpace * maxOf(layout.width, minWidthSpaces).toFloat(), staffSpace * layout.height.toFloat())
            .semantics { contentDescription = spoken },
    ) {
        fun px(spaces: Double) = (spaces * spacePx).toFloat()

        // Staff lines.
        for (step in layout.lineSteps) {
            val y = px(layout.y(step))
            drawLine(ink, Offset(0f, y), Offset(px(maxOf(layout.width, minWidthSpaces)), y), strokeWidth = px(STAFF_LINE_THICKNESS))
        }

        drawGlyph(measurer, Glyph.G_CLEF, music, ink, x = px(layout.clefX), baselineY = px(layout.clefY), centred = false)

        val signature = layout.timeSignature
        val signatureX = layout.timeSignatureX
        if (signature != null && signatureX != null) {
            val centre = px(signatureX + StaffLayout.TIME_SIGNATURE_WIDTH / 2)
            drawGlyph(measurer, Glyph.timeSignature(signature.beatsPerBar), music, ink, x = centre, baselineY = px(layout.timeSignatureTopY), centred = true)
            drawGlyph(measurer, Glyph.timeSignature(signature.beatUnit), music, ink, x = centre, baselineY = px(layout.timeSignatureBottomY), centred = true)
        }

        for (barX in layout.barLineXs) {
            drawLine(ink, Offset(px(barX), px(layout.staffTopY)), Offset(px(barX), px(layout.staffBottomY)), strokeWidth = px(BAR_LINE_THICKNESS))
        }

        for (beam in layout.beams) {
            drawLine(
                ink,
                Offset(px(beam.startX), px(beam.startY)),
                Offset(px(beam.endX), px(beam.endY)),
                strokeWidth = px(StaffLayout.BEAM_THICKNESS),
            )
        }

        for (event in layout.events) {
            val colour = if (event.noteIndex == highlightNoteIndex) highlight else ink
            val x = px(event.x)
            val y = event.y
            val dotX = event.dotX
            val dotY = event.dotY
            if (dotX != null && dotY != null) {
                drawGlyph(measurer, Glyph.DOT, music, colour, x = px(dotX), baselineY = px(dotY), centred = true)
            }
            if (event.isRest) {
                drawGlyph(measurer, Glyph.rest(event.value), music, colour, x = x, baselineY = px(y), centred = true)
                continue
            }
            for (ledgerY in event.ledgerLineYs) {
                drawLine(
                    colour,
                    Offset(x - px(StaffLayout.LEDGER_HALF_WIDTH), px(ledgerY)),
                    Offset(x + px(StaffLayout.LEDGER_HALF_WIDTH), px(ledgerY)),
                    strokeWidth = px(LEDGER_LINE_THICKNESS),
                )
            }
            if (event.sharp) {
                drawGlyph(measurer, Glyph.SHARP, music, colour, x = x - px(SHARP_OFFSET), baselineY = px(y), centred = true)
            }
            val head = when (event.head) {
                NoteHead.Whole -> Glyph.NOTEHEAD_WHOLE
                NoteHead.Half -> Glyph.NOTEHEAD_HALF
                else -> Glyph.NOTEHEAD_BLACK
            }
            drawGlyph(measurer, head, music, colour, x = x, baselineY = px(y), centred = true)

            event.stem?.let { stem ->
                drawLine(colour, Offset(px(stem.x), px(stem.headY)), Offset(px(stem.x), px(stem.tipY)), strokeWidth = px(STEM_THICKNESS))
                Glyph.flag(stem.flags, stem.up)?.let { flag ->
                    drawGlyph(measurer, flag, music, colour, x = px(stem.x - STEM_THICKNESS / 2), baselineY = px(stem.tipY), centred = false)
                }
            }

            event.label?.let { label ->
                val text = measurer.measure(label, labelStyle.copy(color = colour))
                drawText(text, topLeft = Offset(x - text.size.width / 2f, px(layout.labelTop)))
            }
        }
    }
}

/** Draws one SMuFL glyph with its baseline (the glyph's origin) at [baselineY]. */
internal fun DrawScope.drawGlyph(
    measurer: TextMeasurer,
    glyph: String,
    style: TextStyle,
    colour: Color,
    x: Float,
    baselineY: Float,
    centred: Boolean,
) {
    val text = measurer.measure(glyph, style.copy(color = colour))
    val left = if (centred) x - text.size.width / 2f else x
    drawText(text, topLeft = Offset(left, baselineY - text.firstBaseline))
}

/** Bravura's engraving defaults, in staff spaces. */
internal const val STAFF_LINE_THICKNESS = 0.13
internal const val LEDGER_LINE_THICKNESS = 0.16
private const val STEM_THICKNESS = 0.12
private const val BAR_LINE_THICKNESS = 0.16

/** How far left of the note head's centre the sharp is centred. */
internal const val SHARP_OFFSET = 1.3

@Preview
@Composable
private fun TwinklePhrase1Preview() = PianoTrainerTheme {
    StaffView(SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"), highlightNoteIndex = 2, timeSignature = TimeSignature(4, 4))
}

@Preview
@Composable
private fun G3ToG5Preview() = PianoTrainerTheme {
    StaffView(SongFormat.parseEvents("G3/1 A3/1 B3/1 C4/1 D4/1 E4/1 F#4/1 G4/1 B4/1 D5/1 F5/1 G5/4"))
}

@Preview
@Composable
private fun BeamsPreview() = PianoTrainerTheme {
    // Prelude-style sixteenths, and Happy Birthday's dotted eighth + sixteenth.
    StaffView(
        SongFormat.parseEvents("C4/0.25 E4/0.25 G4/0.25 C5/0.25 E5/0.25 G4/0.25 C5/0.25 E5/0.25 G4/0.75 G4/0.25 A4/1 G4/1"),
        timeSignature = TimeSignature(4, 4),
    )
}

@Preview
@Composable
private fun RhythmPreview() = PianoTrainerTheme {
    StaffView(
        SongFormat.parseEvents("E4/1.5 D4/0.5 C4/1 R/1 G4/0.5 G4/0.5 A5/0.25 B5/0.25 C6/0.5 D5/2 R/2 C5/4"),
        timeSignature = TimeSignature(4, 4),
    )
}

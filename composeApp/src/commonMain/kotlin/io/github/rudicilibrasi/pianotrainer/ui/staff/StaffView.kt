package io.github.rudicilibrasi.pianotrainer.ui.staff

import androidx.compose.foundation.Canvas
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
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import org.jetbrains.compose.resources.Font
import pianotrainer.composeapp.generated.resources.Res
import pianotrainer.composeapp.generated.resources.bravura

/** SMuFL code points in the Bravura font. */
private object Glyph {
    const val G_CLEF = ""
    const val NOTEHEAD_WHOLE = ""
    const val NOTEHEAD_HALF = ""
    const val NOTEHEAD_BLACK = ""
    const val SHARP = ""
}

/**
 * A phrase drawn on a treble staff (#25): clef, staff lines, note heads, ledger lines and a
 * "C4"-style label under each note, matching the letter list on the lesson screen. Stems, flags,
 * dots, rests and bar lines come in #26.
 *
 * Glyphs come from the Bravura music font (SMuFL, SIL Open Font License; see
 * `files/licenses/bravura-ofl.txt`). A 6–8 note phrase fits a phone in portrait at the default
 * [staffSpace]; longer phrases scroll sideways.
 *
 * @param highlightNoteIndex index among the phrase's notes (rests not counted) to show in the
 *   highlight colour, e.g. the note sounding during reference playback (#31).
 */
@Composable
fun StaffView(
    events: List<NoteEvent>,
    modifier: Modifier = Modifier,
    highlightNoteIndex: Int? = null,
    staffSpace: Dp = 9.dp,
) {
    val layout = remember(events) { StaffLayout(events) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacePx = with(density) { staffSpace.toPx() }
    // SMuFL fonts are designed so that one em is four staff spaces.
    val music = TextStyle(fontFamily = FontFamily(Font(Res.font.bravura)), fontSize = with(density) { (spacePx * 4).toSp() })
    val labelStyle = MaterialTheme.typography.labelMedium
    val ink = MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.primary
    val description = "Treble staff: " + layout.events.mapNotNull { it.label }.joinToString(", ")

    Canvas(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .size(staffSpace * layout.width.toFloat(), staffSpace * layout.height.toFloat())
            .semantics { contentDescription = description },
    ) {
        fun px(spaces: Double) = (spaces * spacePx).toFloat()

        // Staff lines.
        for (step in layout.lineSteps) {
            val y = px(layout.y(step))
            drawLine(ink, Offset(0f, y), Offset(px(layout.width), y), strokeWidth = px(STAFF_LINE_THICKNESS))
        }

        drawGlyph(measurer, Glyph.G_CLEF, music, ink, x = px(layout.clefX), baselineY = px(layout.clefY), centred = false)

        for (event in layout.events) {
            val y = event.y ?: continue
            val colour = if (event.noteIndex == highlightNoteIndex) highlight else ink
            val x = px(event.x)
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

            event.label?.let { label ->
                val text = measurer.measure(label, labelStyle.copy(color = colour))
                drawText(text, topLeft = Offset(x - text.size.width / 2f, px(layout.labelTop)))
            }
        }
    }
}

/** Draws one SMuFL glyph with its baseline (the glyph's origin) at [baselineY]. */
private fun DrawScope.drawGlyph(
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
private const val STAFF_LINE_THICKNESS = 0.13
private const val LEDGER_LINE_THICKNESS = 0.16

/** How far left of the note head's centre the sharp is centred. */
private const val SHARP_OFFSET = 1.3

@Preview
@Composable
private fun TwinklePhrase1Preview() = PianoTrainerTheme {
    StaffView(SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"), highlightNoteIndex = 2)
}

@Preview
@Composable
private fun G3ToG5Preview() = PianoTrainerTheme {
    StaffView(SongFormat.parseEvents("G3/1 A3/1 B3/1 C4/1 D4/1 E4/1 F#4/1 G4/1 B4/1 D5/1 F5/1 G5/4"))
}

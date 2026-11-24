package io.github.rudicilibrasi.pianotrainer.ui.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.notation.Accidental
import io.github.rudicilibrasi.pianotrainer.core.notation.TrebleStaff
import io.github.rudicilibrasi.pianotrainer.core.scoring.Alignment
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep
import org.jetbrains.compose.resources.Font
import pianotrainer.composeapp.generated.resources.Res
import pianotrainer.composeapp.generated.resources.bravura

/** How a played note relates to the expected phrase. Each has its own colour and symbol. */
enum class PlayedMark(val symbol: String, val spoken: String) {
    Correct("●", "correct"),
    Wrong("✕", "wrong note"),
    Missed("□", "missed"),
    Extra("▲", "extra"),
    Uncertain("?", "not heard clearly"),
}

/**
 * One slot on the detected staff, in staff spaces, lined up under the expected staff.
 *
 * @property step staff position of the played note, or null for a missed note (nothing played) or
 *   a note with no pitch.
 */
data class PlayedSlot(
    val x: Double,
    val step: Int?,
    val sharp: Boolean,
    val ledgerLineYs: List<Double>,
    val y: Double?,
    val mark: PlayedMark,
    val label: String,
)

/**
 * Where the played notes go on the second staff (#44): each one under the expected note it was
 * aligned with (#39), extra notes between their neighbours, and a marked gap for each missed
 * note. Uses the expected staff's [StaffLayout] for horizontal positions and the same vertical
 * scale, so the two staves read as one comparison.
 */
class DetectedStaffModel(val expected: StaffLayout, val slots: List<PlayedSlot>, val width: Double) {

    fun y(step: Int): Double = expected.y(step)

    val description: String
        get() = "What you played: " + slots.joinToString(", ") { "${it.label} ${it.mark.spoken}" }

    companion object {
        fun of(expected: StaffLayout, alignment: Alignment, detected: List<DetectedNote>): DetectedStaffModel {
            val noteXs = expected.events.filter { !it.isRest }.map { it.x }
            val steps = alignment.steps
            // x of the expected note each step sits under, or null for an extra note.
            val anchors: List<Double?> = steps.map { step ->
                when (step) {
                    is AlignmentStep.Match -> noteXs[step.expectedIndex]
                    is AlignmentStep.Substitution -> noteXs[step.expectedIndex]
                    is AlignmentStep.Deletion -> noteXs[step.expectedIndex]
                    is AlignmentStep.Insertion -> null
                }
            }
            val lastX = noteXs.lastOrNull() ?: (StaffLayout.CLEF_WIDTH + StaffLayout.LEAD_IN)
            val xs = anchors.indices.map { i ->
                anchors[i] ?: run {
                    // Extra notes: evenly between the anchored notes either side (or after the end).
                    val before = (i - 1 downTo 0).firstOrNull { anchors[it] != null }
                    val after = (i + 1 until anchors.size).firstOrNull { anchors[it] != null }
                    val from = before?.let { anchors[it]!! } ?: (noteXs.firstOrNull()?.minus(StaffLayout.spacing(1.0)) ?: StaffLayout.CLEF_WIDTH)
                    val to = after?.let { anchors[it]!! } ?: (lastX + StaffLayout.spacing(1.0) * (anchors.size - (before ?: -1)))
                    val run = (before ?: -1) + 1 until (after ?: anchors.size)
                    from + (to - from) * (i - run.first + 1) / (run.count() + 1)
                }
            }

            val slots = steps.mapIndexed { i, step ->
                val note = when (step) {
                    is AlignmentStep.Match -> detected[step.detectedIndex]
                    is AlignmentStep.Substitution -> detected[step.detectedIndex]
                    is AlignmentStep.Insertion -> detected[step.detectedIndex]
                    is AlignmentStep.Deletion -> null
                }
                val pitch = note?.pitch
                val position = pitch?.let(TrebleStaff::position)
                val mark = when {
                    step is AlignmentStep.Deletion -> PlayedMark.Missed
                    note != null && !note.isReliable -> PlayedMark.Uncertain
                    step is AlignmentStep.Match -> PlayedMark.Correct
                    step is AlignmentStep.Substitution -> PlayedMark.Wrong
                    else -> PlayedMark.Extra
                }
                PlayedSlot(
                    x = xs[i],
                    step = position?.step,
                    sharp = position?.accidental == Accidental.SHARP,
                    ledgerLineYs = position?.ledgerLines?.map(expected::y).orEmpty(),
                    y = position?.step?.let(expected::y),
                    mark = mark,
                    label = when {
                        step is AlignmentStep.Deletion -> "—"
                        note != null && note.isReliable -> pitch!!.label
                        else -> "?"
                    },
                )
            }
            val width = maxOf(expected.width, (xs.maxOrNull() ?: 0.0) + StaffLayout.LAST_NOTE_ROOM)
            return DetectedStaffModel(expected, slots, width)
        }
    }
}

/**
 * The expected phrase on one staff and what the student played on a second staff below it (#44),
 * lined up note for note. Played notes are plain note heads (no rhythm), each with a colour and a
 * symbol — ● correct, ✕ wrong, □ missed, ▲ extra, ? not heard clearly — so the outcome never
 * depends on colour alone, and a letter label underneath.
 */
@Composable
fun ComparedStaves(
    phrase: Phrase,
    alignment: Alignment,
    detected: List<DetectedNote>,
    modifier: Modifier = Modifier,
    timeSignature: TimeSignature? = null,
    pickupBeats: Double = 0.0,
    staffSpace: Dp = 9.dp,
) {
    val layout = remember(phrase, timeSignature, pickupBeats) { StaffLayout(phrase.events, timeSignature, phrase.startBeat, pickupBeats) }
    val model = remember(layout, alignment, detected) { DetectedStaffModel.of(layout, alignment, detected) }
    val scroll = rememberScrollState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Expected", style = MaterialTheme.typography.labelLarge)
        // Both staves scroll together, so the notes stay lined up.
        StaffView(
            phrase.events,
            timeSignature = timeSignature,
            phraseStartBeat = phrase.startBeat,
            pickupBeats = pickupBeats,
            staffSpace = staffSpace,
            scrollState = scroll,
            minWidthSpaces = model.width,
        )
        Text("You played", style = MaterialTheme.typography.labelLarge)
        DetectedStaff(model, staffSpace, scroll)
        Text(
            PlayedMark.entries.joinToString(" · ") { "${it.symbol} ${it.spoken}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetectedStaff(model: DetectedStaffModel, staffSpace: Dp, scroll: ScrollState) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spacePx = with(density) { staffSpace.toPx() }
    val music = TextStyle(fontFamily = FontFamily(Font(Res.font.bravura)), fontSize = with(density) { (spacePx * 4).toSp() })
    val scheme = MaterialTheme.colorScheme
    val colours = mapOf(
        PlayedMark.Correct to scheme.primary,
        PlayedMark.Wrong to scheme.error,
        PlayedMark.Missed to scheme.outline,
        PlayedMark.Extra to scheme.tertiary,
        PlayedMark.Uncertain to scheme.secondary,
    )
    val ink = scheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium
    val layout = model.expected

    Canvas(
        Modifier.horizontalScroll(scroll)
            .size(staffSpace * model.width.toFloat(), staffSpace * layout.height.toFloat())
            .semantics { contentDescription = model.description },
    ) {
        fun px(spaces: Double) = (spaces * spacePx).toFloat()
        for (step in layout.lineSteps) {
            val y = px(layout.y(step))
            drawLine(ink, Offset(0f, y), Offset(px(model.width), y), strokeWidth = px(STAFF_LINE_THICKNESS))
        }
        drawGlyph(measurer, Glyph.G_CLEF, music, ink, x = px(layout.clefX), baselineY = px(layout.clefY), centred = false)

        val middleY = px(layout.y(TrebleStaff.MIDDLE_LINE))
        for (slot in model.slots) {
            val colour = colours.getValue(slot.mark)
            val x = px(slot.x)
            val headY = slot.y?.let(::px)
            for (ledgerY in slot.ledgerLineYs) {
                drawLine(colour, Offset(x - px(StaffLayout.LEDGER_HALF_WIDTH), px(ledgerY)), Offset(x + px(StaffLayout.LEDGER_HALF_WIDTH), px(ledgerY)), strokeWidth = px(LEDGER_LINE_THICKNESS))
            }
            if (headY != null && slot.mark != PlayedMark.Missed) {
                if (slot.sharp) drawGlyph(measurer, Glyph.SHARP, music, colour, x = x - px(SHARP_OFFSET), baselineY = headY, centred = true)
                drawGlyph(measurer, Glyph.NOTEHEAD_BLACK, music, colour, x = x, baselineY = headY, centred = true)
            }
            // The outcome symbol, just above the staff (or the note, if higher), so it never hides the head.
            val symbolY = minOf(px(layout.staffTopY) - px(1.2), (headY ?: middleY) - px(1.6))
            markSymbol(slot.mark, Offset(x, symbolY), px(0.55), colour, measurer, labelStyle)
            val text = measurer.measure(slot.label, labelStyle.copy(color = colour))
            drawText(text, topLeft = Offset(x - text.size.width / 2f, px(layout.labelTop)))
        }
    }
}

/** ● correct, ✕ wrong, □ missed, ▲ extra, ? uncertain. */
private fun DrawScope.markSymbol(
    mark: PlayedMark,
    centre: Offset,
    radius: Float,
    colour: Color,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    when (mark) {
        PlayedMark.Correct -> drawCircle(colour, radius, centre)
        PlayedMark.Wrong -> {
            drawLine(colour, centre + Offset(-radius, -radius), centre + Offset(radius, radius), strokeWidth = radius * 0.5f)
            drawLine(colour, centre + Offset(-radius, radius), centre + Offset(radius, -radius), strokeWidth = radius * 0.5f)
        }
        PlayedMark.Missed -> drawRect(colour, centre - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(width = radius * 0.4f))
        PlayedMark.Extra -> drawPath(
            Path().apply {
                moveTo(centre.x, centre.y - radius)
                lineTo(centre.x + radius, centre.y + radius)
                lineTo(centre.x - radius, centre.y + radius)
                close()
            },
            colour,
        )
        PlayedMark.Uncertain -> {
            val text = measurer.measure("?", style.copy(color = colour))
            drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
        }
    }
}

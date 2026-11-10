package io.github.rudicilibrasi.pianotrainer.ui.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import kotlin.math.log2

/**
 * One detected note as a bar on the piano roll, in 0–1 fractions of the plot area.
 *
 * @property row the note's row (see [PitchGraphModel.rows]), or null for a note with no pitch at
 *   all, which is drawn as a faint band across every row.
 */
data class NoteBar(val startX: Float, val endX: Float, val row: Int?, val uncertain: Boolean, val label: String)

/**
 * What the piano-roll pitch graph draws (#59), in 0–1 fractions, so it can be tested without
 * drawing: one row per semitone (at least G3–G5, widened to fit every note), seconds across.
 *
 * @property rows MIDI numbers from the top row down.
 * @property labelledRows rows labelled up the side: the white keys, by name ("G3", "A3", …).
 * @property pitchTrack runs of (x, y) points of the raw pitch, broken where nothing was voiced. y
 *   is continuous, so a slightly sharp note sits a little above its row's centre.
 */
data class PitchGraphModel(
    val rows: List<Int>,
    val labelledRows: List<Pair<Int, String>>,
    val bars: List<NoteBar>,
    val pitchTrack: List<List<Pair<Float, Float>>>,
    val ticks: List<Tick>,
    val tickXs: List<Float>,
    val durationSeconds: Double,
) {
    /** Vertical centre of [row] (0 is the top row). */
    fun rowCentreY(row: Int): Float = (row + 0.5f) / rows.size

    val rowHeight: Float get() = 1f / rows.size

    /** Plain-language summary for screen readers: which notes, and when. */
    val description: String
        get() = if (bars.isEmpty()) {
            "Pitch over time: no notes heard"
        } else {
            "Pitch over time: " + bars.joinToString(", ") { bar ->
                "${bar.label} at ${TimeAxis.label(bar.startX * durationSeconds)}"
            }
        }

    companion object {
        private val G3 = Pitch.parse("G3").midi
        private val G5 = Pitch.parse("G5").midi

        fun of(result: AnalysisResult): PitchGraphModel {
            val duration = result.durationSeconds
            val pitches = result.notes.mapNotNull { it.pitch?.midi }
            val low = minOf(G3, pitches.minOrNull() ?: G3)
            val high = maxOf(G5, pitches.maxOrNull() ?: G5)
            val rows = (high downTo low).toList()
            fun xOf(seconds: Double) = if (duration <= 0) 0f else (seconds / duration).toFloat().coerceIn(0f, 1f)

            val bars = result.notes.map { note ->
                NoteBar(
                    startX = xOf(note.startMs / 1000),
                    endX = xOf(note.endMs / 1000),
                    row = note.pitch?.let { high - it.midi },
                    uncertain = !note.isReliable,
                    label = if (note.isReliable) note.pitch!!.label else "?",
                )
            }

            // The raw pitch track, one point per graph column, as a continuous row position.
            val graphs = result.graphs
            val runs = ArrayList<List<Pair<Float, Float>>>()
            var run = ArrayList<Pair<Float, Float>>()
            for (c in 0 until graphs.columnCount) {
                val hz = graphs.pitchHz[c]
                val semitone = hz?.let { Pitch.A4_MIDI + 12 * log2(it / Pitch.A4_HZ) }
                if (semitone == null || semitone < low - 0.5 || semitone > high + 0.5) {
                    if (run.size >= 2) runs += run
                    run = ArrayList()
                    continue
                }
                val x = (c + 0.5f) / graphs.columnCount
                val y = ((high - semitone + 0.5) / rows.size).toFloat()
                run += x to y
            }
            if (run.size >= 2) runs += run

            val ticks = TimeAxis.ticks(duration)
            return PitchGraphModel(
                rows = rows,
                labelledRows = rows.withIndex().filter { (_, midi) -> Pitch(midi).name.length == 1 }.map { (i, midi) -> i to Pitch(midi).label },
                bars = bars,
                pitchTrack = runs,
                ticks = ticks,
                tickXs = ticks.map { xOf(it.seconds) },
                durationSeconds = duration,
            )
        }
    }
}

/**
 * A graph a beginner can read (spec §9, #59): which notes were played, and when. Piano-roll
 * style, with note names up the side and seconds along the bottom; each detected note is a bar,
 * uncertain notes are hatched, and a thin line shows the raw pitch.
 */
@Composable
fun PitchGraph(result: AnalysisResult, modifier: Modifier = Modifier, height: Dp = 260.dp) {
    val model = remember(result) { PitchGraphModel.of(result) }
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val barStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onPrimary)

    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = model.description }) {
        val labelWidth = measurer.measure("G#5", labelStyle).size.width + 6.dp.toPx()
        val timeLabelHeight = measurer.measure("0 s", labelStyle).size.height.toFloat()
        val left = labelWidth
        val plotWidth = size.width - left
        val plotHeight = size.height - timeLabelHeight - 4.dp.toPx()
        fun px(x: Float) = left + x * plotWidth
        fun py(y: Float) = y * plotHeight
        val rowPx = model.rowHeight * plotHeight

        // Row shading: black-key rows slightly darker, like a keyboard on its side.
        model.rows.forEachIndexed { row, midi ->
            if (Pitch(midi).name.length > 1) {
                drawRect(scheme.surfaceVariant, Offset(left, py(row * model.rowHeight)), Size(plotWidth, rowPx))
            }
        }
        // Note names for the white keys, centred on their rows, if there's room for them.
        if (rowPx * 2 >= measurer.measure("G3", labelStyle).size.height) {
            model.labelledRows.forEach { (row, name) ->
                val text = measurer.measure(name, labelStyle)
                drawText(text, topLeft = Offset(0f, py(model.rowCentreY(row)) - text.size.height / 2f))
            }
        }

        for (bar in model.bars) {
            val x0 = px(bar.startX)
            val width = (px(bar.endX) - x0).coerceAtLeast(2.dp.toPx())
            val row = bar.row
            val rect = if (row == null) {
                Rect(x0, 0f, x0 + width, plotHeight)
            } else {
                Rect(x0, py(row * model.rowHeight), x0 + width, py(row * model.rowHeight) + rowPx)
            }
            if (bar.uncertain) {
                hatch(rect, if (row == null) scheme.outline.copy(alpha = 0.5f) else scheme.tertiary)
            } else {
                drawRect(scheme.primary, rect.topLeft, rect.size)
                val text = measurer.measure(bar.label, barStyle)
                if (text.size.width < rect.width && text.size.height <= rect.height * 1.6f) {
                    drawText(text, topLeft = Offset(rect.left + 2.dp.toPx(), rect.center.y - text.size.height / 2f))
                }
            }
        }

        for (run in model.pitchTrack) {
            val path = Path().apply { run.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(px(x), py(y)) else lineTo(px(x), py(y)) } }
            drawPath(path, scheme.onSurface, style = Stroke(width = 1.dp.toPx()))
        }

        drawLine(scheme.onSurfaceVariant, Offset(left, plotHeight), Offset(size.width, plotHeight), strokeWidth = 1.dp.toPx())
        model.ticks.zip(model.tickXs).forEach { (tick, x) ->
            val text = measurer.measure(tick.label, labelStyle)
            val textLeft = (px(x) - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
            drawText(text, topLeft = Offset(textLeft, plotHeight + 4.dp.toPx()))
        }
    }
}

/** An outlined box filled with diagonal lines: "we're not sure about this note". */
private fun DrawScope.hatch(rect: Rect, colour: Color) {
    drawRect(colour, rect.topLeft, rect.size, style = Stroke(width = 1.dp.toPx()))
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        val spacing = 5.dp.toPx()
        var x = rect.left - rect.height
        while (x < rect.right) {
            drawLine(colour, Offset(x, rect.bottom), Offset(x + rect.height, rect.top), strokeWidth = 1.dp.toPx())
            x += spacing
        }
    }
}

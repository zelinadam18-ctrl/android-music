package io.github.rudicilibrasi.pianotrainer.ui.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult

/**
 * What the amplitude graph draws, in fractions of the plot area (0–1), so it can be tested
 * without drawing.
 *
 * @property levels one point per graph column: x across, y up (1 is 0 dBFS, 0 is [FLOOR_DB]).
 * @property onsetXs where each detected note starts.
 */
data class AmplitudeGraphModel(
    val levels: List<Pair<Float, Float>>,
    val onsetXs: List<Float>,
    val ticks: List<Tick>,
    val tickXs: List<Float>,
    val durationSeconds: Double,
) {
    companion object {
        /** The quietest level shown; anything below sits on the bottom edge. */
        const val FLOOR_DB = -60f

        fun of(result: AnalysisResult): AmplitudeGraphModel {
            val graphs = result.graphs
            val duration = result.durationSeconds
            val columns = graphs.columnCount
            val levels = List(columns) { c ->
                // Each column's centre.
                val x = if (columns == 0) 0f else (c + 0.5f) / columns
                val y = ((graphs.amplitudeDb[c].coerceIn(FLOOR_DB, 0f) - FLOOR_DB) / -FLOOR_DB)
                x to y
            }
            fun xOf(seconds: Double) = if (duration <= 0) 0f else (seconds / duration).toFloat().coerceIn(0f, 1f)
            val ticks = TimeAxis.ticks(duration)
            return AmplitudeGraphModel(
                levels = levels,
                onsetXs = result.onsets.map { xOf(it.timeSeconds) },
                ticks = ticks,
                tickXs = ticks.map { xOf(it.seconds) },
                durationSeconds = duration,
            )
        }
    }
}

/**
 * How loud the recording is over time (spec §9): the RMS level from −60 to 0 dBFS as a filled
 * envelope, with a mark at each detected note start (#56). Works the same for the reference and
 * the student's recording.
 */
@Composable
fun AmplitudeGraph(result: AnalysisResult, modifier: Modifier = Modifier, height: Dp = 160.dp) {
    val model = remember(result) { AmplitudeGraphModel.of(result) }
    val measurer = rememberTextMeasurer()
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val line = MaterialTheme.colorScheme.primary
    val onset = MaterialTheme.colorScheme.tertiary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axis)
    val description = "Loudness over ${TimeAxis.label(model.durationSeconds)}, with ${model.onsetXs.size} note starts marked"

    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val labelHeight = measurer.measure("0 s", labelStyle).size.height.toFloat()
        val plotBottom = size.height - labelHeight - 4.dp.toPx()
        val plotWidth = size.width
        fun px(x: Float) = x * plotWidth
        fun py(y: Float) = plotBottom * (1f - y)

        if (model.levels.isNotEmpty()) {
            val envelope = Path().apply {
                moveTo(px(model.levels.first().first), plotBottom)
                model.levels.forEach { (x, y) -> lineTo(px(x), py(y)) }
                lineTo(px(model.levels.last().first), plotBottom)
                close()
            }
            drawPath(envelope, fill)
            val outline = Path().apply {
                model.levels.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(px(x), py(y)) else lineTo(px(x), py(y)) }
            }
            drawPath(outline, line, style = Stroke(width = 1.5.dp.toPx()))
        }

        for (x in model.onsetXs) {
            drawLine(onset, Offset(px(x), 0f), Offset(px(x), plotBottom), strokeWidth = 1.dp.toPx())
        }

        drawLine(axis, Offset(0f, plotBottom), Offset(plotWidth, plotBottom), strokeWidth = 1.dp.toPx())
        val texts = model.ticks.map { measurer.measure(it.label, labelStyle) }
        model.tickXs.forEach { x -> drawLine(axis, Offset(px(x), plotBottom), Offset(px(x), plotBottom + 3.dp.toPx()), strokeWidth = 1.dp.toPx()) }
        TimeAxis.labelLefts(model.tickXs.map { px(it) }, texts.map { it.size.width.toFloat() }, 0f, plotWidth, 6.dp.toPx())
            .forEachIndexed { i, textLeft -> textLeft?.let { drawText(texts[i], topLeft = Offset(it, plotBottom + 4.dp.toPx())) } }
    }
}

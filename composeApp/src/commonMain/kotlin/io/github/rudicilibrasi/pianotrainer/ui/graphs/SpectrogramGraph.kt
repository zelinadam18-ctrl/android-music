package io.github.rudicilibrasi.pianotrainer.ui.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The magma colour map (perceptually uniform, readable in greyscale), from dark to bright. */
object Magma {
    // 16 evenly spaced samples (entries 0, 17, 34, … 255) of matplotlib's 256-entry magma, as 0xRRGGBB.
    private val STOPS = intArrayOf(
        0x000004, 0x0B0924, 0x20114B, 0x3B0F70, 0x57157E, 0x721F81, 0x8C2981, 0xA8327D,
        0xC43C75, 0xDE4968, 0xF1605D, 0xFA7F5E, 0xFE9F6D, 0xFEBF84, 0xFDDEA0, 0xFCFDBF,
    )

    /** Colour for [t] from 0 (quietest) to 1 (loudest), as 0xAARRGGBB. */
    fun argb(t: Float): Int {
        val scaled = t.coerceIn(0f, 1f) * (STOPS.size - 1)
        val i = min(scaled.toInt(), STOPS.size - 2)
        val f = scaled - i
        fun channel(shift: Int): Int {
            val a = (STOPS[i] shr shift) and 0xFF
            val b = (STOPS[i + 1] shr shift) and 0xFF
            return (a + (b - a) * f).roundToInt()
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/**
 * What the spectrogram draws (#55): loudness per time column and frequency row, already mapped
 * to 0–1 and limited to [maxHz].
 *
 * @property intensity `[column][row]`, row 0 the lowest frequency; 0 is [rangeDb] below the
 *   loudest cell, 1 the loudest.
 * @property hzTicks frequency axis labels with their height (0 bottom, 1 top).
 */
class SpectrogramModel(
    val intensity: Array<FloatArray>,
    val shownHz: Double,
    val hzTicks: List<Pair<Float, String>>,
    val ticks: List<Tick>,
    val tickXs: List<Float>,
    val durationSeconds: Double,
) {
    val columns: Int get() = intensity.size
    val rows: Int get() = intensity.firstOrNull()?.size ?: 0

    /** The image as 0xAARRGGBB pixels, top row first, ready for a bitmap. */
    fun pixels(): IntArray {
        val out = IntArray(columns * rows)
        for (c in 0 until columns) for (r in 0 until rows) out[(rows - 1 - r) * columns + c] = Magma.argb(intensity[c][r])
        return out
    }

    companion object {
        const val DEFAULT_MAX_HZ = 2000.0

        /** Levels more than this far below the loudest cell are drawn as the darkest colour. */
        const val RANGE_DB = 70f

        fun of(result: AnalysisResult, maxHz: Double = DEFAULT_MAX_HZ): SpectrogramModel {
            val graphs = result.graphs
            val rows = max(1, min(graphs.rowCount, (maxHz / graphs.rowHz).roundToInt()))
            val loudest = graphs.spectrogramDb.maxOfOrNull { column -> column.take(rows).maxOrNull() ?: Float.NEGATIVE_INFINITY } ?: 0f
            val intensity = Array(graphs.columnCount) { c ->
                FloatArray(rows) { r -> ((graphs.spectrogramDb[c][r] - (loudest - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f) }
            }
            val shownHz = rows * graphs.rowHz
            val step = if (shownHz > 1500) 500 else 250
            val hzTicks = (0..(shownHz / step).toInt()).map { i -> (i * step / shownHz).toFloat() to "${i * step}" }
            val duration = result.durationSeconds
            val ticks = TimeAxis.ticks(duration)
            return SpectrogramModel(
                intensity = intensity,
                shownHz = shownHz,
                hzTicks = hzTicks,
                ticks = ticks,
                tickXs = ticks.map { if (duration <= 0) 0f else (it.seconds / duration).toFloat() },
                durationSeconds = duration,
            )
        }
    }
}

/**
 * Frequency intensity over time (spec §9, #55): time across, frequency up (0–2 kHz, where our
 * notes and their first partials are), loudness as colour (magma). The image is built once as a
 * small bitmap, one pixel per column and frequency row, and scaled up when drawn, so scrolling
 * stays smooth.
 */
@Composable
fun SpectrogramGraph(result: AnalysisResult, modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val model = remember(result) { SpectrogramModel.of(result) }
    val bitmap = remember(model) { model.toBitmap() }
    val measurer = rememberTextMeasurer()
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = axis)
    val description = "Spectrogram over ${TimeAxis.label(model.durationSeconds)}, 0 to ${model.shownHz.roundToInt()} Hz"

    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val hzLabelWidth = measurer.measure("2000 Hz", labelStyle).size.width + 4.dp.toPx()
        val timeLabelHeight = measurer.measure("0 s", labelStyle).size.height.toFloat()
        val left = hzLabelWidth
        val plotWidth = size.width - left
        val plotHeight = size.height - timeLabelHeight - 4.dp.toPx()

        if (bitmap != null) {
            drawIntoCanvas { canvas ->
                canvas.drawImageRect(
                    bitmap,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(left.roundToInt(), 0),
                    dstSize = IntSize(plotWidth.roundToInt(), plotHeight.roundToInt()),
                    paint = Paint().apply { filterQuality = FilterQuality.Low },
                )
            }
        } else {
            drawRect(Color(Magma.argb(0f)), Offset(left, 0f), Size(plotWidth, plotHeight))
        }

        model.hzTicks.forEach { (y, label) ->
            val text = measurer.measure(if (y == 0f) "0 Hz" else label, labelStyle)
            val top = (plotHeight * (1 - y) - text.size.height / 2f).coerceIn(0f, plotHeight - text.size.height)
            drawText(text, topLeft = Offset(0f, top))
        }
        model.ticks.zip(model.tickXs).forEach { (tick, x) ->
            val text = measurer.measure(tick.label, labelStyle)
            val textLeft = (left + x * plotWidth - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
            drawText(text, topLeft = Offset(textLeft, plotHeight + 4.dp.toPx()))
        }
    }
}

/** A bitmap of [SpectrogramModel.pixels], or null for an empty recording. */
private fun SpectrogramModel.toBitmap(): ImageBitmap? {
    if (columns == 0 || rows == 0) return null
    val bitmap = ImageBitmap(columns, rows)
    val pixels = pixels()
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint()
    // Common code can't write pixels directly, so fill one-pixel rects; it's done once per recording.
    for (y in 0 until rows) for (x in 0 until columns) {
        paint.color = Color(pixels[y * columns + x])
        canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
    }
    return bitmap
}

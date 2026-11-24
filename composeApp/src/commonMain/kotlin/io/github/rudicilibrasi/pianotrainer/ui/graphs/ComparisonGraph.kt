package io.github.rudicilibrasi.pianotrainer.ui.graphs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.scoring.AlignmentStep
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScore

/** How a note fared in the alignment (#39). */
enum class NoteOutcome(val label: String) {
    Correct("correct"),
    Wrong("wrong note"),
    Missed("missed"),
    Extra("extra"),
}

/**
 * One note on the comparison graph, in 0–1 fractions. Expected notes ([expected] true) are
 * outlines; played notes are filled.
 *
 * @property row the note's row in [ComparisonModel.rows], or null for a played note with no pitch.
 */
data class CompareBar(
    val startX: Float,
    val endX: Float,
    val row: Int?,
    val expected: Boolean,
    val outcome: NoteOutcome,
    val label: String,
)

/** A line from an expected note's start to the start of the note played for it. */
data class PairLine(val expectedX: Float, val expectedRow: Int, val playedX: Float, val playedRow: Int?, val outcome: NoteOutcome)

/**
 * What the reference-vs-you graph draws (#58): expected and played notes on the same piano-roll
 * rows, played times mapped back through the tempo fit from #40 so a slower performance still
 * lines up (or left as recorded when [aligned] is false), lines joining each aligned pair, and an
 * outcome for every note.
 */
data class ComparisonModel(
    val rows: List<Int>,
    val labelledRows: List<Pair<Int, String>>,
    val bars: List<CompareBar>,
    val pairs: List<PairLine>,
    val ticks: List<Tick>,
    val tickXs: List<Float>,
    val durationSeconds: Double,
    val aligned: Boolean,
) {
    fun rowCentreY(row: Int): Float = (row + 0.5f) / rows.size
    val rowHeight: Float get() = 1f / rows.size

    val counts: Map<NoteOutcome, Int>
        get() = NoteOutcome.entries.associateWith { outcome ->
            bars.count { it.outcome == outcome && (it.expected || outcome == NoteOutcome.Extra) }
        }

    val description: String
        get() {
            val c = counts
            return "Comparison with the reference: ${c[NoteOutcome.Correct]} correct, ${c[NoteOutcome.Wrong]} wrong, " +
                "${c[NoteOutcome.Missed]} missed, ${c[NoteOutcome.Extra]} extra"
        }

    companion object {
        private val G3 = Pitch.parse("G3").midi
        private val G5 = Pitch.parse("G5").midi

        fun of(phrase: Phrase, tempoBpm: Double, detected: List<DetectedNote>, score: PhraseScore, aligned: Boolean = true): ComparisonModel {
            val expected = phrase.timedEvents(tempoBpm).filter { !it.event.isRest }
            val ratio = score.timing.tempoRatio
            val offsetMs = score.timing.offsetMs
            // Played time → reference time: undo the fit userTime = a · expectedTime + b. When
            // raw, only shift by where the student started, so both begin together.
            val firstStart = detected.firstOrNull()?.startMs ?: 0.0
            fun playedMs(ms: Double): Double = when {
                aligned && ratio != null && offsetMs != null && ratio > 0 -> (ms - offsetMs) / ratio
                else -> ms - (offsetMs ?: firstStart)
            }

            val pitches = expected.map { it.event.pitch!!.midi } + detected.mapNotNull { it.pitch?.midi }
            val high = maxOf(G5, pitches.maxOrNull() ?: G5)
            val low = minOf(G3, pitches.minOrNull() ?: G3)
            val rows = (high downTo low).toList()
            fun rowOf(pitch: Pitch?) = pitch?.let { high - it.midi }

            val expectedEnd = expected.maxOfOrNull { it.endMs } ?: 0.0
            val playedEnd = detected.maxOfOrNull { playedMs(it.endMs) } ?: 0.0
            val playedStart = detected.minOfOrNull { playedMs(it.startMs) } ?: 0.0
            val originMs = minOf(0.0, playedStart)
            val spanMs = (maxOf(expectedEnd, playedEnd) - originMs).coerceAtLeast(1.0)
            fun xOf(ms: Double) = ((ms - originMs) / spanMs).toFloat().coerceIn(0f, 1f)

            val bars = ArrayList<CompareBar>()
            val pairs = ArrayList<PairLine>()
            for (step in score.alignment.steps) {
                when (step) {
                    is AlignmentStep.Match, is AlignmentStep.Substitution -> {
                        val (e, d) = when (step) {
                            is AlignmentStep.Match -> step.expectedIndex to step.detectedIndex
                            is AlignmentStep.Substitution -> step.expectedIndex to step.detectedIndex
                            else -> error("unreachable")
                        }
                        val outcome = if (step is AlignmentStep.Match) NoteOutcome.Correct else NoteOutcome.Wrong
                        val exp = expected[e]
                        val played = detected[d]
                        bars += CompareBar(xOf(exp.startMs), xOf(exp.endMs), rowOf(exp.event.pitch), expected = true, outcome, exp.event.pitch!!.label)
                        bars += CompareBar(
                            xOf(playedMs(played.startMs)), xOf(playedMs(played.endMs)), rowOf(played.pitch),
                            expected = false, outcome, if (played.isReliable) played.pitch!!.label else "?",
                        )
                        pairs += PairLine(xOf(exp.startMs), rowOf(exp.event.pitch)!!, xOf(playedMs(played.startMs)), rowOf(played.pitch), outcome)
                    }
                    is AlignmentStep.Deletion -> {
                        val exp = expected[step.expectedIndex]
                        bars += CompareBar(xOf(exp.startMs), xOf(exp.endMs), rowOf(exp.event.pitch), expected = true, NoteOutcome.Missed, exp.event.pitch!!.label)
                    }
                    is AlignmentStep.Insertion -> {
                        val played = detected[step.detectedIndex]
                        bars += CompareBar(
                            xOf(playedMs(played.startMs)), xOf(playedMs(played.endMs)), rowOf(played.pitch),
                            expected = false, NoteOutcome.Extra, if (played.isReliable) played.pitch!!.label else "?",
                        )
                    }
                }
            }

            val durationSeconds = spanMs / 1000
            val ticks = TimeAxis.ticks(durationSeconds)
            return ComparisonModel(
                rows = rows,
                labelledRows = rows.withIndex().filter { (_, midi) -> Pitch(midi).name.length == 1 }.map { (i, midi) -> i to Pitch(midi).label },
                bars = bars,
                pairs = pairs,
                ticks = ticks,
                tickXs = ticks.map { (it.seconds / durationSeconds).toFloat() },
                durationSeconds = durationSeconds,
                aligned = aligned,
            )
        }
    }
}

/**
 * Expected and played notes against time (spec §9, §11, #58), so the student can see wrong
 * notes, order mistakes, and early or late notes. A toggle switches between times matched to the
 * reference's tempo and raw recorded times.
 */
@Composable
fun ComparisonGraph(
    phrase: Phrase,
    tempoBpm: Double,
    detected: List<DetectedNote>,
    score: PhraseScore,
    modifier: Modifier = Modifier,
    height: Dp = 260.dp,
) {
    var aligned by remember { mutableStateOf(true) }
    val model = remember(phrase, detected, score, aligned) { ComparisonModel.of(phrase, tempoBpm, detected, score, aligned) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = aligned, onClick = { aligned = true }, label = { Text("Matched to tempo") })
            FilterChip(selected = !aligned, onClick = { aligned = false }, label = { Text("As recorded") })
        }
        ComparisonCanvas(model, height)
        Legend()
    }
}

@Composable
private fun ComparisonCanvas(model: ComparisonModel, height: Dp) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val colours = outcomeColours()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)

    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = model.description }) {
        val left = measurer.measure("G#5", labelStyle).size.width + 6.dp.toPx()
        val plotWidth = size.width - left
        val plotHeight = size.height - measurer.measure("0 s", labelStyle).size.height - 4.dp.toPx()
        val rowPx = model.rowHeight * plotHeight
        fun px(x: Float) = left + x * plotWidth
        fun rowTop(row: Int) = row * rowPx

        model.rows.forEachIndexed { row, midi ->
            if (Pitch(midi).name.length > 1) drawRect(scheme.surfaceVariant, Offset(left, rowTop(row)), Size(plotWidth, rowPx))
        }
        if (rowPx * 2 >= measurer.measure("G3", labelStyle).size.height) {
            model.labelledRows.forEach { (row, name) ->
                val text = measurer.measure(name, labelStyle)
                drawText(text, topLeft = Offset(0f, rowTop(row) + rowPx / 2 - text.size.height / 2f))
            }
        }

        for (pair in model.pairs) {
            val from = Offset(px(pair.expectedX), rowTop(pair.expectedRow) + rowPx / 2)
            val to = Offset(px(pair.playedX), pair.playedRow?.let { rowTop(it) + rowPx / 2 } ?: plotHeight / 2)
            drawLine(colours.getValue(pair.outcome).copy(alpha = 0.7f), from, to, strokeWidth = 1.dp.toPx())
        }

        for (bar in model.bars) {
            val colour = colours.getValue(bar.outcome)
            val x0 = px(bar.startX)
            val width = (px(bar.endX) - x0).coerceAtLeast(3.dp.toPx())
            val top = bar.row?.let(::rowTop) ?: 0f
            val barHeight = if (bar.row == null) plotHeight else rowPx
            if (bar.expected) {
                // Expected: an outline; dashed when it was missed.
                val effect = if (bar.outcome == NoteOutcome.Missed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
                drawRect(colour, Offset(x0, top), Size(width, barHeight), style = Stroke(width = 1.5.dp.toPx(), pathEffect = effect))
            } else {
                drawRect(colour.copy(alpha = 0.8f), Offset(x0, top + barHeight * 0.2f), Size(width, barHeight * 0.6f))
            }
            // Shape at the note's start, so the outcome doesn't rely on colour alone.
            if (!bar.expected || bar.outcome == NoteOutcome.Missed) {
                marker(bar.outcome, Offset(x0, top + barHeight / 2), (rowPx * 0.45f).coerceIn(3.dp.toPx(), 6.dp.toPx()), colour)
            }
        }

        drawLine(scheme.onSurfaceVariant, Offset(left, plotHeight), Offset(size.width, plotHeight), strokeWidth = 1.dp.toPx())
        model.ticks.zip(model.tickXs).forEach { (tick, x) ->
            val text = measurer.measure(tick.label, labelStyle)
            drawText(text, topLeft = Offset((px(x) - text.size.width / 2f).coerceIn(left, size.width - text.size.width), plotHeight + 4.dp.toPx()))
        }
    }
}

@Composable
private fun outcomeColours(): Map<NoteOutcome, Color> {
    val scheme = MaterialTheme.colorScheme
    return mapOf(
        NoteOutcome.Correct to scheme.primary,
        NoteOutcome.Wrong to scheme.error,
        NoteOutcome.Missed to scheme.outline,
        NoteOutcome.Extra to scheme.tertiary,
    )
}

/** Correct ●, wrong ✕, missed □, extra ▲. */
private fun DrawScope.marker(outcome: NoteOutcome, centre: Offset, radius: Float, colour: Color) {
    when (outcome) {
        NoteOutcome.Correct -> drawCircle(colour, radius, centre)
        NoteOutcome.Wrong -> {
            val stroke = radius * 0.5f
            drawLine(colour, centre + Offset(-radius, -radius), centre + Offset(radius, radius), strokeWidth = stroke)
            drawLine(colour, centre + Offset(-radius, radius), centre + Offset(radius, -radius), strokeWidth = stroke)
        }
        NoteOutcome.Missed -> drawRect(colour, centre - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(width = radius * 0.4f))
        NoteOutcome.Extra -> drawPath(
            Path().apply {
                moveTo(centre.x, centre.y - radius)
                lineTo(centre.x + radius, centre.y + radius)
                lineTo(centre.x - radius, centre.y + radius)
                close()
            },
            colour,
        )
    }
}

@Composable
private fun Legend() {
    Text(
        "Outline: expected note · Filled: what you played · ● correct · ✕ wrong note · □ missed · ▲ extra",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

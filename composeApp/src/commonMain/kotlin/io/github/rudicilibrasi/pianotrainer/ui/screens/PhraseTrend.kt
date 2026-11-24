package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.database.Attempt

/**
 * How one attempt compares with the other attempts on the same phrase (#63).
 *
 * @property sinceLastText "+8 since last time", "−5 since last time", "Same as last time", or
 *   null for the first attempt.
 * @property bestText "Best so far: 84 (this attempt)" — counting only attempts up to this one, so
 *   an old attempt in history shows what was true then.
 * @property scores every score on the phrase, oldest first, for the chart.
 * @property currentIndex this attempt's position in [scores].
 */
data class PhraseTrend(
    val sinceLastText: String?,
    val bestText: String,
    val scores: List<Int>,
    val currentIndex: Int,
) {
    val attemptText: String get() = "Attempt ${currentIndex + 1} of ${scores.size} on this phrase"

    companion object {
        /** [attempts] on one phrase in any order; [current] must be one of them. */
        fun of(attempts: List<Attempt>, current: Attempt): PhraseTrend {
            val ordered = attempts.sortedWith(compareBy<Attempt> { it.createdAt }.thenBy { it.id })
            val index = ordered.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
            val scores = ordered.map { it.scores.score }
            val score = current.scores.score
            val previous = ordered.getOrNull(index - 1)?.scores?.score
            val sinceLast = previous?.let {
                val change = score - it
                when {
                    change > 0 -> "+$change since last time"
                    change < 0 -> "−${-change} since last time"
                    else -> "Same as last time"
                }
            }
            val bestBefore = scores.take(index).maxOrNull()
            val best = maxOf(score, bestBefore ?: score)
            val bestText = if (bestBefore == null || score > bestBefore) "Best so far: $best (this attempt)" else "Best so far: $best"
            return PhraseTrend(sinceLast, bestText, scores, index)
        }
    }
}

/** "+8 since last time", the best so far, and a small chart of the phrase's scores over time. */
@Composable
fun PhraseTrendView(trend: PhraseTrend, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        trend.sinceLastText?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        Text(trend.bestText, style = MaterialTheme.typography.bodyMedium)
        Text(trend.attemptText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        if (trend.scores.size >= 2) {
            val description = "Scores on this phrase, oldest first: " + trend.scores.joinToString(", ")
            Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = description }) {
                val n = trend.scores.size
                fun x(i: Int) = if (n == 1) size.width / 2 else i * size.width / (n - 1)
                fun y(score: Int) = size.height * (1f - score / 100f)
                // The unlock line at 50.
                val unlockY = y(Progression.UNLOCK_SCORE)
                drawLine(
                    scheme.outline, Offset(0f, unlockY), Offset(size.width, unlockY),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
                val path = Path().apply { trend.scores.forEachIndexed { i, s -> if (i == 0) moveTo(x(i), y(s)) else lineTo(x(i), y(s)) } }
                drawPath(path, scheme.primary, style = Stroke(width = 2.dp.toPx()))
                trend.scores.forEachIndexed { i, s ->
                    val current = i == trend.currentIndex
                    drawCircle(if (current) scheme.tertiary else scheme.primary, if (current) 5.dp.toPx() else 3.dp.toPx(), Offset(x(i), y(s)))
                }
            }
        }
    }
}

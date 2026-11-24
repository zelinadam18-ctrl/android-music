package io.github.rudicilibrasi.pianotrainer.ui.graphs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScore

/** The graphs the switcher offers (spec §9). */
enum class GraphKind(val label: String) {
    Spectrogram("Spectrogram"),
    Amplitude("Amplitude"),
    Pitch("Pitch"),
    Compare("Compare"),
}

/** Whose recording a per-recording graph shows. */
enum class GraphSource(val label: String) { Reference("Reference"), You("Your recording") }

/** What the student just did, for the Compare graph. */
data class ComparisonInput(val phrase: Phrase, val tempoBpm: Double, val detected: List<DetectedNote>, val score: PhraseScore)

/**
 * Which graphs and sources a screen can show, from the data it has (#59): the lesson screen has
 * only the reference, so it shows the three per-recording graphs; the results screen also has the
 * student's recording and the comparison, so it shows all four.
 */
data class GraphChoices(val kinds: List<GraphKind>, val sources: List<GraphSource>) {
    /** The Reference / Your recording toggle only makes sense with both, and not for Compare. */
    fun showsSourceToggle(kind: GraphKind): Boolean = kind != GraphKind.Compare && sources.size > 1

    companion object {
        fun of(reference: AnalysisResult?, yours: AnalysisResult?, comparison: ComparisonInput?): GraphChoices {
            val sources = listOfNotNull(reference?.let { GraphSource.Reference }, yours?.let { GraphSource.You })
            val kinds = buildList {
                if (sources.isNotEmpty()) addAll(listOf(GraphKind.Spectrogram, GraphKind.Amplitude, GraphKind.Pitch))
                if (comparison != null) add(GraphKind.Compare)
            }
            return GraphChoices(kinds, sources)
        }
    }
}

/**
 * All graphs without cluttering a phone screen (spec §9, §15, #59): a segmented control picks
 * one graph at a time, a Reference / Your recording toggle picks the recording where that applies,
 * and tapping the graph opens it full screen.
 *
 * Pass whatever the screen has: the lesson screen passes only [reference]; the results screen
 * passes all three. Nothing is shown when there's nothing to draw.
 */
@Composable
fun GraphSwitcher(
    reference: AnalysisResult?,
    yours: AnalysisResult?,
    comparison: ComparisonInput?,
    modifier: Modifier = Modifier,
) {
    val choices = GraphChoices.of(reference, yours, comparison)
    if (choices.kinds.isEmpty()) return
    var kind by rememberSaveable { mutableStateOf(choices.kinds.last()) }
    var source by rememberSaveable { mutableStateOf(choices.sources.last()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (kind !in choices.kinds) kind = choices.kinds.last()
    if (source !in choices.sources && choices.sources.isNotEmpty()) source = choices.sources.last()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedRow(choices.kinds, kind, { it.label }) { kind = it }
        if (choices.showsSourceToggle(kind)) SegmentedRow(choices.sources, source, { it.label }) { source = it }
        Box(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Show full screen") { expanded = true },
        ) {
            Graph(kind, if (source == GraphSource.Reference) reference ?: yours else yours ?: reference, comparison)
        }
        Text("Tap the graph to see it full screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { expanded = false }) { Text("Close") }
                    Text(
                        kind.label + if (choices.showsSourceToggle(kind) || kind != GraphKind.Compare) " · ${source.label}" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Graph(kind, if (source == GraphSource.Reference) reference ?: yours else yours ?: reference, comparison, tall = true)
                }
            }
        }
    }
}

@Composable
private fun Graph(kind: GraphKind, result: AnalysisResult?, comparison: ComparisonInput?, tall: Boolean = false) {
    when (kind) {
        GraphKind.Spectrogram -> result?.let { SpectrogramGraph(it, height = if (tall) 420.dp else 200.dp) }
        GraphKind.Amplitude -> result?.let { AmplitudeGraph(it, height = if (tall) 320.dp else 160.dp) }
        GraphKind.Pitch -> result?.let { PitchGraph(it, height = if (tall) 520.dp else 260.dp) }
        GraphKind.Compare -> comparison?.let { ComparisonGraph(it.phrase, it.tempoBpm, it.detected, it.score, height = if (tall) 520.dp else 260.dp) }
    }
}

/** A Material segmented control; labels shrink to one line and stay readable at large font sizes. */
@Composable
private fun <T> SegmentedRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

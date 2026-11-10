package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.database.ProgressDates
import io.github.rudicilibrasi.pianotrainer.database.ProgressReader
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** Everything the song completion screen shows (spec §6, §12, #55). */
data class SongCompleteState(
    val title: String,
    val completed: Boolean,
    val overallScore: Int?,
    val phrasesText: String,
    val starEarned: Boolean,
    /** "Star earned on 9 Nov 2026, 14:03", when it was earned. */
    val starText: String?,
    /** The headline: celebration, "need 90", or "not finished yet". */
    val headline: String,
    /** Weakest phrases first: "Phrase 3: best 80". */
    val weakestPhrases: List<String>,
    /** Which part of the score cost the most points, or null without attempts. */
    val commonMistake: String?,
    val recommendations: List<String>,
) {
    companion object {
        /** How many of the weakest phrases to name. */
        private const val WEAKEST = 2

        fun of(song: Song, progress: SongProgress, attempts: List<Attempt>, dates: ProgressDates?, timeZone: TimeZone): SongCompleteState {
            val count = progress.phraseCount
            val done = progress.completedPhraseCount
            val overall = progress.overallScore
            val ranked = progress.phrases.withIndex()
                .filter { it.value.bestScore != null }
                .sortedWith(compareBy({ it.value.bestScore }, { it.index }))
            // Weakest = below the star line, so a 100 is never called weak.
            val weakest = ranked.filter { it.value.bestScore!! < Progression.STAR_SCORE }.take(WEAKEST)
            val weakestNumbers = weakest.map { it.index + 1 }
            fun phraseList(numbers: List<Int>) = when (numbers.size) {
                0 -> ""
                1 -> "phrase ${numbers[0]}"
                else -> "phrases ${numbers.dropLast(1).joinToString(", ")} and ${numbers.last()}"
            }

            val headline = when {
                !progress.completed -> "Keep going! Complete every phrase to finish the song ($done of $count so far)."
                progress.starEarned -> "Song mastered! You earned the star for ${song.title}."
                else -> "Song complete! You need ${Progression.STAR_SCORE} overall for the mastery star." +
                    (if (weakestNumbers.isNotEmpty()) " Replay ${phraseList(weakestNumbers)} to raise your score." else "")
            }

            val recommendations = buildList {
                if (!progress.completed) {
                    add("Continue with phrase ${progress.currentPhraseIndex + 1}.")
                } else if (weakestNumbers.isNotEmpty()) {
                    add("Replay ${phraseList(weakestNumbers)}: ${if (weakestNumbers.size == 1) "it has" else "they have"} the lowest best scores.")
                }
                commonMistakeArea(song, attempts)?.let { add(it.tip) }
            }

            return SongCompleteState(
                title = song.title,
                completed = progress.completed,
                overallScore = overall,
                phrasesText = "$done of $count ${if (count == 1) "phrase" else "phrases"} completed",
                starEarned = progress.starEarned,
                starText = dates?.starEarnedAt?.takeIf { progress.starEarned }?.let { "Star earned on ${formatDateTime(it, timeZone)}" },
                headline = headline,
                weakestPhrases = weakest.map { "Phrase ${it.index + 1}: best ${it.value.bestScore}" },
                commonMistake = commonMistakeArea(song, attempts)?.summary,
                recommendations = recommendations,
            )
        }

        private enum class Area(val summary: String, val tip: String) {
            Notes("Most points were lost on wrong or missed notes.", "Practise slowly and check each note on the staff before you play it."),
            Order("Most points were lost on note order.", "Say the note names out loud in order before you play."),
            Timing("Most points were lost on rhythm.", "Listen to the reference and tap along before you play."),
            Tempo("Most points were lost on tempo.", "Play along with the reference to match its speed."),
        }

        /**
         * The score component with the most points lost, over each phrase's best attempt,
         * weighted as in the score formula.
         */
        private fun commonMistakeArea(song: Song, attempts: List<Attempt>): Area? {
            val best = attempts.filter { it.songId == song.id && it.phraseIndex != null }
                .groupBy { it.phraseIndex }
                .values.map { list -> list.maxWith(compareBy({ it.scores.score }, { it.createdAt })) }
            if (best.isEmpty()) return null
            val lost = mapOf(
                Area.Notes to best.sumOf { (1 - it.scores.noteAccuracy) * 45 },
                Area.Order to best.sumOf { (1 - it.scores.orderAccuracy) * 20 },
                Area.Timing to best.sumOf { (1 - it.scores.timingAccuracy) * 20 },
                Area.Tempo to best.sumOf { (1 - it.scores.tempoAccuracy) * 15 },
            )
            val worst = lost.maxBy { it.value }
            return worst.key.takeIf { worst.value > 0.5 }
        }
    }
}

sealed interface SongCompleteUiState {
    data object Loading : SongCompleteUiState
    data class Loaded(val state: SongCompleteState) : SongCompleteUiState
    data class Error(val message: String) : SongCompleteUiState
}

class SongCompleteViewModel(
    private val songId: String,
    private val catalog: SongCatalog,
    private val progress: ProgressReader,
    private val history: AttemptHistory,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<SongCompleteUiState>(SongCompleteUiState.Loading)
    val uiState: StateFlow<SongCompleteUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val song = try {
                catalog.loadSongs().firstOrNull { it.id == songId }
            } catch (e: Exception) {
                _uiState.value = SongCompleteUiState.Error("The song couldn't be loaded.\n(${e.message})")
                return@launch
            }
            if (song == null) {
                _uiState.value = SongCompleteUiState.Error("This song isn't available.")
                return@launch
            }
            progress.observe(song).combine(history.observeForSong(songId)) { p, a -> p to a }.collect { (p, a) ->
                _uiState.value = SongCompleteUiState.Loaded(SongCompleteState.of(song, p, a, progress.dates(songId), timeZone))
            }
        }
    }
}

/** Shown the first time the last phrase is completed, and reachable later from song detail. */
@Composable
fun SongCompleteScreen(
    songId: String,
    container: AppContainer,
    viewModel: SongCompleteViewModel = viewModel {
        SongCompleteViewModel(songId, container.songCatalog, container.progress, container.attemptHistory)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        SongCompleteUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is SongCompleteUiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is SongCompleteUiState.Loaded -> SongCompleteContent(current.state)
    }
}

@Composable
fun SongCompleteContent(state: SongCompleteState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.starEarned) {
            Icon(Icons.Filled.Star, contentDescription = "Star earned", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(96.dp))
        }
        Text(state.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(state.headline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        state.starText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.overallScore?.let { Text("Overall score: $it/100", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center) }
        Text(state.phrasesText, style = MaterialTheme.typography.bodyLarge)

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.weakestPhrases.isNotEmpty()) {
                Text("Lowest scores", style = MaterialTheme.typography.titleSmall)
                state.weakestPhrases.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            state.commonMistake?.let {
                Text("Most common mistake", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.recommendations.isNotEmpty()) {
                Text("What to do next", style = MaterialTheme.typography.titleSmall)
                state.recommendations.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Preview
@Composable
private fun StarPreview() = PianoTrainerTheme {
    SongCompleteContent(
        SongCompleteState(
            title = "Twinkle, Twinkle, Little Star", completed = true, overallScore = 93, phrasesText = "6 of 6 phrases completed",
            starEarned = true, starText = "Star earned on 9 Nov 2026, 14:03", headline = "Song mastered! You earned the star for Twinkle, Twinkle, Little Star.",
            weakestPhrases = listOf("Phrase 4: best 86"), commonMistake = "Most points were lost on rhythm.",
            recommendations = listOf("Listen to the reference and tap along before you play."),
        ),
    )
}

@Preview
@Composable
private fun NoStarPreview() = PianoTrainerTheme {
    SongCompleteContent(
        SongCompleteState(
            title = "Twinkle, Twinkle, Little Star", completed = true, overallScore = 82, phrasesText = "6 of 6 phrases completed",
            starEarned = false, starText = null,
            headline = "Song complete! You need 90 overall for the mastery star. Replay phrases 3 and 5 to raise your score.",
            weakestPhrases = listOf("Phrase 3: best 64", "Phrase 5: best 71"), commonMistake = "Most points were lost on wrong or missed notes.",
            recommendations = listOf("Replay phrases 3 and 5: they have the lowest best scores.", "Practise slowly and check each note on the staff before you play it."),
        ),
    )
}

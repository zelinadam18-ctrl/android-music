package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How far a student has got with a song. */
enum class SongStatus(val label: String) {
    NotStarted("Not started"),
    InProgress("In progress"),
    Completed("Completed"),
}

/** Everything one song card shows. */
data class SongCardState(
    val songId: String,
    val title: String,
    val composer: String?,
    val completedPhrases: Int,
    val phraseCount: Int,
    val bestOverallScore: Int?,
    val status: SongStatus,
    val starEarned: Boolean,
) {
    /** "3 of 6 phrases completed". */
    val progressText: String
        get() = "$completedPhrases of $phraseCount ${if (phraseCount == 1) "phrase" else "phrases"} completed"

    /** "Best score 87", or null before any attempt. */
    val bestScoreText: String? get() = bestOverallScore?.let { "Best score $it" }

    companion object {
        /** The card for [song] with [progress]; without progress (until #54), the song is not started. */
        fun of(song: Song, progress: SongProgress = SongProgress.initial(song)) = SongCardState(
            songId = song.id,
            title = song.title,
            composer = song.composer,
            completedPhrases = progress.completedPhraseCount,
            phraseCount = progress.phraseCount,
            bestOverallScore = progress.overallScore,
            status = when {
                progress.completed -> SongStatus.Completed
                progress.started -> SongStatus.InProgress
                else -> SongStatus.NotStarted
            },
            starEarned = progress.starEarned,
        )
    }
}

sealed interface SongsUiState {
    data object Loading : SongsUiState
    data class Loaded(val songs: List<SongCardState>) : SongsUiState
    data class Error(val message: String) : SongsUiState
}

class SongsViewModel(private val catalog: SongCatalog = SongCatalog.bundled()) : ViewModel() {
    private val _uiState = MutableStateFlow<SongsUiState>(SongsUiState.Loading)
    val uiState: StateFlow<SongsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = try {
                // Every song shows as not started until saved progress is wired up (#54).
                SongsUiState.Loaded(catalog.loadSongs().map { SongCardState.of(it) })
            } catch (e: Exception) {
                SongsUiState.Error("The songs couldn't be loaded. Please reinstall the app if this keeps happening.\n(${e.message})")
            }
        }
    }
}

/** The home screen: every bundled song, in menu order, with its progress. */
@Composable
fun SongsScreen(
    onOpenSong: (songId: String) -> Unit,
    viewModel: SongsViewModel = viewModel { SongsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SongsContent(state, onOpenSong)
}

@Composable
fun SongsContent(state: SongsUiState, onOpenSong: (songId: String) -> Unit) {
    when (state) {
        SongsUiState.Loading -> CenteredMessage { CircularProgressIndicator() }
        is SongsUiState.Error -> CenteredMessage { Text(state.message, textAlign = TextAlign.Center) }
        is SongsUiState.Loaded -> if (state.songs.isEmpty()) {
            CenteredMessage { Text("No songs yet.", textAlign = TextAlign.Center) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.songs, key = { it.songId }) { card -> SongCard(card, onClick = { onOpenSong(card.songId) }) }
            }
        }
    }
}

@Composable
fun SongCard(card: SongCardState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Title and star wrap rather than truncate, so large system fonts stay readable.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                if (card.starEarned) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics { contentDescription = "Star earned" },
                    )
                }
            }
            card.composer?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(card.status.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(card.progressText, style = MaterialTheme.typography.bodyMedium)
            card.bestScoreText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

private fun previewCard(completed: Int, best: Int?, status: SongStatus, star: Boolean = false) = SongCardState(
    songId = "twinkle",
    title = "Twinkle, Twinkle, Little Star",
    composer = "Traditional",
    completedPhrases = completed,
    phraseCount = 6,
    bestOverallScore = best,
    status = status,
    starEarned = star,
)

@Preview
@Composable
private fun NotStartedCardPreview() = PianoTrainerTheme { SongCard(previewCard(0, null, SongStatus.NotStarted), onClick = {}) }

@Preview
@Composable
private fun InProgressCardPreview() = PianoTrainerTheme { SongCard(previewCard(3, 72, SongStatus.InProgress), onClick = {}) }

@Preview
@Composable
private fun CompletedCardPreview() = PianoTrainerTheme { SongCard(previewCard(6, 81, SongStatus.Completed), onClick = {}) }

@Preview
@Composable
private fun StarredCardPreview() = PianoTrainerTheme { SongCard(previewCard(6, 94, SongStatus.Completed, star = true), onClick = {}) }

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.ProgressReader
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import io.github.rudicilibrasi.pianotrainer.database.StartLessonResult
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One phrase in the song detail list. */
data class PhraseRowState(
    val index: Int,
    val noteCount: Int,
    val notesText: String,
    val bestScore: Int?,
    val latestScore: Int?,
    val unlocked: Boolean,
) {
    val number: Int get() = index + 1

    val title: String get() = "Phrase $number · $noteCount ${if (noteCount == 1) "note" else "notes"}"

    /** "Best 82 · Latest 64", "Not attempted yet", or what it takes to unlock a locked phrase. */
    val statusText: String
        get() = when {
            !unlocked -> "Score ${Progression.UNLOCK_SCORE} or more on phrase ${number - 1} to unlock"
            bestScore == null -> "Not attempted yet"
            else -> "Best $bestScore · Latest $latestScore"
        }
}

data class SongDetailState(
    val songId: String,
    val title: String,
    val composer: String?,
    val phrases: List<PhraseRowState>,
    val currentPhraseIndex: Int,
    val completed: Boolean,
) {
    /** Only unlocked phrases can be opened. */
    fun canOpen(phraseIndex: Int): Boolean = phrases.getOrNull(phraseIndex)?.unlocked == true

    val continueText: String get() = "Continue with phrase ${currentPhraseIndex + 1}"

    companion object {
        /** Without saved progress, only phrase 1 is unlocked. */
        fun of(song: Song, progress: SongProgress = SongProgress.initial(song)) = SongDetailState(
            songId = song.id,
            title = song.title,
            composer = song.composer,
            phrases = song.phrases.map { phrase ->
                val saved = progress.phrases[phrase.index]
                PhraseRowState(
                    index = phrase.index,
                    noteCount = phrase.noteCount,
                    notesText = phrase.notes.joinToString(" ") { it.pitch!!.label },
                    bestScore = saved.bestScore,
                    latestScore = saved.latestScore,
                    unlocked = saved.unlocked,
                )
            },
            currentPhraseIndex = progress.currentPhraseIndex,
            completed = progress.completed,
        )
    }
}

sealed interface SongDetailUiState {
    data object Loading : SongDetailUiState
    data class Loaded(val detail: SongDetailState) : SongDetailUiState
    data class Error(val message: String) : SongDetailUiState
}

class SongDetailViewModel(
    private val songId: String,
    private val catalog: SongCatalog,
    private val progress: ProgressReader,
    private val startLesson: StartLesson,
) : ViewModel() {
    private val _uiState = MutableStateFlow<SongDetailUiState>(SongDetailUiState.Loading)
    val uiState: StateFlow<SongDetailUiState> = _uiState.asStateFlow()

    /** Why the last phrase the student tried to open couldn't be opened, until they open another. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var song: Song? = null

    init {
        viewModelScope.launch {
            val loaded = try {
                catalog.loadSongs().firstOrNull { it.id == songId }
            } catch (e: Exception) {
                _uiState.value = SongDetailUiState.Error("The song couldn't be loaded.\n(${e.message})")
                return@launch
            }
            if (loaded == null) {
                _uiState.value = SongDetailUiState.Error("This song isn't available.")
                return@launch
            }
            song = loaded
            // Saved progress: going back from a lesson shows newly unlocked phrases at once.
            progress.observe(loaded).collect { _uiState.value = SongDetailUiState.Loaded(SongDetailState.of(loaded, it)) }
        }
    }

    /**
     * Opens [phraseIndex] through [StartLesson], which refuses locked phrases whatever the UI
     * shows; [onReady] is called only when the lesson may start.
     */
    fun openPhrase(phraseIndex: Int, onReady: (Int) -> Unit) {
        val current = song ?: return
        viewModelScope.launch {
            when (val result = startLesson(current, phraseIndex)) {
                is StartLessonResult.Ready -> {
                    _message.value = null
                    onReady(phraseIndex)
                }
                is StartLessonResult.Locked -> _message.value = result.message
                is StartLessonResult.NoSuchPhrase -> _message.value = "This phrase isn't available."
            }
        }
    }
}

@Composable
fun SongDetailScreen(
    songId: String,
    onPractisePhrase: (phraseIndex: Int) -> Unit,
    onSongComplete: () -> Unit,
    container: AppContainer,
    viewModel: SongDetailViewModel = viewModel {
        SongDetailViewModel(songId, container.songCatalog, container.progress, container.startLesson)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    when (val current = state) {
        SongDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is SongDetailUiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is SongDetailUiState.Loaded -> SongDetailContent(
            detail = current.detail,
            onPractisePhrase = { index -> viewModel.openPhrase(index, onPractisePhrase) },
            onSongComplete = onSongComplete,
            message = message,
        )
    }
}

@Composable
fun SongDetailContent(
    detail: SongDetailState,
    onPractisePhrase: (phraseIndex: Int) -> Unit,
    onSongComplete: () -> Unit,
    message: String? = null,
) {
    // Locked phrases never reach the lesson, however this is called.
    val openIfUnlocked: (Int) -> Unit = { index -> if (detail.canOpen(index)) onPractisePhrase(index) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                detail.composer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Button(onClick = { openIfUnlocked(detail.currentPhraseIndex) }, modifier = Modifier.fillMaxWidth()) {
                Text(detail.continueText)
            }
        }
        if (message != null) {
            item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        if (detail.completed) {
            item {
                OutlinedButton(onClick = onSongComplete, modifier = Modifier.fillMaxWidth()) { Text("See song results") }
            }
        }
        items(detail.phrases, key = { it.index }) { row -> PhraseRow(row, onClick = { openIfUnlocked(row.index) }) }
    }
}

@Composable
fun PhraseRow(row: PhraseRowState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        enabled = row.unlocked,
        modifier = modifier.fillMaxWidth().alpha(if (row.unlocked) 1f else 0.6f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.notesText, style = MaterialTheme.typography.bodyLarge)
                Text(row.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!row.unlocked) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.semantics { contentDescription = "Locked" })
            }
        }
    }
}

@Preview
@Composable
private fun SongDetailPreview() = PianoTrainerTheme {
    SongDetailContent(
        SongDetailState(
            songId = "twinkle",
            title = "Twinkle, Twinkle, Little Star",
            composer = "Traditional",
            phrases = listOf(
                PhraseRowState(0, 7, "C4 C4 G4 G4 A4 A4 G4", bestScore = 82, latestScore = 64, unlocked = true),
                PhraseRowState(1, 7, "F4 F4 E4 E4 D4 D4 C4", bestScore = null, latestScore = null, unlocked = true),
                PhraseRowState(2, 7, "G4 G4 F4 F4 E4 E4 D4", bestScore = null, latestScore = null, unlocked = false),
            ),
            currentPhraseIndex = 1,
            completed = false,
        ),
        onPractisePhrase = {},
        onSongComplete = {},
    )
}

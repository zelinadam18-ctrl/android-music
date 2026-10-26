package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SongDetailUiState(val songId: String)

class SongDetailViewModel(songId: String) : ViewModel() {
    private val _uiState = MutableStateFlow(SongDetailUiState(songId))
    val uiState: StateFlow<SongDetailUiState> = _uiState.asStateFlow()
}

@Composable
fun SongDetailScreen(
    songId: String,
    onPractisePhrase: (phraseIndex: Int) -> Unit,
    onSongComplete: () -> Unit,
    viewModel: SongDetailViewModel = viewModel { SongDetailViewModel(songId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(
        heading = "Song",
        issueNumber = 29,
        details = listOf("Song: ${state.songId}"),
        links = listOf(
            PlaceholderLink("Practise phrase 1") { onPractisePhrase(0) },
            PlaceholderLink("Song complete") { onSongComplete() },
        ),
    )
}

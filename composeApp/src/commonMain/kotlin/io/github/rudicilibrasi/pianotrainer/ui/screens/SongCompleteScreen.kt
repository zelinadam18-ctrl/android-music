package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SongCompleteUiState(val songId: String)

class SongCompleteViewModel(songId: String) : ViewModel() {
    private val _uiState = MutableStateFlow(SongCompleteUiState(songId))
    val uiState: StateFlow<SongCompleteUiState> = _uiState.asStateFlow()
}

@Composable
fun SongCompleteScreen(
    songId: String,
    viewModel: SongCompleteViewModel = viewModel { SongCompleteViewModel(songId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(
        heading = "Song complete",
        issueNumber = 55,
        details = listOf("Song: ${state.songId}"),
    )
}

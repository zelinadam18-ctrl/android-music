package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nothing to show yet; issue #28 adds the real state. */
data object SongsUiState

class SongsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SongsUiState)
    val uiState: StateFlow<SongsUiState> = _uiState.asStateFlow()
}

@Composable
fun SongsScreen(
    onOpenSong: (songId: String) -> Unit,
    viewModel: SongsViewModel = viewModel { SongsViewModel() },
) {
    PlaceholderScreen(
        heading = "Songs",
        issueNumber = 28,
        links = listOf(
            PlaceholderLink("Open an example song") { onOpenSong("example") },
        ),
    )
}

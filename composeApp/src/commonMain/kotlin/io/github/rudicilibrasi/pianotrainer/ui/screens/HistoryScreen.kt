package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nothing to show yet; issue #62 adds the real state. */
data object HistoryUiState

class HistoryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
}

@Composable
fun HistoryScreen(
    onOpenAttempt: (attemptId: Long) -> Unit,
    viewModel: HistoryViewModel = viewModel { HistoryViewModel() },
) {
    PlaceholderScreen(
        heading = "Test history",
        issueNumber = 62,
        links = listOf(
            PlaceholderLink("Open an example attempt") { onOpenAttempt(0L) },
        ),
    )
}

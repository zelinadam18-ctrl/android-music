package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryDetailUiState(val attemptId: Long)

class HistoryDetailViewModel(attemptId: Long) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryDetailUiState(attemptId))
    val uiState: StateFlow<HistoryDetailUiState> = _uiState.asStateFlow()
}

@Composable
fun HistoryDetailScreen(
    attemptId: Long,
    viewModel: HistoryDetailViewModel = viewModel { HistoryDetailViewModel(attemptId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(
        heading = "Saved attempt",
        issueNumber = 63,
        details = listOf("Attempt: ${state.attemptId}"),
    )
}

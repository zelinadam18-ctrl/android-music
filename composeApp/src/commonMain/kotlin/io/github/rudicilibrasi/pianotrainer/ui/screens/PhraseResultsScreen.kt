package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhraseResultsUiState(val attemptId: Long)

class PhraseResultsViewModel(attemptId: Long) : ViewModel() {
    private val _uiState = MutableStateFlow(PhraseResultsUiState(attemptId))
    val uiState: StateFlow<PhraseResultsUiState> = _uiState.asStateFlow()
}

@Composable
fun PhraseResultsScreen(
    attemptId: Long,
    viewModel: PhraseResultsViewModel = viewModel { PhraseResultsViewModel(attemptId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(
        heading = "Phrase results",
        issueNumber = 47,
        details = listOf("Attempt: ${state.attemptId}"),
    )
}

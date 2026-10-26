package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nothing to show yet; issue #66 adds the real state. */
data object FreePracticeUiState

class FreePracticeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FreePracticeUiState)
    val uiState: StateFlow<FreePracticeUiState> = _uiState.asStateFlow()
}

@Composable
fun FreePracticeScreen(
    viewModel: FreePracticeViewModel = viewModel { FreePracticeViewModel() },
) {
    PlaceholderScreen(
        heading = "Free practice",
        issueNumber = 66,
        details = listOf("Practise six random notes without affecting song progress."),
    )
}

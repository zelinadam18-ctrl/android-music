package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LessonUiState(val songId: String, val phraseIndex: Int)

class LessonViewModel(songId: String, phraseIndex: Int) : ViewModel() {
    private val _uiState = MutableStateFlow(LessonUiState(songId, phraseIndex))
    val uiState: StateFlow<LessonUiState> = _uiState.asStateFlow()
}

@Composable
fun LessonScreen(
    songId: String,
    phraseIndex: Int,
    onAttemptAnalyzed: (attemptId: Long) -> Unit,
    viewModel: LessonViewModel = viewModel { LessonViewModel(songId, phraseIndex) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlaceholderScreen(
        heading = "Lesson",
        issueNumber = 30,
        details = listOf("Song: ${state.songId}", "Phrase ${state.phraseIndex + 1}"),
        links = listOf(
            PlaceholderLink("See results") { onAttemptAnalyzed(0L) },
        ),
    )
}

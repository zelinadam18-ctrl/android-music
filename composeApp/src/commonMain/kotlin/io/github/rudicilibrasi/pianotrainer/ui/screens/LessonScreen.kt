package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.core.analysis.AnalysisResult
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import io.github.rudicilibrasi.pianotrainer.database.StartLessonResult
import io.github.rudicilibrasi.pianotrainer.ui.graphs.GraphSwitcher
import io.github.rudicilibrasi.pianotrainer.ui.staff.StaffView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the lesson screen shows about the phrase (spec §4, #30). Everything comes from the song
 * model; the screen never builds its own note list.
 */
data class LessonState(
    val song: Song,
    val phrase: Phrase,
) {
    val title: String get() = song.title
    val phraseText: String get() = "Phrase ${phrase.number} of ${song.phrases.size}"
    val noteCountText: String get() = "${phrase.noteCount} ${if (phrase.noteCount == 1) "note" else "notes"}"

    /** "C4 — C4 — G4 — …", rests left out. */
    val lettersText: String get() = phrase.notes.joinToString(" — ") { it.pitch!!.label }
}

sealed interface LessonUiState {
    data object Loading : LessonUiState

    /** [reference] is the analysis of the synthesized reference, for the graphs; null while it's being made. */
    data class Ready(val lesson: LessonState, val reference: AnalysisResult? = null) : LessonUiState

    /** The phrase is locked, or doesn't exist; [message] says why. */
    data class Unavailable(val message: String) : LessonUiState
}

/**
 * Opens a lesson through [StartLesson], so a locked phrase can't be reached by any route (deep
 * link, restored state, back/forward). Then renders and analyses the reference in memory for the
 * graphs, off the main thread.
 */
class LessonViewModel(
    private val songId: String,
    private val phraseIndex: Int,
    private val catalog: SongCatalog,
    private val startLesson: StartLesson,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<LessonUiState>(LessonUiState.Loading)
    val uiState: StateFlow<LessonUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val song = try {
                catalog.loadSongs().firstOrNull { it.id == songId }
            } catch (e: Exception) {
                _uiState.value = LessonUiState.Unavailable("The song couldn't be loaded.\n(${e.message})")
                return@launch
            }
            if (song == null) {
                _uiState.value = LessonUiState.Unavailable("This song isn't available.")
                return@launch
            }
            when (val result = startLesson(song, phraseIndex)) {
                is StartLessonResult.Locked -> _uiState.value = LessonUiState.Unavailable(result.message)
                is StartLessonResult.NoSuchPhrase -> _uiState.value = LessonUiState.Unavailable("This phrase isn't available.")
                is StartLessonResult.Ready -> {
                    val lesson = LessonState(song, result.phrase)
                    _uiState.value = LessonUiState.Ready(lesson)
                    val reference = runCatching {
                        val synthesizer = Synthesizer()
                        val samples = withContext(analysisDispatcher) { synthesizer.renderPhrase(result.phrase, song.tempoBpm) }
                        AudioAnalyzer(analysisDispatcher).analyze(AudioClip(samples, synthesizer.sampleRate))
                    }.getOrNull()
                    _uiState.value = LessonUiState.Ready(lesson, reference)
                }
            }
        }
    }
}

@Composable
fun LessonScreen(
    songId: String,
    phraseIndex: Int,
    container: AppContainer,
    onAttemptAnalyzed: (attemptId: Long) -> Unit,
    viewModel: LessonViewModel = viewModel { LessonViewModel(songId, phraseIndex, container.songCatalog, container.startLesson) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val current = state) {
        LessonUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LessonUiState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is LessonUiState.Ready -> LessonContent(current.lesson, current.reference)
    }
}

@Composable
fun LessonContent(lesson: LessonState, reference: AnalysisResult?, playbackPositionMs: Long? = null) {
    // The note sounding during reference playback (#31); null when stopped, which clears it.
    val highlight = PlaybackHighlight(lesson.phrase, lesson.song.tempoBpm).noteAt(playbackPositionMs)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Song, phrase and note count.
        Text(lesson.title, style = MaterialTheme.typography.headlineSmall)
        Text("${lesson.phraseText} · ${lesson.noteCountText}", style = MaterialTheme.typography.titleMedium)
        // 2. The notes in letters.
        NoteLetters(lesson.phrase, highlight)
        // 3. The phrase on the staff.
        StaffView(
            lesson.phrase.events,
            highlightNoteIndex = highlight,
            timeSignature = lesson.song.timeSignature,
            phraseStartBeat = lesson.phrase.startBeat,
            pickupBeats = lesson.song.pickupBeats,
        )
        // 4. Listen to the reference: playback arrives with #21, #22 and #23.
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Listen") }
        Text(
            "Listening to the reference isn't available yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 5. Recording controls (#34) go here. Reference graphs (#61):
        if (reference == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            GraphSwitcher(reference = reference, yours = null, comparison = null)
        }
    }
}

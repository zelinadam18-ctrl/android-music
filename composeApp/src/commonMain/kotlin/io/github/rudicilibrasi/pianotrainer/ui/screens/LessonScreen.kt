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
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioPlayer
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

    /**
     * [reference] is the analysis of the synthesized reference, for the graphs, and
     * [referenceWav] the same audio to play; both null while they're being made.
     */
    data class Ready(val lesson: LessonState, val reference: AnalysisResult? = null, val referenceWav: ByteArray? = null) : LessonUiState

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
    private val player: AudioPlayer = SilentAudioPlayer(),
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** Where reference playback is, for the note highlight; null when stopped. */
    val playbackPositionMs: StateFlow<Long?> = player.positionMs
    val playerState: StateFlow<PlayerState> = player.state
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
                    val synthesizer = Synthesizer()
                    val wav = runCatching {
                        withContext(analysisDispatcher) { Wav.encode(synthesizer.renderPhrase(result.phrase, song.tempoBpm), synthesizer.sampleRate) }
                    }.getOrNull()
                    val reference = wav?.let { runCatching { AudioAnalyzer(analysisDispatcher).analyze(it) }.getOrNull() }
                    _uiState.value = LessonUiState.Ready(lesson, reference, wav)
                }
            }
        }
    }

    /** Plays the reference, or stops it if it's already playing. */
    fun toggleListen() {
        val wav = (_uiState.value as? LessonUiState.Ready)?.referenceWav ?: return
        if (player.state.value == PlayerState.Playing) player.stop() else player.play(wav)
    }

    /** Leaving the lesson stops the reference. */
    override fun onCleared() {
        player.stop()
    }
}

@Composable
fun LessonScreen(
    songId: String,
    phraseIndex: Int,
    container: AppContainer,
    onAttemptAnalyzed: (attemptId: Long) -> Unit,
    viewModel: LessonViewModel = viewModel {
        LessonViewModel(songId, phraseIndex, container.songCatalog, container.startLesson, container.audioPlayer)
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val position by viewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    when (val current = state) {
        LessonUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LessonUiState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is LessonUiState.Ready -> LessonContent(
            lesson = current.lesson,
            reference = current.reference,
            playbackPositionMs = position,
            canListen = current.referenceWav != null,
            playerState = playerState,
            onListen = viewModel::toggleListen,
        )
    }
}

@Composable
fun LessonContent(
    lesson: LessonState,
    reference: AnalysisResult?,
    playbackPositionMs: Long? = null,
    canListen: Boolean = false,
    playerState: PlayerState = PlayerState.Idle,
    onListen: () -> Unit = {},
) {
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
        // 4. Listen to the reference, with a loading state while it's being made.
        Button(onClick = onListen, enabled = canListen, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    !canListen -> "Preparing the reference…"
                    playerState == PlayerState.Playing -> "Stop"
                    else -> "Listen"
                },
            )
        }
        if (playerState == PlayerState.Error) {
            Text("The reference couldn't be played. Check the volume and try again.", color = MaterialTheme.colorScheme.error)
        }
        // 5. Recording controls (#34) go here. Reference graphs (#61):
        if (reference == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            GraphSwitcher(reference = reference, yours = null, comparison = null)
        }
    }
}

package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScore
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioPlayer
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.ProgressReader
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import io.github.rudicilibrasi.pianotrainer.database.StartLessonResult
import io.github.rudicilibrasi.pianotrainer.ui.graphs.ComparisonInput
import io.github.rudicilibrasi.pianotrainer.ui.staff.ComparedStaves
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * The results of the attempt just recorded (spec §11, §12, #45).
 *
 * @property detail the saved attempt, shown exactly as stored (shared with history detail).
 * @property phrase the song's phrase when it still has the attempt's notes, for the staves and
 *   comparison graph; null for Free Practice or a song that changed.
 * @property comparison the alignment and timing behind the comparison views, recomputed from
 *   the saved notes with the same deterministic scorer.
 * @property canContinue only when the score is at least 50; [PhraseResultsViewModel.continueOn]
 *   checks the next phrase again through [StartLesson] (#51).
 */
data class PhraseResultsState(
    val attempt: Attempt,
    val detail: HistoryDetailState,
    val song: Song?,
    val phrase: Phrase?,
    val comparison: PhraseScore?,
    val unlockText: String?,
    val canContinue: Boolean,
    val continueText: String?,
    val isLastPhrase: Boolean,
) {
    val freePractice: Boolean get() = attempt.mode == AttemptMode.FreePractice

    val retryText: String get() = if (freePractice) "Try these notes again" else "Retry phrase ${(attempt.phraseIndex ?: 0) + 1}"

    companion object {
        fun of(attempt: Attempt, songs: Map<String, Song>, trend: PhraseTrend?, timeZone: TimeZone): PhraseResultsState {
            val detail = HistoryDetailState.of(attempt, songs, timeZone).copy(trend = trend)
            val song = attempt.songId?.let(songs::get)
            val index = attempt.phraseIndex
            val phrase = index?.let { song?.phrases?.getOrNull(it) }?.takeIf { p -> p.notes.map { it.pitch } == attempt.expectedNotes }
            val passed = attempt.scores.score >= Progression.UNLOCK_SCORE
            val isLast = song != null && index != null && index == song.phrases.lastIndex
            val lesson = attempt.mode == AttemptMode.Lesson && song != null && index != null
            return PhraseResultsState(
                attempt = attempt,
                detail = detail,
                song = song,
                phrase = phrase,
                comparison = phrase?.let { PhraseScorer.score(it, song!!.tempoBpm, attempt.detectedNotes) },
                unlockText = when {
                    !lesson -> null
                    passed && isLast -> "You completed the last phrase."
                    passed -> "Phrase ${index!! + 2} is unlocked."
                    isLast -> "Score ${Progression.UNLOCK_SCORE} or more to complete this phrase."
                    else -> "Score ${Progression.UNLOCK_SCORE} or more to unlock phrase ${index!! + 2}."
                },
                canContinue = lesson && passed,
                continueText = when {
                    !lesson || !passed -> null
                    isLast -> "See song results"
                    else -> "Continue to phrase ${index!! + 2}"
                },
                isLastPhrase = isLast,
            )
        }
    }
}

sealed interface PhraseResultsUiState {
    data object Loading : PhraseResultsUiState
    data class Loaded(val state: PhraseResultsState, val message: String? = null) : PhraseResultsUiState
    data class Error(val message: String) : PhraseResultsUiState
}

class PhraseResultsViewModel(
    private val attemptId: Long,
    private val catalog: SongCatalog,
    private val history: AttemptHistory,
    private val progress: ProgressReader,
    private val startLesson: StartLesson,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val storage: AppStorage? = null,
    private val references: ReferenceAudioCache? = null,
    private val player: AudioPlayer = SilentAudioPlayer(),
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<PhraseResultsUiState>(PhraseResultsUiState.Loading)
    val uiState: StateFlow<PhraseResultsUiState> = _uiState.asStateFlow()

    /** The reference and the student's recording, for playback and graphs; null while loading. */
    private val _audio = MutableStateFlow<AttemptAudio?>(null)
    val audio: StateFlow<AttemptAudio?> = _audio.asStateFlow()

    fun play(wav: ByteArray) = player.play(wav)

    val playerState: StateFlow<PlayerState> = player.state

    override fun onCleared() {
        player.stop()
    }

    init {
        viewModelScope.launch {
            _uiState.value = try {
                val attempt = history.get(attemptId)
                if (attempt == null) {
                    PhraseResultsUiState.Error("These results aren't available any more.")
                } else {
                    val songs = runCatching { catalog.loadSongs().associateBy { it.id } }.getOrDefault(emptyMap())
                    val songId = attempt.songId
                    val phraseIndex = attempt.phraseIndex
                    val trend = if (songId != null && phraseIndex != null) {
                        PhraseTrend.of(history.observeForPhrase(songId, phraseIndex).first(), attempt)
                    } else {
                        null
                    }
                    val state = PhraseResultsState.of(attempt, songs, trend, timeZone)
                    viewModelScope.launch {
                        _audio.value = AttemptAudio.load(attempt, state.phrase, state.song?.tempoBpm, storage, references, AudioAnalyzer(analysisDispatcher))
                    }
                    PhraseResultsUiState.Loaded(state)
                }
            } catch (e: Exception) {
                PhraseResultsUiState.Error("The results couldn't be loaded.\n(${e.message})")
            }
        }
    }

    /**
     * Continue: the next phrase, or the song results after the last one. The next phrase goes
     * through [StartLesson] again, so the lock is enforced even if this screen is out of date.
     */
    fun continueOn(onNextPhrase: (songId: String, phraseIndex: Int) -> Unit, onSongComplete: (songId: String) -> Unit) {
        val loaded = _uiState.value as? PhraseResultsUiState.Loaded ?: return
        val state = loaded.state
        val song = state.song ?: return
        val index = state.attempt.phraseIndex ?: return
        if (!state.canContinue) return
        viewModelScope.launch {
            if (state.isLastPhrase) {
                if (progress.get(song).completed) onSongComplete(song.id)
                else _uiState.value = loaded.copy(message = "Complete every phrase to finish the song.")
                return@launch
            }
            when (val result = startLesson(song, index + 1)) {
                is StartLessonResult.Ready -> onNextPhrase(song.id, index + 1)
                is StartLessonResult.Locked -> _uiState.value = loaded.copy(message = result.message)
                is StartLessonResult.NoSuchPhrase -> _uiState.value = loaded.copy(message = "There's no next phrase.")
            }
        }
    }
}

@Composable
fun PhraseResultsScreen(
    attemptId: Long,
    container: AppContainer,
    onRetry: (songId: String, phraseIndex: Int) -> Unit,
    onRetryFreePractice: () -> Unit,
    onNextPhrase: (songId: String, phraseIndex: Int) -> Unit,
    onSongComplete: (songId: String) -> Unit,
    viewModel: PhraseResultsViewModel = viewModel {
        PhraseResultsViewModel(
            attemptId, container.songCatalog, container.attemptHistory, container.progress, container.startLesson,
            storage = container.storage, references = container.referenceAudio, player = container.audioPlayer,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    when (val current = state) {
        PhraseResultsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is PhraseResultsUiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is PhraseResultsUiState.Loaded -> PhraseResultsContent(
            state = current.state,
            message = current.message,
            onRetry = {
                val attempt = current.state.attempt
                val songId = attempt.songId
                val phraseIndex = attempt.phraseIndex
                if (songId != null && phraseIndex != null) onRetry(songId, phraseIndex) else onRetryFreePractice()
            },
            onContinue = { viewModel.continueOn(onNextPhrase, onSongComplete) },
            audio = audio,
            onPlay = viewModel::play,
            playbackFailed = playerState == PlayerState.Error,
        )
    }
}

@Composable
fun PhraseResultsContent(
    state: PhraseResultsState,
    message: String?,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    audio: AttemptAudio? = null,
    onPlay: (ByteArray) -> Unit = {},
    playbackFailed: Boolean = false,
) {
    val detail = state.detail
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(detail.title, style = MaterialTheme.typography.titleLarge)
        Text("${detail.score}/100", style = MaterialTheme.typography.displayMedium)
        state.unlockText?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        detail.components.forEach { (name, value) -> Text("$name: $value", style = MaterialTheme.typography.bodyLarge) }
        detail.trend?.let { PhraseTrendView(it) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Retry is always available; Continue only from 50 up.
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text(state.retryText) }
            state.continueText?.let { Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text(it) } }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Text(detail.summary, style = MaterialTheme.typography.titleMedium)
        if (detail.wentWell.isNotEmpty()) {
            Text("What went well", style = MaterialTheme.typography.titleSmall)
            detail.wentWell.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (detail.toImprove.isNotEmpty()) {
            Text("What to improve", style = MaterialTheme.typography.titleSmall)
            detail.toImprove.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }

        Text("Expected: ${detail.expectedLetters}", style = MaterialTheme.typography.bodyLarge)
        Text("You played: ${detail.detectedLetters}", style = MaterialTheme.typography.bodyLarge)
        val phrase = state.phrase
        val comparison = state.comparison
        val song = state.song
        if (phrase != null && comparison != null && song != null) {
            ComparedStaves(phrase, comparison.alignment, state.attempt.detectedNotes, timeSignature = song.timeSignature, pickupBeats = song.pickupBeats)
        }
        // Play the reference and the recording, and all four graphs (#59); a missing recording says so (#62).
        AttemptAudioSection(
            audio,
            if (phrase != null && comparison != null && song != null) ComparisonInput(phrase, song.tempoBpm, state.attempt.detectedNotes, comparison) else null,
            onPlay,
            playbackFailed,
        )
    }
}

package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.AudioRecorder
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.RecorderState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioRecorder
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.scoring.FeedbackGenerator
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseContext
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.AttemptScores
import io.github.rudicilibrasi.pianotrainer.database.FreePractice
import io.github.rudicilibrasi.pianotrainer.database.NewAttempt
import io.github.rudicilibrasi.pianotrainer.ui.staff.StaffView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The current Free Practice notes, and their reference once it has been rendered. */
data class FreePracticeUiState(val phrase: Phrase, val referenceWav: ByteArray? = null) {
    val lettersText: String get() = phrase.notes.joinToString(" — ") { it.pitch!!.label }
}

/**
 * The original app's random-note exercise (#66): six random white keys, the same listen → record
 * → results flow as a lesson, saved to history as Free Practice. It never touches song progress:
 * it only gets [saveAttempt], which is `RecordFreePractice`, never `RecordAttempt`.
 */
class FreePracticeViewModel(
    private val player: AudioPlayer = SilentAudioPlayer(),
    private val recorder: AudioRecorder = SilentAudioRecorder(),
    private val saveAttempt: suspend (NewAttempt) -> Long = { error("No database") },
    private val random: Random = Random.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { 0L },
    private val references: ReferenceAudioCache? = null,
    private val saveRecording: (relativePath: String, wav: ByteArray) -> Boolean = { _, _ -> false },
) : ViewModel() {
    private var referencePath: String? = null
    private val _uiState = MutableStateFlow(FreePracticeUiState(FreePractice.newPhrase(random)))
    val uiState: StateFlow<FreePracticeUiState> = _uiState.asStateFlow()
    private val _recording = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val recording: StateFlow<RecordingUiState> = _recording.asStateFlow()
    val playbackPositionMs: StateFlow<Long?> = player.positionMs
    val playerState: StateFlow<PlayerState> = player.state
    val inputLevel: StateFlow<Float> = recorder.level
    val elapsedSeconds: StateFlow<Double> = recorder.elapsedSeconds

    init {
        // If the microphone stops on its own (a call, another app taking it), say so.
        viewModelScope.launch {
            recorder.state.collect { state ->
                if (state == RecorderState.Error && _recording.value == RecordingUiState.Recording) {
                    recorder.cancel()
                    _recording.value = RecordingUiState.Problem("Recording was interrupted. Tap Try again and play the notes from the start.")
                }
            }
        }
        renderReference()
    }

    /** "New notes": a fresh set of six. */
    fun newNotes() {
        if (_recording.value == RecordingUiState.Recording || _recording.value == RecordingUiState.Analyzing) return
        player.stop()
        _uiState.value = FreePracticeUiState(FreePractice.newPhrase(random))
        renderReference()
    }

    private fun renderReference() {
        val phrase = _uiState.value.phrase
        viewModelScope.launch {
            val audio = withContext(dispatcher) { references?.reference(null, phrase, FreePractice.TEMPO_BPM) }
            val wav = audio?.wav ?: withContext(dispatcher) {
                Synthesizer().let { Wav.encode(it.renderPhrase(phrase, FreePractice.TEMPO_BPM), it.sampleRate) }
            }
            if (_uiState.value.phrase === phrase) {
                referencePath = audio?.path
                _uiState.value = _uiState.value.copy(referenceWav = wav)
            }
        }
    }

    fun toggleListen() {
        if (_recording.value == RecordingUiState.Recording) return
        val wav = _uiState.value.referenceWav ?: return
        if (player.state.value == PlayerState.Playing) player.stop() else player.play(wav)
    }

    fun startRecording() {
        if (_recording.value == RecordingUiState.Recording || _recording.value == RecordingUiState.Analyzing) return
        player.stop()
        viewModelScope.launch {
            _recording.value = when {
                recorder.start() -> RecordingUiState.Recording
                recorder.state.value == RecorderState.PermissionDenied -> RecordingUiState.PermissionDenied
                else -> RecordingUiState.Problem("The microphone couldn't be started. Close other apps that use it and try again.")
            }
        }
    }

    fun stopRecording(onSaved: (attemptId: Long) -> Unit) {
        if (_recording.value != RecordingUiState.Recording) return
        val clip = recorder.stop()
        if (clip == null) {
            _recording.value = RecordingUiState.Problem("Nothing was recorded. Tap Record and play the notes.")
            return
        }
        val phrase = _uiState.value.phrase
        _recording.value = RecordingUiState.Analyzing
        viewModelScope.launch {
            try {
                val createdAt = now()
                val recordingPath = withContext(dispatcher) {
                    AppStorage.recordingPath(isoTime(createdAt), null, null).takeIf { saveRecording(it, Wav.encode(clip)) }
                }
                val detected = AudioAnalyzer(dispatcher).analyze(clip).notes
                val score = withContext(dispatcher) { PhraseScorer.score(phrase, FreePractice.TEMPO_BPM, detected) }
                // Free Practice unlocks nothing, so the summary is just the score.
                val feedback = FeedbackGenerator.generate(phrase, detected, score, PhraseContext(isLastPhrase = false, nextPhraseAlreadyUnlocked = true))
                    .copy(summary = "You scored ${score.score}/100.")
                val id = saveAttempt(
                    NewAttempt(
                        songId = null,
                        phraseIndex = null,
                        createdAt = createdAt,
                        mode = AttemptMode.FreePractice,
                        expectedNotes = phrase.notes.map { it.pitch!! },
                        detectedNotes = detected,
                        referenceWavPath = referencePath,
                        userWavPath = recordingPath,
                        scores = AttemptScores(score.score, score.rawScore, score.noteAccuracy, score.orderAccuracy, score.timingAccuracy, score.tempoAccuracy, score.timing.tempoRatio),
                        feedback = feedback,
                        analysisVersion = AudioAnalyzer.ANALYSIS_VERSION,
                    ),
                )
                _recording.value = RecordingUiState.Idle
                onSaved(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _recording.value = RecordingUiState.Problem("The recording couldn't be analysed. Please try again.")
            }
        }
    }

    fun dismissProblem() {
        if (_recording.value is RecordingUiState.Problem || _recording.value == RecordingUiState.PermissionDenied) _recording.value = RecordingUiState.Idle
    }

    override fun onCleared() {
        player.stop()
        recorder.cancel()
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun FreePracticeScreen(
    container: AppContainer,
    onAttemptAnalyzed: (attemptId: Long) -> Unit,
    viewModel: FreePracticeViewModel = viewModel {
        FreePracticeViewModel(
            player = container.audioPlayer,
            recorder = container.audioRecorder,
            saveAttempt = { container.recordFreePractice(it) },
            now = { Clock.System.now().toEpochMilliseconds() },
            references = container.referenceAudio,
            saveRecording = container.storage::write,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val position by viewModel.playbackPositionMs.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val level by viewModel.inputLevel.collectAsStateWithLifecycle()
    val elapsed by viewModel.elapsedSeconds.collectAsStateWithLifecycle()
    val busy = recording == RecordingUiState.Recording || recording == RecordingUiState.Analyzing
    val highlight = PlaybackHighlight(state.phrase, FreePractice.TEMPO_BPM).noteAt(position)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Free practice", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Six random notes to play. Free practice is saved to history but never changes your song progress.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NoteLetters(state.phrase, highlight)
        StaffView(state.phrase.events, highlightNoteIndex = highlight)
        Button(onClick = viewModel::toggleListen, enabled = state.referenceWav != null && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    state.referenceWav == null -> "Preparing the notes…"
                    playerState == PlayerState.Playing -> "Stop"
                    else -> "Listen"
                },
            )
        }
        RecordingControls(
            recording, level, elapsed,
            onRecord = viewModel::startRecording,
            onStop = { viewModel.stopRecording(onAttemptAnalyzed) },
            onOpenSettings = container.openAppSettings,
            onDismiss = viewModel::dismissProblem,
        )
        OutlinedButton(onClick = viewModel::newNotes, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("New notes") }
    }
}

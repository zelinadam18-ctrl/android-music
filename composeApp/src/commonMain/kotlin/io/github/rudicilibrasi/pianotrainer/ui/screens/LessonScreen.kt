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
import io.github.rudicilibrasi.pianotrainer.audio.AudioRecorder
import io.github.rudicilibrasi.pianotrainer.audio.RecorderState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioRecorder
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.core.scoring.FeedbackGenerator
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseContext
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.AttemptScores
import io.github.rudicilibrasi.pianotrainer.database.NewAttempt
import io.github.rudicilibrasi.pianotrainer.database.RecordAttemptResult
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioPlayer
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
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

/** Where the student is in recording an attempt (#34). */
sealed interface RecordingUiState {
    data object Idle : RecordingUiState
    data object Recording : RecordingUiState
    data object Analyzing : RecordingUiState

    /** The microphone was refused: explain, and offer a way to Settings. */
    data object PermissionDenied : RecordingUiState

    /** Something went wrong; [message] says what to do next. */
    data class Problem(val message: String) : RecordingUiState
}

/** "0:07", "1:05". */
fun formatElapsed(seconds: Double): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
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
    private val recorder: AudioRecorder = SilentAudioRecorder(),
    /** Saves the attempt and its progress together: `RecordAttempt` (#53) in the app. */
    private val saveAttempt: suspend (Song, NewAttempt) -> RecordAttemptResult = { _, _ -> error("No database") },
    private val now: () -> Long = { 0L },
    /** The reference cache (#21); without one the reference is rendered in memory each time. */
    private val references: ReferenceAudioCache? = null,
    /** Saves a recording's WAV under a relative path (#20); returns false if it couldn't. */
    private val saveRecording: (relativePath: String, wav: ByteArray) -> Boolean = { _, _ -> false },
) : ViewModel() {
    private var referencePath: String? = null
    private val _recording = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val recording: StateFlow<RecordingUiState> = _recording.asStateFlow()
    val inputLevel: StateFlow<Float> = recorder.level
    val elapsedSeconds: StateFlow<Double> = recorder.elapsedSeconds

    /** Progress when the lesson opened, to word the unlock message in the feedback. */
    private var startProgress: SongProgress? = null
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
                    startProgress = result.progress
                    val lesson = LessonState(song, result.phrase)
                    _uiState.value = LessonUiState.Ready(lesson)
                    val wav = runCatching {
                        withContext(analysisDispatcher) {
                            references?.reference(song.id, result.phrase, song.tempoBpm)?.also { referencePath = it.path }?.wav
                                ?: Synthesizer().let { Wav.encode(it.renderPhrase(result.phrase, song.tempoBpm), it.sampleRate) }
                        }
                    }.getOrNull()
                    val reference = wav?.let { runCatching { AudioAnalyzer(analysisDispatcher).analyze(it) }.getOrNull() }
                    _uiState.value = LessonUiState.Ready(lesson, reference, wav)
                }
            }
        }
    }

    /** Plays the reference, or stops it if it's already playing. Not while recording. */
    fun toggleListen() {
        if (_recording.value == RecordingUiState.Recording) return
        val wav = (_uiState.value as? LessonUiState.Ready)?.referenceWav ?: return
        if (player.state.value == PlayerState.Playing) player.stop() else player.play(wav)
    }

    /** Starts recording: stops any playback, and asks for the microphone the first time. */
    fun startRecording() {
        if (_uiState.value !is LessonUiState.Ready) return
        if (_recording.value == RecordingUiState.Recording || _recording.value == RecordingUiState.Analyzing) return
        player.stop()
        viewModelScope.launch {
            _recording.value = if (recorder.start()) {
                RecordingUiState.Recording
            } else if (recorder.state.value == RecorderState.PermissionDenied) {
                RecordingUiState.PermissionDenied
            } else {
                RecordingUiState.Problem("The microphone couldn't be started. Close other apps that use it and try again.")
            }
        }
    }

    /**
     * Stops recording, then analyses, scores and saves the attempt, and calls [onSaved] with its
     * id so the results screen (#47) can open.
     */
    fun stopRecording(onSaved: (attemptId: Long) -> Unit) {
        if (_recording.value != RecordingUiState.Recording) return
        val ready = _uiState.value as? LessonUiState.Ready ?: return
        val clip = recorder.stop()
        if (clip == null) {
            _recording.value = RecordingUiState.Problem("Nothing was recorded. Tap Record and play the phrase.")
            return
        }
        _recording.value = RecordingUiState.Analyzing
        viewModelScope.launch {
            try {
                val song = ready.lesson.song
                val phrase = ready.lesson.phrase
                val createdAt = now()
                // Keep the take as a WAV under recordings/ (#20); the attempt still saves if this fails.
                val recordingPath = withContext(analysisDispatcher) {
                    AppStorage.recordingPath(isoTime(createdAt), song.id, phrase.number)
                        .takeIf { saveRecording(it, Wav.encode(clip)) }
                }
                val detected = AudioAnalyzer(analysisDispatcher).analyze(clip).notes
                val score = withContext(analysisDispatcher) { PhraseScorer.score(phrase, song.tempoBpm, detected) }
                val progress = startProgress
                val context = PhraseContext(
                    isLastPhrase = phrase.index == song.phrases.lastIndex,
                    nextPhraseAlreadyUnlocked = progress?.isUnlocked(phrase.index + 1) == true,
                )
                val attempt = NewAttempt(
                    songId = song.id,
                    phraseIndex = phrase.index,
                    createdAt = createdAt,
                    mode = AttemptMode.Lesson,
                    expectedNotes = phrase.notes.map { it.pitch!! },
                    detectedNotes = detected,
                    referenceWavPath = referencePath,
                    userWavPath = recordingPath,
                    scores = AttemptScores(
                        score = score.score,
                        rawScore = score.rawScore,
                        noteAccuracy = score.noteAccuracy,
                        orderAccuracy = score.orderAccuracy,
                        timingAccuracy = score.timingAccuracy,
                        tempoAccuracy = score.tempoAccuracy,
                        tempoRatio = score.timing.tempoRatio,
                    ),
                    feedback = FeedbackGenerator.generate(phrase, detected, score, context),
                    analysisVersion = AudioAnalyzer.ANALYSIS_VERSION,
                )
                when (val result = saveAttempt(song, attempt)) {
                    is RecordAttemptResult.Recorded -> {
                        _recording.value = RecordingUiState.Idle
                        onSaved(result.attemptId)
                    }
                    is RecordAttemptResult.Locked -> _recording.value = RecordingUiState.Problem(result.message)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing was saved, so progress is unchanged.
                _recording.value = RecordingUiState.Problem("The recording couldn't be analysed. Please try again.")
            }
        }
    }

    /** Clears a message so the student can try again. */
    fun dismissProblem() {
        if (_recording.value is RecordingUiState.Problem || _recording.value == RecordingUiState.PermissionDenied) {
            _recording.value = RecordingUiState.Idle
        }
    }

    /** Leaving the lesson stops the reference and throws away an unfinished recording. */
    override fun onCleared() {
        player.stop()
        recorder.cancel()
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun LessonScreen(
    songId: String,
    phraseIndex: Int,
    container: AppContainer,
    onAttemptAnalyzed: (attemptId: Long) -> Unit,
    viewModel: LessonViewModel = viewModel {
        LessonViewModel(
            songId = songId,
            phraseIndex = phraseIndex,
            catalog = container.songCatalog,
            startLesson = container.startLesson,
            player = container.audioPlayer,
            recorder = container.audioRecorder,
            saveAttempt = { song, attempt -> container.recordAttempt(song, attempt) },
            now = { Clock.System.now().toEpochMilliseconds() },
            references = container.referenceAudio,
            saveRecording = container.storage::write,
        )
    },
) {
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val level by viewModel.inputLevel.collectAsStateWithLifecycle()
    val elapsed by viewModel.elapsedSeconds.collectAsStateWithLifecycle()
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
            recording = recording,
            inputLevel = level,
            elapsedSeconds = elapsed,
            onRecord = viewModel::startRecording,
            onStop = { viewModel.stopRecording(onAttemptAnalyzed) },
            onOpenSettings = container.openAppSettings,
            onDismiss = viewModel::dismissProblem,
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
    recording: RecordingUiState = RecordingUiState.Idle,
    inputLevel: Float = 0f,
    elapsedSeconds: Double = 0.0,
    onRecord: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val busy = recording == RecordingUiState.Recording || recording == RecordingUiState.Analyzing
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
        Button(onClick = onListen, enabled = canListen && !busy, modifier = Modifier.fillMaxWidth()) {
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
        // 5. Recording (#34).
        RecordingControls(recording, inputLevel, elapsedSeconds, onRecord, onStop, onOpenSettings, onDismiss)
        // Reference graphs (#61):
        if (reference == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            GraphSwitcher(reference = reference, yours = null, comparison = null)
        }
    }
}

@Composable
internal fun RecordingControls(
    recording: RecordingUiState,
    inputLevel: Float,
    elapsedSeconds: Double,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (recording) {
            RecordingUiState.Idle -> {
                Button(onClick = onRecord, modifier = Modifier.fillMaxWidth()) { Text("Record") }
                Text(
                    "Put the phone near the piano, tap Record, play the phrase, then tap Stop. Take as long as you like.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RecordingUiState.Recording -> {
                Text("Recording… ${formatElapsed(elapsedSeconds)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                LinearProgressIndicator(
                    progress = { (inputLevel * 4).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Microphone level" },
                )
                if (elapsedSeconds >= AudioRecorder.MAX_SECONDS - 15) {
                    Text("Recording stops at ${formatElapsed(AudioRecorder.MAX_SECONDS)}.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
            RecordingUiState.Analyzing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Analyzing…", style = MaterialTheme.typography.titleMedium)
            }
            RecordingUiState.PermissionDenied -> {
                Text(
                    "Piano Trainer needs the microphone to hear you play. You can allow it in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open Settings") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
            is RecordingUiState.Problem -> {
                Text(recording.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Button(onClick = { onDismiss(); onRecord() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
    }
}

/** "2026-10-25T17:03:12.512Z" for a time in epoch milliseconds, for recording file names. */
@OptIn(ExperimentalTime::class)
internal fun isoTime(epochMillis: Long): String = kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toString()

package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.music.NoteEvent
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.SilentAudioPlayer
import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseScorer
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.ui.graphs.ComparisonInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.ui.staff.StaffView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

/**
 * Everything the saved-attempt screen shows, taken only from what was saved with the attempt,
 * so an old attempt always looks exactly as it did.
 */
data class HistoryDetailState(
    val title: String,
    val dateText: String,
    val score: Int,
    val components: List<Pair<String, String>>,
    val expectedLetters: String,
    val detectedLetters: String,
    val expectedStaff: List<NoteEvent>,
    val detectedStaff: List<NoteEvent>,
    val timeSignature: TimeSignature?,
    val phraseStartBeat: Double,
    val pickupBeats: Double,
    val summary: String,
    val wentWell: List<String>,
    val toImprove: List<String>,
    /** Shown when the attempt was analysed by an older (or newer) version than this app's. */
    val versionNote: String?,
    /** How this attempt compares with the others on the same phrase; null for Free Practice. */
    val trend: PhraseTrend? = null,
) {
    companion object {
        fun of(attempt: Attempt, songs: Map<String, Song>, timeZone: TimeZone): HistoryDetailState {
            val song = attempt.songId?.let(songs::get)
            val phrase = attempt.phraseIndex?.let { song?.phrases?.getOrNull(it) }
            // The phrase's real rhythm when its notes still match what was saved; plain quarter notes otherwise.
            val savedPhrase = phrase?.takeIf { it.notes.map { note -> note.pitch } == attempt.expectedNotes }
            val expectedStaff = savedPhrase?.events ?: attempt.expectedNotes.map { NoteEvent(it, 1.0) }
            val scores = attempt.scores
            fun percent(value: Double) = "${(value * 100).roundToInt()}%"
            return HistoryDetailState(
                title = HistoryRowState.of(attempt, songs, timeZone).title,
                dateText = formatDateTime(attempt.createdAt, timeZone),
                score = scores.score,
                components = listOfNotNull(
                    "Notes" to percent(scores.noteAccuracy),
                    "Order" to percent(scores.orderAccuracy),
                    "Timing" to percent(scores.timingAccuracy),
                    "Tempo" to percent(scores.tempoAccuracy),
                    scores.tempoRatio?.let { "Speed" to "${(it * 100).roundToInt()}% of the reference's length" },
                ),
                expectedLetters = attempt.expectedNotes.joinToString(" — ") { it.label },
                detectedLetters = if (attempt.detectedNotes.isEmpty()) {
                    "No notes heard"
                } else {
                    attempt.detectedNotes.joinToString(" — ") { if (it.isReliable) it.pitch!!.label else "?" }
                },
                expectedStaff = expectedStaff,
                // Detected notes as quarter notes: their exact lengths aren't written as rhythm.
                detectedStaff = attempt.detectedNotes.mapNotNull { note -> note.pitch?.takeIf { note.isReliable }?.let { NoteEvent(it, 1.0) } },
                timeSignature = savedPhrase?.let { song?.timeSignature },
                phraseStartBeat = savedPhrase?.startBeat ?: 0.0,
                pickupBeats = if (savedPhrase != null) song?.pickupBeats ?: 0.0 else 0.0,
                summary = attempt.feedback.summary,
                wentWell = attempt.feedback.wentWell,
                toImprove = attempt.feedback.toImprove,
                versionNote = if (attempt.analysisVersion != AudioAnalyzer.ANALYSIS_VERSION) {
                    "These are the scores saved at the time. The analysis has changed since " +
                        "(version ${attempt.analysisVersion} then, ${AudioAnalyzer.ANALYSIS_VERSION} now), so a new attempt may score differently."
                } else {
                    null
                },
            )
        }
    }
}

sealed interface HistoryDetailUiState {
    data object Loading : HistoryDetailUiState
    data class Loaded(val detail: HistoryDetailState) : HistoryDetailUiState
    data class Error(val message: String) : HistoryDetailUiState
}

/**
 * Strictly read-only (spec §13): it only gets [AttemptHistory], so it can't record an attempt or
 * change progress.
 */
class HistoryDetailViewModel(
    private val attemptId: Long,
    private val catalog: SongCatalog,
    private val history: AttemptHistory,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val storage: AppStorage? = null,
    private val references: ReferenceAudioCache? = null,
    private val player: AudioPlayer = SilentAudioPlayer(),
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HistoryDetailUiState>(HistoryDetailUiState.Loading)
    val uiState: StateFlow<HistoryDetailUiState> = _uiState.asStateFlow()

    /** The saved recordings, loaded after the details; null while loading. */
    private val _audio = MutableStateFlow<AttemptAudio?>(null)
    val audio: StateFlow<AttemptAudio?> = _audio.asStateFlow()

    /** The phrase and score behind the comparison graph, when the song still has these notes. */
    private val _comparison = MutableStateFlow<ComparisonInput?>(null)
    val comparison: StateFlow<ComparisonInput?> = _comparison.asStateFlow()

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
                    HistoryDetailUiState.Error("This attempt isn't available any more.")
                } else {
                    // A song that no longer loads shouldn't hide the saved results.
                    val songs = runCatching { catalog.loadSongs().associateBy { it.id } }.getOrDefault(emptyMap())
                    val songId = attempt.songId
                    val phraseIndex = attempt.phraseIndex
                    val trend = if (songId != null && phraseIndex != null) {
                        PhraseTrend.of(history.observeForPhrase(songId, phraseIndex).first(), attempt)
                    } else {
                        null
                    }
                    val song = songId?.let(songs::get)
                    val phrase = phraseIndex?.let { song?.phrases?.getOrNull(it) }?.takeIf { p -> p.notes.map { it.pitch } == attempt.expectedNotes }
                    if (phrase != null && song != null) {
                        _comparison.value = ComparisonInput(phrase, song.tempoBpm, attempt.detectedNotes, PhraseScorer.score(phrase, song.tempoBpm, attempt.detectedNotes))
                    }
                    viewModelScope.launch {
                        _audio.value = AttemptAudio.load(attempt, phrase, song?.tempoBpm, storage, references, AudioAnalyzer(analysisDispatcher))
                    }
                    HistoryDetailUiState.Loaded(HistoryDetailState.of(attempt, songs, timeZone).copy(trend = trend))
                }
            } catch (e: Exception) {
                HistoryDetailUiState.Error("This attempt couldn't be loaded.\n(${e.message})")
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(
    attemptId: Long,
    container: AppContainer,
    viewModel: HistoryDetailViewModel = viewModel {
        HistoryDetailViewModel(
            attemptId, container.songCatalog, container.attemptHistory,
            storage = container.storage, references = container.referenceAudio, player = container.audioPlayer,
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val comparison by viewModel.comparison.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    when (val current = state) {
        HistoryDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is HistoryDetailUiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(current.message, textAlign = TextAlign.Center)
        }
        is HistoryDetailUiState.Loaded -> HistoryDetailContent(current.detail, audio, comparison, viewModel::play, playerState == PlayerState.Error)
    }
}

@Composable
fun HistoryDetailContent(
    detail: HistoryDetailState,
    audio: AttemptAudio? = null,
    comparison: ComparisonInput? = null,
    onPlay: (ByteArray) -> Unit = {},
    playbackFailed: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(detail.title, style = MaterialTheme.typography.headlineSmall)
        Text(detail.dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${detail.score}/100", style = MaterialTheme.typography.displaySmall)
        detail.components.forEach { (name, value) -> Text("$name: $value", style = MaterialTheme.typography.bodyLarge) }
        detail.versionNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
        detail.trend?.let { PhraseTrendView(it) }

        Text("Expected", style = MaterialTheme.typography.titleMedium)
        Text(detail.expectedLetters, style = MaterialTheme.typography.bodyLarge)
        if (detail.expectedStaff.isNotEmpty()) {
            StaffView(detail.expectedStaff, timeSignature = detail.timeSignature, phraseStartBeat = detail.phraseStartBeat, pickupBeats = detail.pickupBeats)
        }
        Text("You played", style = MaterialTheme.typography.titleMedium)
        Text(detail.detectedLetters, style = MaterialTheme.typography.bodyLarge)
        if (detail.detectedStaff.isNotEmpty()) StaffView(detail.detectedStaff)

        Text(detail.summary, style = MaterialTheme.typography.titleMedium)
        if (detail.wentWell.isNotEmpty()) {
            Text("What went well", style = MaterialTheme.typography.titleSmall)
            detail.wentWell.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        if (detail.toImprove.isNotEmpty()) {
            Text("What to improve", style = MaterialTheme.typography.titleSmall)
            detail.toImprove.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        // Playback and graphs; a missing recording says so instead (#62).
        AttemptAudioSection(audio, comparison, onPlay, playbackFailed)
    }
}

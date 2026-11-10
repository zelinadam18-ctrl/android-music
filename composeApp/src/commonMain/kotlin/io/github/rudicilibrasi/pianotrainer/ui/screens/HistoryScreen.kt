package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudicilibrasi.pianotrainer.AppContainer
import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One attempt in the history list. */
data class HistoryRowState(
    val attemptId: Long,
    val title: String,
    val dateText: String,
    val score: Int,
    /** "Unlocked the next phrase", or null when it didn't (or there's no next phrase). */
    val unlockText: String?,
    val freePractice: Boolean,
) {
    val scoreText: String get() = "$score/100"

    companion object {
        fun of(attempt: Attempt, songs: Map<String, Song>, timeZone: TimeZone): HistoryRowState {
            val song = attempt.songId?.let(songs::get)
            val phraseNumber = attempt.phraseIndex?.plus(1)
            val title = when {
                attempt.mode == AttemptMode.FreePractice -> "Free Practice"
                song != null -> "${song.title} · Phrase $phraseNumber"
                else -> "Removed song · Phrase $phraseNumber"
            }
            val hasNextPhrase = song != null && attempt.phraseIndex != null && attempt.phraseIndex!! < song.phrases.lastIndex
            return HistoryRowState(
                attemptId = attempt.id,
                title = title,
                dateText = formatDateTime(attempt.createdAt, timeZone),
                score = attempt.scores.score,
                unlockText = if (attempt.mode == AttemptMode.Lesson && hasNextPhrase && attempt.scores.score >= Progression.UNLOCK_SCORE) {
                    "Unlocked the next phrase"
                } else {
                    null
                },
                freePractice = attempt.mode == AttemptMode.FreePractice,
            )
        }
    }
}

/** "9 Nov 2026, 14:03" in [timeZone]. */
@OptIn(ExperimentalTime::class)
fun formatDateTime(epochMillis: Long, timeZone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone)
    val month = MONTHS[time.month.ordinal]
    return "${time.day} $month ${time.year}, ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A song the list can be filtered by. */
data class SongFilter(val songId: String, val title: String)

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Loaded(
        val rows: List<HistoryRowState>,
        val filters: List<SongFilter>,
        val selectedSongId: String?,
    ) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}

class HistoryViewModel(
    private val catalog: SongCatalog,
    private val history: AttemptHistory,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<HistoryUiState>(HistoryUiState.Loading)
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val selectedSongId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val songs = try {
                catalog.loadSongs().associateBy { it.id }
            } catch (e: Exception) {
                _uiState.value = HistoryUiState.Error("The songs couldn't be loaded.\n(${e.message})")
                return@launch
            }
            // Updates as soon as a new attempt is saved.
            history.observeAll().combine(selectedSongId) { attempts, selected -> attempts to selected }.collect { (attempts, selected) ->
                val filters = attempts.mapNotNull { it.songId }.distinct()
                    .mapNotNull { id -> songs[id]?.let { SongFilter(id, it.title) } }
                    .sortedBy { it.title }
                val shown = if (selected == null) attempts else attempts.filter { it.songId == selected }
                _uiState.value = HistoryUiState.Loaded(shown.map { HistoryRowState.of(it, songs, timeZone) }, filters, selected)
            }
        }
    }

    /** Shows only [songId]'s attempts, or every attempt when null. */
    fun filterBySong(songId: String?) {
        selectedSongId.value = songId
    }
}

@Composable
fun HistoryScreen(
    container: AppContainer,
    onOpenAttempt: (attemptId: Long) -> Unit,
    viewModel: HistoryViewModel = viewModel { HistoryViewModel(container.songCatalog, container.attemptHistory) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryContent(state, onOpenAttempt, viewModel::filterBySong)
}

@Composable
fun HistoryContent(state: HistoryUiState, onOpenAttempt: (Long) -> Unit, onFilter: (String?) -> Unit) {
    when (state) {
        HistoryUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is HistoryUiState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(state.message, textAlign = TextAlign.Center)
        }
        is HistoryUiState.Loaded -> Column(Modifier.fillMaxSize()) {
            if (state.filters.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = state.selectedSongId == null, onClick = { onFilter(null) }, label = { Text("All") })
                    state.filters.forEach { filter ->
                        FilterChip(selected = state.selectedSongId == filter.songId, onClick = { onFilter(filter.songId) }, label = { Text(filter.title) })
                    }
                }
            }
            if (state.rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No attempts yet. Record yourself playing a phrase and it will show up here.", textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows, key = { it.attemptId }) { row -> HistoryRow(row, onClick = { onOpenAttempt(row.attemptId) }) }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(row: HistoryRowState, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.dateText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.unlockText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Text(row.scoreText, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Preview
@Composable
private fun HistoryPreview() = PianoTrainerTheme {
    HistoryContent(
        HistoryUiState.Loaded(
            rows = listOf(
                HistoryRowState(3, "Twinkle, Twinkle, Little Star · Phrase 2", "9 Nov 2026, 14:03", 76, "Unlocked the next phrase", freePractice = false),
                HistoryRowState(2, "Free Practice", "9 Nov 2026, 13:40", 58, null, freePractice = true),
                HistoryRowState(1, "Twinkle, Twinkle, Little Star · Phrase 1", "8 Nov 2026, 19:12", 41, null, freePractice = false),
            ),
            filters = listOf(SongFilter("twinkle", "Twinkle, Twinkle, Little Star")),
            selectedSongId = null,
        ),
        onOpenAttempt = {},
        onFilter = {},
    )
}

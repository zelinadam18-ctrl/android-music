package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.scoring.PhraseFeedback
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptHistory
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.AttemptScores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** In-memory attempts, newest first, like the real repository. */
class FakeAttemptHistory : AttemptHistory {
    private val attempts = MutableStateFlow<List<Attempt>>(emptyList())

    fun add(attempt: Attempt) {
        attempts.value = (attempts.value + attempt).sortedWith(compareByDescending<Attempt> { it.createdAt }.thenByDescending { it.id })
    }

    override suspend fun get(id: Long): Attempt? = attempts.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<Attempt>> = attempts
    override fun observeForSong(songId: String): Flow<List<Attempt>> = attempts.map { list -> list.filter { it.songId == songId } }
    override fun observeForPhrase(songId: String, phraseIndex: Int): Flow<List<Attempt>> =
        attempts.map { list -> list.filter { it.songId == songId && it.phraseIndex == phraseIndex } }
}

fun testAttempt(id: Long, songId: String?, phrase: Int?, score: Int, at: Long, mode: AttemptMode = AttemptMode.Lesson) = Attempt(
    id = id,
    songId = songId,
    phraseIndex = phrase,
    createdAt = at,
    mode = mode,
    expectedNotes = emptyList(),
    detectedNotes = emptyList(),
    referenceWavPath = null,
    userWavPath = "recordings/$id.wav",
    scores = AttemptScores(score, score.toDouble(), 1.0, 1.0, 1.0, 1.0, 1.0),
    feedback = PhraseFeedback("You scored $score/100.", emptyList(), emptyList()),
    analysisVersion = 1,
)

class HistoryTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun songJson(id: String, title: String, phrases: Int) = """{ "id": "$id", "title": "$title", "tempoBpm": 100,
        "timeSignature": "4/4", "key": "C major", "source": "traditional", "license": "Public domain",
        "sourceVerified": false, "phrases": [${List(phrases) { "\"C4/1 D4/1 E4/2\"" }.joinToString(",")}] }"""

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["twinkle", "mary"] }"""
            "files/songs/twinkle.json" -> songJson("twinkle", "Twinkle", 3)
            "files/songs/mary.json" -> songJson("mary", "Mary", 2)
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    // 9 Nov 2026 14:03 UTC.
    private val nov9 = 1_794_232_980_000L

    @Test
    fun datesAreShownInTheLocalTimeZone() {
        assertEquals("9 Nov 2026, 14:03", formatDateTime(nov9, TimeZone.UTC))
        assertEquals("9 Nov 2026, 15:03", formatDateTime(nov9, TimeZone.of("Europe/Paris")))
    }

    @Test
    fun rowsShowSongPhraseScoreAndUnlocks() = runTest {
        val history = FakeAttemptHistory()
        history.add(testAttempt(1, "twinkle", 0, score = 76, at = nov9))
        history.add(testAttempt(2, "twinkle", 2, score = 95, at = nov9 + 60_000))
        history.add(testAttempt(3, null, null, score = 58, at = nov9 + 120_000, mode = AttemptMode.FreePractice))
        history.add(testAttempt(4, "mary", 0, score = 30, at = nov9 + 180_000))
        val viewModel = HistoryViewModel(catalog, history, TimeZone.UTC)
        testScheduler.advanceUntilIdle()

        val rows = assertIs<HistoryUiState.Loaded>(viewModel.uiState.value).rows
        assertEquals(listOf(4L, 3L, 2L, 1L), rows.map { it.attemptId }, "newest first")
        assertEquals(listOf("Mary · Phrase 1", "Free Practice", "Twinkle · Phrase 3", "Twinkle · Phrase 1"), rows.map { it.title })
        assertEquals("30/100", rows[0].scoreText)
        assertNull(rows[0].unlockText, "below 50")
        assertEquals(true, rows[1].freePractice)
        assertNull(rows[1].unlockText, "free practice never unlocks")
        assertNull(rows[2].unlockText, "last phrase has nothing to unlock")
        assertEquals("Unlocked the next phrase", rows[3].unlockText)
        assertEquals("9 Nov 2026, 14:03", rows[3].dateText)
    }

    @Test
    fun filtersBySong() = runTest {
        val history = FakeAttemptHistory()
        history.add(testAttempt(1, "twinkle", 0, score = 76, at = nov9))
        history.add(testAttempt(2, "mary", 0, score = 60, at = nov9 + 1))
        history.add(testAttempt(3, null, null, score = 58, at = nov9 + 2, mode = AttemptMode.FreePractice))
        val viewModel = HistoryViewModel(catalog, history, TimeZone.UTC)
        testScheduler.advanceUntilIdle()

        val all = assertIs<HistoryUiState.Loaded>(viewModel.uiState.value)
        assertEquals(listOf("Mary", "Twinkle"), all.filters.map { it.title })

        viewModel.filterBySong("twinkle")
        testScheduler.advanceUntilIdle()
        val filtered = assertIs<HistoryUiState.Loaded>(viewModel.uiState.value)
        assertEquals(listOf(1L), filtered.rows.map { it.attemptId })
        assertEquals("twinkle", filtered.selectedSongId)

        viewModel.filterBySong(null)
        testScheduler.advanceUntilIdle()
        assertEquals(3, assertIs<HistoryUiState.Loaded>(viewModel.uiState.value).rows.size)
    }

    @Test
    fun theListUpdatesAfterEachNewAttempt() = runTest {
        val history = FakeAttemptHistory()
        val viewModel = HistoryViewModel(catalog, history, TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), assertIs<HistoryUiState.Loaded>(viewModel.uiState.value).rows, "empty state")

        history.add(testAttempt(1, "twinkle", 0, score = 76, at = nov9))
        testScheduler.advanceUntilIdle()
        assertEquals(1, assertIs<HistoryUiState.Loaded>(viewModel.uiState.value).rows.size)
    }
}

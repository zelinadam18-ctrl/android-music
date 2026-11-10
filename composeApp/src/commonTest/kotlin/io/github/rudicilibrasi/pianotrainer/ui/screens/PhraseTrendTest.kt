package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.Dispatchers
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

class PhraseTrendTest {

    private fun attempt(id: Long, score: Int, at: Long) = testAttempt(id, "twinkle", 0, score, at)

    @Test
    fun oneAttempt() {
        val only = attempt(1, 62, 1_000)
        val trend = PhraseTrend.of(listOf(only), only)
        assertNull(trend.sinceLastText)
        assertEquals("Best so far: 62 (this attempt)", trend.bestText)
        assertEquals(listOf(62), trend.scores)
        assertEquals("Attempt 1 of 1 on this phrase", trend.attemptText)
    }

    @Test
    fun twoAttempts() {
        val first = attempt(1, 40, 1_000)
        val second = attempt(2, 48, 2_000)
        val trend = PhraseTrend.of(listOf(second, first), second)
        assertEquals("+8 since last time", trend.sinceLastText)
        assertEquals("Best so far: 48 (this attempt)", trend.bestText)
        assertEquals(listOf(40, 48), trend.scores)
        assertEquals(1, trend.currentIndex)
    }

    @Test
    fun manyAttemptsViewedFromTheMiddleOfHistory() {
        val attempts = listOf(55, 70, 64, 64, 90, 81).mapIndexed { i, s -> attempt(i + 1L, s, 1_000L * (i + 1)) }
        // The fourth attempt, as it was then: same as the one before, best was 70.
        val trend = PhraseTrend.of(attempts.shuffled(kotlin.random.Random(4)), attempts[3])
        assertEquals("Same as last time", trend.sinceLastText)
        assertEquals("Best so far: 70", trend.bestText)
        assertEquals(listOf(55, 70, 64, 64, 90, 81), trend.scores)
        assertEquals("Attempt 4 of 6 on this phrase", trend.attemptText)

        val drop = PhraseTrend.of(attempts, attempts[5])
        assertEquals("−9 since last time", drop.sinceLastText)
        assertEquals("Best so far: 90", drop.bestText)
    }

    @Test
    fun sameTimeAttemptsAreOrderedById() {
        val a = attempt(1, 30, 1_000)
        val b = attempt(2, 50, 1_000)
        assertEquals("+20 since last time", PhraseTrend.of(listOf(b, a), b).sinceLastText)
    }
}

class HistoryDetailTrendTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": [] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    @Test
    fun theSavedAttemptScreenShowsTheTrendForLessonsOnly() = runTest {
        val history = FakeAttemptHistory().apply {
            add(testAttempt(1, "twinkle", 0, score = 40, at = 1_000))
            add(testAttempt(2, "twinkle", 0, score = 55, at = 2_000))
            add(testAttempt(3, "twinkle", 1, score = 99, at = 3_000))
            add(testAttempt(4, null, null, score = 70, at = 4_000, mode = io.github.rudicilibrasi.pianotrainer.database.AttemptMode.FreePractice))
        }
        val lesson = HistoryDetailViewModel(2, catalog, history, TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        val trend = assertIs<HistoryDetailUiState.Loaded>(lesson.uiState.value).detail.trend!!
        assertEquals("+15 since last time", trend.sinceLastText)
        assertEquals(listOf(40, 55), trend.scores, "other phrases aren't mixed in")

        val free = HistoryDetailViewModel(4, catalog, history, TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        assertNull(assertIs<HistoryDetailUiState.Loaded>(free.uiState.value).detail.trend)
    }
}

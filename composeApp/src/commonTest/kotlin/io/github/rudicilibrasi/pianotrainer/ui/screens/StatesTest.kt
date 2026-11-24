package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
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
import kotlin.test.assertTrue

/** The loading, empty and error states of #65, as the screens' view models expose them. */
class StatesTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val brokenCatalog = SongCatalog { throw IllegalStateException("file damaged") }

    @Test
    fun aSongFileThatFailsToLoadShowsAMessageOnEveryScreen() = runTest {
        val songs = SongsViewModel(brokenCatalog, FakeProgress())
        val detail = SongDetailViewModel("twinkle", brokenCatalog, FakeProgress(), io.github.rudicilibrasi.pianotrainer.database.StartLesson(FakeProgress()))
        val lesson = LessonViewModel("twinkle", 0, brokenCatalog, io.github.rudicilibrasi.pianotrainer.database.StartLesson(FakeProgress()), analysisDispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        assertTrue(assertIs<SongsUiState.Error>(songs.uiState.value).message.startsWith("The songs couldn't be loaded."))
        assertIs<SongDetailUiState.Error>(detail.uiState.value)
        assertIs<LessonUiState.Unavailable>(lesson.uiState.value)
    }

    @Test
    fun historyStartsLoadingThenShowsItsEmptyState() = runTest {
        val viewModel = HistoryViewModel(SongCatalog { """{ "songs": [] }""".encodeToByteArray() }, FakeAttemptHistory(), TimeZone.UTC)
        assertEquals(HistoryUiState.Loading, viewModel.uiState.value)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), assertIs<HistoryUiState.Loaded>(viewModel.uiState.value).rows)
    }

    @Test
    fun aPlaybackFailureIsExposedSoTheScreenCanSaySo() = runTest {
        val player = RecordingPlayer()
        val history = FakeAttemptHistory().apply { add(testAttempt(1, null, null, 50, at = 1, mode = io.github.rudicilibrasi.pianotrainer.database.AttemptMode.FreePractice)) }
        val viewModel = HistoryDetailViewModel(1, SongCatalog { """{ "songs": [] }""".encodeToByteArray() }, history, TimeZone.UTC, player = player, analysisDispatcher = dispatcher)
        player.state.value = PlayerState.Error
        assertEquals(PlayerState.Error, viewModel.playerState.value)
        assertTrue(PLAYBACK_FAILED.endsWith("try again."))
    }
}

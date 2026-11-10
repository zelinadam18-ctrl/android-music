package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhraseResultsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["twinkle"] }"""
            "files/songs/twinkle.json" -> """{ "id": "twinkle", "title": "Twinkle", "tempoBpm": 120, "timeSignature": "4/4",
                "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                "phrases": ["C4/1 C4/1 G4/1 G4/1", "A4/1 A4/1 G4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    private val phrase1 = listOf("C4", "C4", "G4", "G4").map(Pitch::parse)
    private val phrase2 = listOf("A4", "A4", "G4").map(Pitch::parse)

    private fun lesson(id: Long, phrase: Int, score: Int) = testAttempt(id, "twinkle", phrase, score, at = id).copy(
        expectedNotes = if (phrase == 0) phrase1 else phrase2,
        detectedNotes = (if (phrase == 0) phrase1 else phrase2).mapIndexed { i, p -> DetectedNote(p, i * 500.0, i * 500.0 + 400, 0.9, false) },
    )

    private fun setUpResults(attempt: io.github.rudicilibrasi.pianotrainer.database.Attempt, progress: FakeProgress = FakeProgress()): PhraseResultsViewModel {
        val history = FakeAttemptHistory().apply { add(attempt) }
        return PhraseResultsViewModel(attempt.id, catalog, history, progress, StartLesson(progress), TimeZone.UTC)
    }

    @Test
    fun aPassingScoreUnlocksAndOffersContinue() = runTest {
        val viewModel = setUpResults(lesson(1, 0, 76))
        testScheduler.advanceUntilIdle()
        val state = assertIs<PhraseResultsUiState.Loaded>(viewModel.uiState.value).state
        assertEquals("Phrase 2 is unlocked.", state.unlockText)
        assertTrue(state.canContinue)
        assertEquals("Continue to phrase 2", state.continueText)
        assertEquals("Retry phrase 1", state.retryText)
        // The comparison views have the phrase and a fresh alignment of the saved notes.
        assertNotNull(state.phrase)
        assertTrue(state.comparison!!.alignment.isPerfect)
        assertNotNull(state.detail.trend)
    }

    @Test
    fun belowFiftyThereIsNoContinueButRetryIsAlwaysThere() = runTest {
        val viewModel = setUpResults(lesson(1, 0, 49))
        testScheduler.advanceUntilIdle()
        val state = assertIs<PhraseResultsUiState.Loaded>(viewModel.uiState.value).state
        assertFalse(state.canContinue)
        assertNull(state.continueText)
        assertEquals("Score 50 or more to unlock phrase 2.", state.unlockText)
        assertEquals("Retry phrase 1", state.retryText)
    }

    @Test
    fun continueOpensTheNextPhraseOnlyIfStartLessonAgrees() = runTest {
        val progress = FakeProgress()
        val viewModel = setUpResults(lesson(1, 0, 76), progress)
        testScheduler.advanceUntilIdle()
        val opened = ArrayList<Int>()

        // Progress wasn't actually saved (out-of-date screen): StartLesson refuses.
        viewModel.continueOn({ _, i -> opened += i }, {})
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), opened)
        assertEquals("Phrase 2 is locked. Score 50 or more on phrase 1 to unlock it.", assertIs<PhraseResultsUiState.Loaded>(viewModel.uiState.value).message)

        // Once progress has it unlocked, Continue goes ahead.
        val song = catalog.loadSongs().single()
        progress.set("twinkle", Progression.recordScore(SongProgress.initial(song), 0, 76).progress)
        viewModel.continueOn({ _, i -> opened += i }, {})
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1), opened)
    }

    @Test
    fun theLastPhraseContinuesToTheSongResults() = runTest {
        val progress = FakeProgress()
        val song = catalog.loadSongs().single()
        progress.set("twinkle", listOf(80, 70).foldIndexed(SongProgress.initial(song)) { i, p, s -> Progression.recordScore(p, i, s).progress })
        val viewModel = setUpResults(lesson(2, 1, 70), progress)
        testScheduler.advanceUntilIdle()
        val state = assertIs<PhraseResultsUiState.Loaded>(viewModel.uiState.value).state
        assertEquals("You completed the last phrase.", state.unlockText)
        assertEquals("See song results", state.continueText)

        var completed: String? = null
        viewModel.continueOn({ _, _ -> }, { completed = it })
        testScheduler.advanceUntilIdle()
        assertEquals("twinkle", completed)
    }

    @Test
    fun freePracticeHasRetryButNoContinueOrUnlock() = runTest {
        val viewModel = setUpResults(testAttempt(5, null, null, 90, at = 5, mode = AttemptMode.FreePractice))
        testScheduler.advanceUntilIdle()
        val state = assertIs<PhraseResultsUiState.Loaded>(viewModel.uiState.value).state
        assertTrue(state.freePractice)
        assertFalse(state.canContinue)
        assertNull(state.unlockText)
        assertEquals("Try these notes again", state.retryText)
        assertNull(state.comparison)
    }

    @Test
    fun missingResultsShowAMessage() = runTest {
        val progress = FakeProgress()
        val viewModel = PhraseResultsViewModel(99, catalog, FakeAttemptHistory(), progress, StartLesson(progress), TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        assertIs<PhraseResultsUiState.Error>(viewModel.uiState.value)
    }
}

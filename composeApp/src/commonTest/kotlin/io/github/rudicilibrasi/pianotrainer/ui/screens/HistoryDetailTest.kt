package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.analysis.DetectedNote
import io.github.rudicilibrasi.pianotrainer.core.music.Pitch
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryDetailTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["twinkle"] }"""
            "files/songs/twinkle.json" -> """{ "id": "twinkle", "title": "Twinkle", "tempoBpm": 100, "timeSignature": "4/4",
                "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                "phrases": ["C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    private val twinkle = listOf("C4", "C4", "G4", "G4", "A4", "A4", "G4").map(Pitch::parse)

    private fun saved(version: Int = AudioAnalyzer.ANALYSIS_VERSION) = testAttempt(7, "twinkle", 0, score = 76, at = 1_794_232_980_000L).copy(
        expectedNotes = twinkle,
        detectedNotes = listOf(
            DetectedNote(Pitch.parse("C4"), 0.0, 500.0, 0.9, uncertain = false),
            DetectedNote(null, 600.0, 900.0, 0.1, uncertain = true),
            DetectedNote(Pitch.parse("G4"), 1_000.0, 1_500.0, 0.9, uncertain = false),
        ),
        analysisVersion = version,
    )

    private fun load(history: FakeAttemptHistory, id: Long = 7) = HistoryDetailViewModel(id, catalog, history, TimeZone.UTC)

    @Test
    fun showsTheAttemptExactlyAsSaved() = runTest {
        val history = FakeAttemptHistory().apply { add(saved()) }
        val viewModel = load(history)
        testScheduler.advanceUntilIdle()
        val detail = assertIs<HistoryDetailUiState.Loaded>(viewModel.uiState.value).detail

        assertEquals("Twinkle · Phrase 1", detail.title)
        assertEquals("9 Nov 2026, 14:03", detail.dateText)
        assertEquals(76, detail.score)
        assertEquals("C4 — C4 — G4 — G4 — A4 — A4 — G4", detail.expectedLetters)
        assertEquals("C4 — ? — G4", detail.detectedLetters)
        // The song still matches, so the expected staff uses the real rhythm and time signature.
        assertEquals(2.0, detail.expectedStaff.last().beats)
        assertNotNull(detail.timeSignature)
        // Uncertain notes are left off the detected staff.
        assertEquals(listOf("C4", "G4"), detail.detectedStaff.map { it.pitch!!.label })
        assertEquals("You scored 76/100.", detail.summary)
        assertNull(detail.versionNote)
    }

    @Test
    fun anOlderAnalysisVersionShowsTheSavedScoresWithANote() = runTest {
        val history = FakeAttemptHistory().apply { add(saved(version = AudioAnalyzer.ANALYSIS_VERSION - 1)) }
        val viewModel = load(history)
        testScheduler.advanceUntilIdle()
        val detail = assertIs<HistoryDetailUiState.Loaded>(viewModel.uiState.value).detail
        assertEquals(76, detail.score)
        assertTrue(detail.versionNote!!.startsWith("These are the scores saved at the time."))
    }

    @Test
    fun aSongThatChangedSinceFallsBackToPlainNotes() = runTest {
        val history = FakeAttemptHistory().apply { add(saved().copy(expectedNotes = listOf(Pitch.parse("E4"), Pitch.parse("D4")))) }
        val viewModel = load(history)
        testScheduler.advanceUntilIdle()
        val detail = assertIs<HistoryDetailUiState.Loaded>(viewModel.uiState.value).detail
        assertEquals("E4 — D4", detail.expectedLetters)
        assertEquals(listOf(1.0, 1.0), detail.expectedStaff.map { it.beats })
        assertNull(detail.timeSignature)
    }

    @Test
    fun aMissingAttemptShowsAMessage() = runTest {
        val viewModel = load(FakeAttemptHistory(), id = 99)
        testScheduler.advanceUntilIdle()
        assertIs<HistoryDetailUiState.Error>(viewModel.uiState.value)
    }

    @Test
    fun openingAHistoryEntryLeavesProgressUnchanged() = runTest {
        // The screen only gets read-only AttemptHistory, so it has no way to change progress; this
        // checks that opening every entry leaves saved progress and attempts exactly as they were.
        val progress = FakeProgress()
        val songs = catalog.loadSongs()
        val before = progress.observeAll(songs).first()
        val history = FakeAttemptHistory().apply {
            add(saved())
            add(testAttempt(8, "twinkle", 0, score = 20, at = 1L))
        }
        val attemptsBefore = history.observeAll().first()
        for (id in listOf(7L, 8L)) {
            load(history, id)
            testScheduler.advanceUntilIdle()
        }
        assertEquals(before, progress.observeAll(songs).first())
        assertEquals(SongProgress.initial(songs.single()), progress.get(songs.single()))
        assertEquals(attemptsBefore, history.observeAll().first())
    }
}

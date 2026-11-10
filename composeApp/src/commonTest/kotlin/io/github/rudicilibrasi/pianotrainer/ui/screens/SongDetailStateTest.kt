package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SongDetailStateTest {

    private val song = Song(
        id = "twinkle",
        title = "Twinkle, Twinkle, Little Star",
        composer = "Traditional",
        tempoBpm = 100.0,
        timeSignature = TimeSignature(4, 4),
        key = "C major",
        source = "traditional",
        license = "Public domain",
        sourceVerified = false,
        phrases = listOf(
            SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2"),
            SongFormat.parseEvents("F4/1 F4/1 E4/1 E4/1 R/1 D4/1 C4/2"),
            SongFormat.parseEvents("G4/4"),
        ),
    )

    @Test
    fun withoutProgressOnlyPhrase1IsUnlocked() {
        val detail = SongDetailState.of(song)
        assertEquals(listOf(true, false, false), detail.phrases.map { it.unlocked })
        assertTrue(detail.canOpen(0))
        assertFalse(detail.canOpen(1))
        assertFalse(detail.canOpen(2))
        assertFalse(detail.canOpen(7))
        assertEquals("Continue with phrase 1", detail.continueText)
    }

    @Test
    fun rowsShowNumberNoteCountNotesAndScores() {
        val progress = Progression.recordScore(SongProgress.initial(song), 0, 82).progress
            .let { Progression.recordScore(it, 0, 64).progress }
        val rows = SongDetailState.of(song, progress).phrases
        assertEquals("Phrase 1 · 7 notes", rows[0].title)
        assertEquals("C4 C4 G4 G4 A4 A4 G4", rows[0].notesText)
        assertEquals("Best 82 · Latest 64", rows[0].statusText)
        // Rests are left out of the letters.
        assertEquals("F4 F4 E4 E4 D4 C4", rows[1].notesText)
        assertEquals("Not attempted yet", rows[1].statusText)
        assertEquals("Phrase 3 · 1 note", rows[2].title)
        assertEquals("Score 50 or more on phrase 2 to unlock", rows[2].statusText)
    }

    @Test
    fun continueOpensTheCurrentPhrase() {
        val progress = Progression.recordScore(SongProgress.initial(song), 0, 70).progress
        val detail = SongDetailState.of(song, progress)
        assertEquals(1, detail.currentPhraseIndex)
        assertTrue(detail.canOpen(1))
        assertFalse(detail.completed)
    }
}

class SongDetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["a"] }"""
            "files/songs/a.json" -> """{ "id": "a", "title": "A Song", "tempoBpm": 100, "timeSignature": "4/4",
                "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                "phrases": ["C4/1 D4/1 E4/2", "E4/1 D4/1 C4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    @Test
    fun loadsTheSong() = runTest {
        val viewModel = SongDetailViewModel("a", catalog)
        testScheduler.advanceUntilIdle()
        val state = assertIs<SongDetailUiState.Loaded>(viewModel.uiState.value)
        assertEquals("A Song", state.detail.title)
        assertEquals(2, state.detail.phrases.size)
    }

    @Test
    fun anUnknownSongIsAnError() = runTest {
        val viewModel = SongDetailViewModel("missing", catalog)
        testScheduler.advanceUntilIdle()
        assertIs<SongDetailUiState.Error>(viewModel.uiState.value)
    }
}

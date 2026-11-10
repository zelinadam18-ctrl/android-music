package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongCardStateTest {

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
        phrases = List(3) { SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1") },
    )

    private fun SongProgress.score(phrase: Int, score: Int) = Progression.recordScore(this, phrase, score).progress

    @Test
    fun aNewSongIsNotStarted() {
        val card = SongCardState.of(song)
        assertEquals(SongStatus.NotStarted, card.status)
        assertEquals("0 of 3 phrases completed", card.progressText)
        assertNull(card.bestScoreText)
        assertEquals(false, card.starEarned)
        assertEquals("Traditional", card.composer)
    }

    @Test
    fun aSongWithAttemptsIsInProgress() {
        val progress = SongProgress.initial(song).score(0, 70).score(1, 40)
        val card = SongCardState.of(song, progress)
        assertEquals(SongStatus.InProgress, card.status)
        assertEquals("1 of 3 phrases completed", card.progressText)
        assertEquals("Best score 55", card.bestScoreText)
    }

    @Test
    fun aCompletedSongWithAHighScoreHasAStar() {
        val progress = SongProgress.initial(song).score(0, 95).score(1, 92).score(2, 97)
        val card = SongCardState.of(song, progress)
        assertEquals(SongStatus.Completed, card.status)
        assertEquals("3 of 3 phrases completed", card.progressText)
        assertTrue(card.starEarned)
    }

    @Test
    fun oneCompletedPhraseIsSingular() {
        val card = SongCardState.of(song).copy(phraseCount = 1)
        assertEquals("0 of 1 phrase completed", card.progressText)
    }
}

class SongsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun catalog(files: Map<String, String>) = SongCatalog { path ->
        (files[path] ?: throw IllegalStateException("missing resource $path")).encodeToByteArray()
    }

    @Test
    fun loadsSongsInIndexOrderAsNotStarted() = runTest {
        fun song(id: String, title: String) = """{ "id": "$id", "title": "$title", "tempoBpm": 100, "timeSignature": "4/4",
            "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
            "phrases": ["C4/1 D4/1 E4/2"] }"""
        val viewModel = SongsViewModel(
            progress = FakeProgress(),
            catalog = catalog(
                mapOf(
                    "files/songs/index.json" to """{ "songs": ["b", "a"] }""",
                    "files/songs/a.json" to song("a", "A Song"),
                    "files/songs/b.json" to song("b", "B Song"),
                ),
            ),
        )
        assertEquals(SongsUiState.Loading, viewModel.uiState.value)
        testScheduler.advanceUntilIdle()
        val state = assertIs<SongsUiState.Loaded>(viewModel.uiState.value)
        assertEquals(listOf("B Song", "A Song"), state.songs.map { it.title })
        assertTrue(state.songs.all { it.status == SongStatus.NotStarted })
    }

    @Test
    fun cardsUpdateAsSoonAsProgressIsSaved() = runTest {
        val song = """{ "id": "a", "title": "A Song", "tempoBpm": 100, "timeSignature": "4/4", "key": "C major",
            "source": "traditional", "license": "Public domain", "sourceVerified": false,
            "phrases": ["C4/1 D4/1 E4/2", "E4/1 D4/1 C4/2"] }"""
        val progress = FakeProgress()
        val catalog = catalog(mapOf("files/songs/index.json" to """{ "songs": ["a"] }""", "files/songs/a.json" to song))
        val viewModel = SongsViewModel(catalog, progress)
        testScheduler.advanceUntilIdle()
        val loaded = catalog.loadSongs().single()

        progress.set("a", Progression.recordScore(SongProgress.initial(loaded), 0, 80).progress)
        testScheduler.advanceUntilIdle()
        val card = assertIs<SongsUiState.Loaded>(viewModel.uiState.value).songs.single()
        assertEquals(SongStatus.InProgress, card.status)
        assertEquals("1 of 2 phrases completed", card.progressText)
        assertEquals("Best score 80", card.bestScoreText)
    }

    @Test
    fun aBrokenCatalogShowsAnError() = runTest {
        val viewModel = SongsViewModel(catalog(emptyMap()), FakeProgress())
        testScheduler.advanceUntilIdle()
        assertIs<SongsUiState.Error>(viewModel.uiState.value)
    }
}

package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.music.Song
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.core.music.TimeSignature
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.Attempt
import io.github.rudicilibrasi.pianotrainer.database.AttemptScores
import io.github.rudicilibrasi.pianotrainer.database.ProgressDates
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongCompleteTest {

    private val song = Song(
        id = "twinkle", title = "Twinkle", composer = null, tempoBpm = 100.0, timeSignature = TimeSignature(4, 4),
        key = "C major", source = "traditional", license = "Public domain", sourceVerified = false,
        phrases = List(4) { SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1") },
    )

    private fun progress(vararg scores: Int): SongProgress =
        scores.foldIndexed(SongProgress.initial(song)) { i, p, s -> Progression.recordScore(p, i, s).progress }

    private fun attempt(id: Long, phrase: Int, score: Int, notes: Double = 1.0, timing: Double = 1.0) =
        testAttempt(id, song.id, phrase, score, at = id).copy(scores = AttemptScores(score, score.toDouble(), notes, 1.0, timing, 1.0, 1.0))

    private val nov9 = 1_794_232_980_000L

    @Test
    fun starPathCelebratesWithTheDate() {
        val state = SongCompleteState.of(song, progress(95, 92, 100, 88), emptyList(), ProgressDates(1L, nov9, nov9), TimeZone.UTC)
        assertTrue(state.completed && state.starEarned)
        assertEquals(94, state.overallScore)
        assertEquals("Song mastered! You earned the star for Twinkle.", state.headline)
        assertEquals("Star earned on 9 Nov 2026, 14:03", state.starText)
        assertEquals("4 of 4 phrases completed", state.phrasesText)
        assertEquals(listOf("Phrase 4: best 88"), state.weakestPhrases, "only phrases below 90 count as weak")
    }

    @Test
    fun noStarPathNamesThePhrasesToReplay() {
        val attempts = listOf(attempt(1, 0, 95), attempt(2, 1, 64, notes = 0.4), attempt(3, 2, 90), attempt(4, 3, 71, notes = 0.6))
        val state = SongCompleteState.of(song, progress(95, 64, 90, 71), attempts, ProgressDates(1L, null, 5L), TimeZone.UTC)
        assertTrue(state.completed)
        assertFalse(state.starEarned)
        assertEquals(80, state.overallScore)
        assertEquals("Song complete! You need 90 overall for the mastery star. Replay phrases 2 and 4 to raise your score.", state.headline)
        assertEquals(listOf("Phrase 2: best 64", "Phrase 4: best 71"), state.weakestPhrases)
        assertEquals("Most points were lost on wrong or missed notes.", state.commonMistake)
        assertEquals("Replay phrases 2 and 4: they have the lowest best scores.", state.recommendations.first())
        assertNull(state.starText)
    }

    @Test
    fun theMostCommonMistakeComesFromEachPhrasesBestAttempt() {
        // An early bad-notes attempt on phrase 1 doesn't count; its best attempt lost points on rhythm.
        val attempts = listOf(attempt(1, 0, 30, notes = 0.1), attempt(2, 0, 85, timing = 0.3))
        val state = SongCompleteState.of(song, progress(85), attempts, null, TimeZone.UTC)
        assertEquals("Most points were lost on rhythm.", state.commonMistake)
    }

    @Test
    fun anUnfinishedSongSaysHowFarAlong() {
        val state = SongCompleteState.of(song, progress(70, 55), emptyList(), null, TimeZone.UTC)
        assertFalse(state.completed)
        assertEquals("Keep going! Complete every phrase to finish the song (2 of 4 so far).", state.headline)
        assertEquals(listOf("Continue with phrase 3."), state.recommendations)
        assertNull(state.commonMistake)
    }

    @Test
    fun onePhraseToReplayIsSingular() {
        val state = SongCompleteState.of(song, progress(95, 95, 95, 60), emptyList(), null, TimeZone.UTC)
        assertEquals("Song complete! You need 90 overall for the mastery star. Replay phrase 4 to raise your score.", state.headline)
        assertEquals("Replay phrase 4: it has the lowest best scores.", state.recommendations.first())
    }
}

class SongCompleteViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["a"] }"""
            "files/songs/a.json" -> """{ "id": "a", "title": "A Song", "tempoBpm": 100, "timeSignature": "4/4",
                "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                "phrases": ["C4/1 D4/1 E4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    @Test
    fun updatesWhenTheStarIsEarned() = runTest {
        val progress = FakeProgress()
        val viewModel = SongCompleteViewModel("a", catalog, progress, FakeAttemptHistory(), TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        assertFalse(assertIs<SongCompleteUiState.Loaded>(viewModel.uiState.value).state.completed)

        val song = catalog.loadSongs().single()
        progress.setDates("a", ProgressDates(1L, 1_794_232_980_000L, 1L))
        progress.set("a", Progression.recordScore(SongProgress.initial(song), 0, 96).progress)
        testScheduler.advanceUntilIdle()
        val state = assertIs<SongCompleteUiState.Loaded>(viewModel.uiState.value).state
        assertTrue(state.starEarned)
        assertEquals("Star earned on 9 Nov 2026, 14:03", state.starText)
    }

    @Test
    fun anUnknownSongIsAnError() = runTest {
        val viewModel = SongCompleteViewModel("missing", catalog, FakeProgress(), FakeAttemptHistory(), TimeZone.UTC)
        testScheduler.advanceUntilIdle()
        assertIs<SongCompleteUiState.Error>(viewModel.uiState.value)
    }
}

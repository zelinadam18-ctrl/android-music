package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LessonTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val catalog = SongCatalog { path ->
        when (path) {
            "files/songs/index.json" -> """{ "songs": ["twinkle"] }"""
            "files/songs/twinkle.json" -> """{ "id": "twinkle", "title": "Twinkle", "tempoBpm": 120, "timeSignature": "4/4",
                "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                "phrases": ["C4/1 C4/1 G4/1 G4/1 A4/1 A4/1 G4/2", "F4/1 F4/1 E4/1 R/1 D4/1 D4/1 C4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    private fun lesson(phrase: Int, progress: FakeProgress = FakeProgress()) =
        LessonViewModel("twinkle", phrase, catalog, StartLesson(progress), analysisDispatcher = dispatcher)

    @Test
    fun theFirstPhraseOpensWithEverythingFromTheSongModel() = runTest {
        val viewModel = lesson(0)
        testScheduler.advanceUntilIdle()
        val ready = assertIs<LessonUiState.Ready>(viewModel.uiState.value)
        val lesson = ready.lesson
        assertEquals("Twinkle", lesson.title)
        assertEquals("Phrase 1 of 2", lesson.phraseText)
        assertEquals("7 notes", lesson.noteCountText)
        assertEquals("C4 — C4 — G4 — G4 — A4 — A4 — G4", lesson.lettersText)
        // The reference was synthesized and analysed for the graphs, and finds the same notes.
        val reference = assertNotNull(ready.reference)
        assertEquals(lesson.phrase.notes.map { it.pitch }, reference.notes.map { it.pitch })
    }

    @Test
    fun listenPlaysTheRenderedReferenceAndStopsWhenLeaving() = runTest {
        val player = RecordingPlayer()
        val viewModel = LessonViewModel("twinkle", 0, catalog, StartLesson(FakeProgress()), player, dispatcher)
        testScheduler.advanceUntilIdle()
        val ready = assertIs<LessonUiState.Ready>(viewModel.uiState.value)
        val wav = assertNotNull(ready.referenceWav)
        // The WAV played is the reference the graphs were drawn from: 7 notes at 120 BPM plus release.
        val clip = io.github.rudicilibrasi.pianotrainer.core.audio.Wav.decode(wav)
        assertEquals(4.0, clip.samples.size.toDouble() / clip.sampleRate, 0.2)

        viewModel.toggleListen()
        assertEquals(listOf("play ${wav.size}"), player.calls)
        viewModel.toggleListen()
        assertEquals(listOf("play ${wav.size}", "stop"), player.calls)
    }

    @Test
    fun aLockedPhraseCannotBeOpenedByAnyRoute() = runTest {
        val viewModel = lesson(1)
        testScheduler.advanceUntilIdle()
        val unavailable = assertIs<LessonUiState.Unavailable>(viewModel.uiState.value)
        assertEquals("Phrase 2 is locked. Score 50 or more on phrase 1 to unlock it.", unavailable.message)
    }

    @Test
    fun anUnlockedPhraseOpensAndLeavesRestsOutOfTheLetters() = runTest {
        val progress = FakeProgress()
        val song = catalog.loadSongs().single()
        progress.set("twinkle", Progression.recordScore(SongProgress.initial(song), 0, 60).progress)
        val viewModel = lesson(1, progress)
        testScheduler.advanceUntilIdle()
        val lesson = assertIs<LessonUiState.Ready>(viewModel.uiState.value).lesson
        assertEquals("F4 — F4 — E4 — D4 — D4 — C4", lesson.lettersText)
        assertEquals("6 notes", lesson.noteCountText)
    }

    @Test
    fun aMissingPhraseOrSongIsUnavailable() = runTest {
        val viewModel = lesson(7)
        testScheduler.advanceUntilIdle()
        assertIs<LessonUiState.Unavailable>(viewModel.uiState.value)
        val missing = LessonViewModel("nope", 0, catalog, StartLesson(FakeProgress()), analysisDispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        assertIs<LessonUiState.Unavailable>(missing.uiState.value)
    }
}

/** Records what was asked of it, and pretends to play until stopped. */
class RecordingPlayer : io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer {
    val calls = ArrayList<String>()
    override val state = kotlinx.coroutines.flow.MutableStateFlow(io.github.rudicilibrasi.pianotrainer.audio.PlayerState.Idle)
    override val positionMs = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    override fun play(wav: ByteArray) {
        calls += "play ${wav.size}"
        state.value = io.github.rudicilibrasi.pianotrainer.audio.PlayerState.Playing
    }
    override fun playFile(path: String) {
        calls += "playFile $path"
    }
    override fun stop() {
        calls += "stop"
        state.value = io.github.rudicilibrasi.pianotrainer.audio.PlayerState.Idle
    }
}

class StaffDescriptionTest {
    @Test
    fun readsThePhraseNumberAndItsNotes() {
        val phrase = io.github.rudicilibrasi.pianotrainer.core.music.Phrase(
            1, io.github.rudicilibrasi.pianotrainer.core.music.SongFormat.parseEvents("F4/1 F4/1 E4/1 E4/1 R/1 D4/1 D4/1 C4/2"), 0.0,
        )
        assertEquals("Phrase 2: F4, F4, E4, E4, D4, D4, C4", staffDescription(phrase))
        assertEquals("Free practice notes: F4, F4, E4, E4, D4, D4, C4", staffDescription(phrase, "Free practice notes"))
    }
}

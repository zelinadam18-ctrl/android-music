package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.FreePractice
import io.github.rudicilibrasi.pianotrainer.database.NewAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FreePracticeScreenTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun playingTheNotesSavesAFreePracticeAttemptWithNoSongOrProgress() = runTest {
        // The "microphone" hears the six notes played perfectly: their own reference.
        val phrase = FreePractice.newPhrase(Random(5))
        val take = AudioClip(Synthesizer().renderPhrase(phrase, FreePractice.TEMPO_BPM), 44100)
        val saved = ArrayList<NewAttempt>()
        val viewModel = FreePracticeViewModel(
            recorder = FakeRecorder(take),
            saveAttempt = { saved += it; 7L },
            random = Random(5),
            dispatcher = dispatcher,
            now = { 3_000L },
        )
        testScheduler.advanceUntilIdle()
        assertEquals(phrase.notes.map { it.pitch }, viewModel.uiState.value.phrase.notes.map { it.pitch })
        assertNotNull(viewModel.uiState.value.referenceWav)

        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        var opened: Long? = null
        viewModel.stopRecording { opened = it }
        testScheduler.advanceUntilIdle()

        assertEquals(7L, opened)
        val attempt = saved.single()
        assertEquals(AttemptMode.FreePractice, attempt.mode)
        assertNull(attempt.songId)
        assertNull(attempt.phraseIndex)
        assertEquals(100, attempt.scores.score)
        assertEquals("You scored 100/100.", attempt.feedback.summary, "no unlock talk in Free Practice")
    }

    @Test
    fun newNotesGivesAFreshSetAndReferenceRendersAgain() = runTest {
        val viewModel = FreePracticeViewModel(random = Random(1), dispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        val first = viewModel.uiState.value.phrase.notes.map { it.pitch }
        var changed = false
        repeat(5) {
            viewModel.newNotes()
            assertNull(viewModel.uiState.value.referenceWav, "the old reference is dropped")
            testScheduler.advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.referenceWav)
            if (viewModel.uiState.value.phrase.notes.map { it.pitch } != first) changed = true
        }
        assertEquals(true, changed)
        assertEquals(6, viewModel.uiState.value.phrase.noteCount)
        assertNotEquals("", viewModel.uiState.value.lettersText)
    }
}

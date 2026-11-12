package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.audio.AudioRecorder
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.audio.RecorderState
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import io.github.rudicilibrasi.pianotrainer.core.audio.SynthNote
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.progress.Progression
import io.github.rudicilibrasi.pianotrainer.core.progress.SongProgress
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import io.github.rudicilibrasi.pianotrainer.data.StoredFile
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import io.github.rudicilibrasi.pianotrainer.database.AttemptMode
import io.github.rudicilibrasi.pianotrainer.database.NewAttempt
import io.github.rudicilibrasi.pianotrainer.database.RecordAttemptResult
import io.github.rudicilibrasi.pianotrainer.database.StartLesson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A microphone that "hears" whatever clip it is given. */
class FakeRecorder(private val clip: AudioClip?, private val grant: Boolean = true) : AudioRecorder {
    override val state = MutableStateFlow(RecorderState.Idle)
    override val level = MutableStateFlow(0f)
    override val elapsedSeconds = MutableStateFlow(0.0)
    var cancelled = false

    override suspend fun start(): Boolean {
        if (!grant) {
            state.value = RecorderState.PermissionDenied
            return false
        }
        state.value = RecorderState.Recording
        return true
    }

    override fun stop(): AudioClip? {
        state.value = RecorderState.Idle
        return clip
    }

    override fun cancel() {
        cancelled = true
    }
}

class LessonRecordingTest {

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
                "phrases": ["C4/1 C4/1 G4/1 G4/1", "A4/1 A4/1 G4/2"] }"""
            else -> throw IllegalStateException("missing resource $path")
        }.encodeToByteArray()
    }

    /** The student plays phrase 1 perfectly, half a second into the recording. */
    private val perfectTake: AudioClip = run {
        val notes = listOf("C4", "C4", "G4", "G4").mapIndexed { i, label ->
            SynthNote(io.github.rudicilibrasi.pianotrainer.core.music.Pitch.parse(label), 0.5 + i * 0.5, 0.45)
        }
        AudioClip(Synthesizer().render(notes, 2.8), 44100)
    }

    private fun lesson(recorder: AudioRecorder, saved: MutableList<NewAttempt>, player: RecordingPlayer = RecordingPlayer()) = LessonViewModel(
        songId = "twinkle",
        phraseIndex = 0,
        catalog = catalog,
        startLesson = StartLesson(FakeProgress()),
        player = player,
        analysisDispatcher = dispatcher,
        recorder = recorder,
        saveAttempt = { song, attempt ->
            saved += attempt
            RecordAttemptResult.Recorded(42, Progression.recordScore(SongProgress.initial(song), 0, attempt.scores.score))
        },
        now = { 1_000L },
    )

    @Test
    fun aRecordingIsAnalysedScoredSavedAndOpensTheResults() = runTest {
        val saved = ArrayList<NewAttempt>()
        val player = RecordingPlayer()
        val viewModel = lesson(FakeRecorder(perfectTake), saved, player)
        testScheduler.advanceUntilIdle()

        player.state.value = PlayerState.Playing
        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        assertEquals(RecordingUiState.Recording, viewModel.recording.value)
        assertTrue("stop" in player.calls, "recording stops playback")
        viewModel.toggleListen()
        assertTrue(player.calls.none { it.startsWith("play") }, "Listen is disabled while recording")

        var opened: Long? = null
        viewModel.stopRecording { opened = it }
        assertEquals(RecordingUiState.Analyzing, viewModel.recording.value)
        testScheduler.advanceUntilIdle()

        assertEquals(42L, opened)
        assertEquals(RecordingUiState.Idle, viewModel.recording.value)
        val attempt = saved.single()
        assertEquals(AttemptMode.Lesson, attempt.mode)
        assertEquals("twinkle", attempt.songId)
        assertEquals(0, attempt.phraseIndex)
        assertEquals(100, attempt.scores.score, "a perfect take scores 100")
        assertEquals(listOf("C4", "C4", "G4", "G4"), attempt.detectedNotes.map { it.pitch!!.label })
        assertEquals("You scored 100/100. The next phrase is now unlocked.", attempt.feedback.summary)
        assertEquals(1_000L, attempt.createdAt)
        assertNull(attempt.userWavPath, "no storage in this test")
    }

    @Test
    fun withStorageTheReferenceIsCachedAndTheTakeIsSaved() = runTest {
        val storage = AppStorage("/app".toPath(), FakeFileSystem())
        val saved = ArrayList<NewAttempt>()
        val viewModel = LessonViewModel(
            songId = "twinkle", phraseIndex = 0, catalog = catalog, startLesson = StartLesson(FakeProgress()),
            analysisDispatcher = dispatcher, recorder = FakeRecorder(perfectTake),
            saveAttempt = { song, attempt -> saved += attempt; RecordAttemptResult.Recorded(1, Progression.recordScore(SongProgress.initial(song), 0, attempt.scores.score)) },
            now = { 1_794_232_980_000L },
            references = ReferenceAudioCache(storage),
            saveRecording = storage::write,
        )
        testScheduler.advanceUntilIdle()
        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        viewModel.stopRecording {}
        testScheduler.advanceUntilIdle()

        val attempt = saved.single()
        assertEquals("recordings/2026-11-09T14-03-00_twinkle_p1.wav", attempt.userWavPath)
        assertTrue(attempt.referenceWavPath!!.startsWith("reference/twinkle_p1_"))
        // Both files are really there, and the recording is the take.
        val take = assertIs<StoredFile.Found>(storage.read(attempt.userWavPath!!))
        assertEquals(perfectTake.samples.size, Wav.decode(take.bytes).samples.size)
        assertIs<StoredFile.Found>(storage.read(attempt.referenceWavPath!!))
    }

    @Test
    fun aRefusedMicrophoneOffersSettings() = runTest {
        val viewModel = lesson(FakeRecorder(perfectTake, grant = false), ArrayList())
        testScheduler.advanceUntilIdle()
        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        assertEquals(RecordingUiState.PermissionDenied, viewModel.recording.value)
        viewModel.dismissProblem()
        assertEquals(RecordingUiState.Idle, viewModel.recording.value)
    }

    @Test
    fun anEmptyRecordingSavesNothing() = runTest {
        val saved = ArrayList<NewAttempt>()
        val viewModel = lesson(FakeRecorder(null), saved)
        testScheduler.advanceUntilIdle()
        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        viewModel.stopRecording { error("nothing to open") }
        testScheduler.advanceUntilIdle()
        assertIs<RecordingUiState.Problem>(viewModel.recording.value)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun aFailedSaveLeavesAMessageAndNoResults() = runTest {
        val viewModel = LessonViewModel(
            songId = "twinkle", phraseIndex = 0, catalog = catalog, startLesson = StartLesson(FakeProgress()),
            analysisDispatcher = dispatcher, recorder = FakeRecorder(perfectTake),
            saveAttempt = { _, _ -> throw IllegalStateException("disk full") },
        )
        testScheduler.advanceUntilIdle()
        viewModel.startRecording()
        testScheduler.advanceUntilIdle()
        viewModel.stopRecording { error("must not open results") }
        testScheduler.advanceUntilIdle()
        assertEquals("The recording couldn't be analysed. Please try again.", assertIs<RecordingUiState.Problem>(viewModel.recording.value).message)
    }

    @Test
    fun elapsedTimeIsShownAsMinutesAndSeconds() {
        assertEquals("0:07", formatElapsed(7.9))
        assertEquals("1:05", formatElapsed(65.0))
        assertEquals("2:00", formatElapsed(AudioRecorder.MAX_SECONDS))
    }
}

package io.github.rudicilibrasi.pianotrainer.ui.screens

import io.github.rudicilibrasi.pianotrainer.core.analysis.AudioAnalyzer
import io.github.rudicilibrasi.pianotrainer.core.audio.Synthesizer
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
import io.github.rudicilibrasi.pianotrainer.core.music.Phrase
import io.github.rudicilibrasi.pianotrainer.core.music.SongFormat
import io.github.rudicilibrasi.pianotrainer.data.AppStorage
import io.github.rudicilibrasi.pianotrainer.data.ReferenceAudioCache
import io.github.rudicilibrasi.pianotrainer.data.SongCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AttemptAudioTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val storage = AppStorage("/app".toPath(), FakeFileSystem())
    private val phrase = Phrase(0, SongFormat.parseEvents("C4/1 C4/1 G4/1 G4/1"), 0.0)
    private val wav = Synthesizer().let { Wav.encode(it.renderPhrase(phrase, 120.0), it.sampleRate) }

    private fun attempt(userPath: String?, referencePath: String? = null) = testAttempt(1, "twinkle", 0, 80, at = 1)
        .copy(expectedNotes = phrase.notes.map { it.pitch!! }, userWavPath = userPath, referenceWavPath = referencePath)

    @Test
    fun aSavedRecordingIsLoadedAndAnalysed() = runTest {
        storage.write("recordings/take.wav", wav)
        val audio = AttemptAudio.load(attempt("recordings/take.wav"), phrase, 120.0, storage, ReferenceAudioCache(storage), AudioAnalyzer(dispatcher))
        val yours = assertIs<SavedRecording.Available>(audio.yours)
        assertEquals(listOf("C4", "C4", "G4", "G4"), yours.analysis.notes.map { it.pitch!!.label })
        assertIs<SavedRecording.Available>(audio.reference)
    }

    @Test
    fun aMissingRecordingIsUnavailableNotACrash() = runTest {
        val audio = AttemptAudio.load(attempt("recordings/deleted.wav"), phrase, 120.0, storage, ReferenceAudioCache(storage), AudioAnalyzer(dispatcher))
        assertEquals(SavedRecording.Unavailable("Recording unavailable"), audio.yours)
        // The reference can always be regenerated from the song data.
        assertIs<SavedRecording.Available>(audio.reference)
    }

    @Test
    fun aCorruptRecordingIsUnavailableNotACrash() = runTest {
        storage.write("recordings/broken.wav", byteArrayOf(0x52, 0x49, 0x46, 0x46, 1, 2, 3))
        val audio = AttemptAudio.load(attempt("recordings/broken.wav"), phrase, 120.0, storage, ReferenceAudioCache(storage), AudioAnalyzer(dispatcher))
        assertIs<SavedRecording.Unavailable>(audio.yours)
    }

    @Test
    fun aDeletedReferenceIsRegeneratedButAMissingRecordingIsNot() = runTest {
        val cache = ReferenceAudioCache(storage)
        val cached = cache.reference("twinkle", phrase, 120.0)
        storage.delete(cached.path)
        val audio = AttemptAudio.load(attempt(null, cached.path), phrase, 120.0, storage, cache, AudioAnalyzer(dispatcher))
        assertIs<SavedRecording.Available>(audio.reference)
        assertIs<SavedRecording.Unavailable>(audio.yours)
    }

    @Test
    fun whenTheSongChangedTheSavedReferenceFileIsUsed() = runTest {
        storage.write("reference/old.wav", wav)
        val audio = AttemptAudio.load(attempt(null, "reference/old.wav"), phrase = null, tempoBpm = null, storage, ReferenceAudioCache(storage), AudioAnalyzer(dispatcher))
        assertIs<SavedRecording.Available>(audio.reference)
    }

    @Test
    fun historyStillShowsTheSavedResultsWhenTheRecordingIsGone() = runTest {
        val catalog = SongCatalog { path ->
            when (path) {
                "files/songs/index.json" -> """{ "songs": ["twinkle"] }"""
                "files/songs/twinkle.json" -> """{ "id": "twinkle", "title": "Twinkle", "tempoBpm": 120, "timeSignature": "4/4",
                    "key": "C major", "source": "traditional", "license": "Public domain", "sourceVerified": false,
                    "phrases": ["C4/1 C4/1 G4/1 G4/1"] }"""
                else -> throw IllegalStateException("missing resource $path")
            }.encodeToByteArray()
        }
        val history = FakeAttemptHistory().apply { add(attempt("recordings/deleted.wav")) }
        val viewModel = HistoryDetailViewModel(
            1, catalog, history, TimeZone.UTC,
            storage = storage, references = ReferenceAudioCache(storage), analysisDispatcher = dispatcher,
        )
        testScheduler.advanceUntilIdle()
        val detail = assertIs<HistoryDetailUiState.Loaded>(viewModel.uiState.value).detail
        assertEquals(80, detail.score)
        assertEquals("You scored 80/100.", detail.summary)
        assertEquals(SavedRecording.Unavailable("Recording unavailable"), viewModel.audio.value!!.yours)
        assertIs<SavedRecording.Available>(viewModel.audio.value!!.reference)
    }
}

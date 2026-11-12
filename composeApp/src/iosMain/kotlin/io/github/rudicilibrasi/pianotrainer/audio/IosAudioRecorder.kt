package io.github.rudicilibrasi.pianotrainer.audio

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * iOS recording with AVAudioEngine (#33). The session is play-and-record with output to the
 * speaker, in measurement mode, which turns off the voice processing that would distort pitch and
 * loudness. Needs NSMicrophoneUsageDescription in Info.plist.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioRecorder : AudioRecorder {
    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _elapsed = MutableStateFlow(0.0)
    override val elapsedSeconds: StateFlow<Double> = _elapsed.asStateFlow()

    private var engine: AVAudioEngine? = null
    private val buffer = SampleBuffer()
    private var sampleRate = 48000

    init {
        // An interruption (a call, Siri) ends the take; the lesson tells the student to try again.
        AudioSessionEvents.observe {
            if (_state.value == RecorderState.Recording) {
                finish()
                _state.value = RecorderState.Error
            }
        }
    }

    private suspend fun requestPermission(): Boolean {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionGranted) return true
        return suspendCancellableCoroutine { continuation ->
            session.requestRecordPermission { granted -> continuation.resume(granted) }
        }
    }

    override suspend fun start(): Boolean {
        cancel()
        if (!requestPermission()) {
            _state.value = RecorderState.PermissionDenied
            return false
        }
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeMeasurement, options = AVAudioSessionCategoryOptionDefaultToSpeaker, error = null)
        session.setActive(true, error = null)

        val created = AVAudioEngine()
        val input = created.inputNode
        val format = input.outputFormatForBus(0u)
        sampleRate = format.sampleRate.toInt()
        buffer.clear()
        _elapsed.value = 0.0
        input.installTapOnBus(0u, bufferSize = 4096u, format = format) { pcm, _ ->
            val frames = pcm?.frameLength?.toInt() ?: 0
            val channel = pcm?.floatChannelData?.get(0) ?: return@installTapOnBus
            val samples = FloatArray(frames) { channel[it] }
            var sum = 0.0
            for (s in samples) sum += s * s
            _level.value = if (frames > 0) sqrt(sum / frames).toFloat().coerceIn(0f, 1f) else 0f
            val total = synchronizedAdd(samples)
            _elapsed.value = total.toDouble() / sampleRate
            if (_elapsed.value >= AudioRecorder.MAX_SECONDS) created.pause()
        }
        created.prepare()
        if (!created.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            _state.value = RecorderState.Error
            return false
        }
        engine = created
        _state.value = RecorderState.Recording
        return true
    }

    private fun synchronizedAdd(samples: FloatArray): Int {
        buffer.add(samples)
        return buffer.size
    }

    override fun stop(): AudioClip? {
        val samples = finish() ?: return null
        return samples.takeIf { it.isNotEmpty() }?.let { AudioClip(it, sampleRate) }
    }

    override fun cancel() {
        finish()
    }

    private fun finish(): FloatArray? {
        val current = engine ?: return null
        current.inputNode.removeTapOnBus(0u)
        current.stop()
        engine = null
        _level.value = 0f
        if (_state.value == RecorderState.Recording) _state.value = RecorderState.Idle
        return buffer.toArray().also { buffer.clear() }
    }
}

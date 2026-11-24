package io.github.rudicilibrasi.pianotrainer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.rudicilibrasi.pianotrainer.audio.AudioRecorder
import io.github.rudicilibrasi.pianotrainer.audio.RecorderState
import io.github.rudicilibrasi.pianotrainer.audio.SampleBuffer
import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Asks the student for the microphone; the Activity provides it (it needs an Activity result). */
fun interface MicrophonePermission {
    suspend fun request(): Boolean
}

/**
 * Android recording with AudioRecord (#31): 16-bit PCM mono at 44.1 kHz (48 kHz if the device
 * refuses 44.1). Uses the UNPROCESSED source when the device supports it, otherwise
 * VOICE_RECOGNITION; both avoid the automatic gain and noise suppression that distort pitch and
 * loudness.
 */
class AndroidAudioRecorder(context: Context) : AudioRecorder {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** Set by the Activity while it is on screen. */
    @Volatile
    var permission: MicrophonePermission? = null

    private val _state = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()
    private val _elapsed = MutableStateFlow(0.0)
    override val elapsedSeconds: StateFlow<Double> = _elapsed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var reader: Job? = null
    private val buffer = SampleBuffer()
    private var sampleRate = 44100

    private fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun start(): Boolean {
        cancel()
        if (!hasPermission() && permission?.request() != true) {
            _state.value = RecorderState.PermissionDenied
            return false
        }
        val created = open() ?: run {
            _state.value = RecorderState.Error
            return false
        }
        record = created
        buffer.clear()
        _elapsed.value = 0.0
        created.startRecording()
        _state.value = RecorderState.Recording
        reader = scope.launch {
            val shorts = ShortArray(sampleRate / 20) // 50 ms
            while (isActive) {
                val read = created.read(shorts, 0, shorts.size)
                if (read < 0) {
                    _state.value = RecorderState.Error
                    break
                }
                val floats = FloatArray(read) { shorts[it] / 32768f }
                synchronized(buffer) { buffer.add(floats) }
                var sum = 0.0
                for (f in floats) sum += f * f
                _level.value = if (read > 0) sqrt(sum / read).toFloat().coerceIn(0f, 1f) else 0f
                _elapsed.value = buffer.size.toDouble() / sampleRate
                if (_elapsed.value >= AudioRecorder.MAX_SECONDS) break
            }
            runCatching { created.stop() }
        }
        return true
    }

    @SuppressLint("MissingPermission") // Checked in start().
    private fun open(): AudioRecord? {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        for (rate in listOf(44100, 48000)) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val candidate = runCatching {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 5 * 2))
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                sampleRate = rate
                return candidate
            }
            candidate.release()
        }
        return null
    }

    override fun stop(): AudioClip? {
        val samples = finish()
        return samples?.takeIf { it.isNotEmpty() }?.let { AudioClip(it, sampleRate) }
    }

    override fun cancel() {
        finish()
    }

    private fun finish(): FloatArray? {
        val current = record ?: return null
        reader?.cancel()
        reader = null
        runCatching { current.stop() }
        current.release()
        record = null
        _level.value = 0f
        if (_state.value == RecorderState.Recording) _state.value = RecorderState.Idle
        return synchronized(buffer) { buffer.toArray().also { buffer.clear() } }
    }
}

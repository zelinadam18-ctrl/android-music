package io.github.rudicilibrasi.pianotrainer.audio

import io.github.rudicilibrasi.pianotrainer.core.audio.AudioClip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderState {
    Idle,
    Recording,

    /** The student said no to the microphone; show how to allow it in Settings. */
    PermissionDenied,

    /** The microphone couldn't be opened or failed while recording. */
    Error,
}

/**
 * Records the student's playing from the microphone (spec §10, #31). The student decides
 * when to stop; recording also stops by itself at [MAX_SECONDS] as a safety limit.
 *
 * The recording comes back in memory as mono samples; saving it as a WAV file under
 * `recordings/` is the app's file storage (#20).
 */
interface AudioRecorder {
    val state: StateFlow<RecorderState>

    /** Input level from 0 (silence) to 1 (full scale), updated while recording, for a level meter. */
    val level: StateFlow<Float>

    /** Seconds recorded so far. */
    val elapsedSeconds: StateFlow<Double>

    /** Asks for the microphone if needed, then starts. Returns false if permission was refused or it failed. */
    suspend fun start(): Boolean

    /** Stops and returns the recording, or null if nothing was recorded. */
    fun stop(): AudioClip?

    /** Throws away a recording in progress, e.g. when leaving the screen. */
    fun cancel()

    companion object {
        const val MAX_SECONDS = 120.0
    }
}

/** A recorder with no microphone, for previews and tests. */
class SilentAudioRecorder : AudioRecorder {
    override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState.Idle).asStateFlow()
    override val level: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
    override val elapsedSeconds: StateFlow<Double> = MutableStateFlow(0.0).asStateFlow()
    override suspend fun start(): Boolean = false
    override fun stop(): AudioClip? = null
    override fun cancel() {}
}

/** Collects recorded samples in chunks without copying the whole recording on every read. */
class SampleBuffer {
    private val chunks = ArrayList<FloatArray>()
    var size: Int = 0
        private set

    fun add(samples: FloatArray, count: Int = samples.size) {
        if (count <= 0) return
        chunks += if (count == samples.size) samples else samples.copyOf(count)
        size += count
    }

    fun toArray(): FloatArray {
        val out = FloatArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    fun clear() {
        chunks.clear()
        size = 0
    }
}

package io.github.rudicilibrasi.pianotrainer.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayerState { Idle, Playing, Error }

/**
 * Plays reference audio and recordings (spec §8, #22).
 *
 * Only one sound plays at a time: starting a new one stops the old one. [positionMs] updates at
 * least every 50 ms while playing, for the note highlight (#30), and is null when stopped.
 */
interface AudioPlayer {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long?>

    /** Plays a WAV held in memory, e.g. a freshly rendered reference. */
    fun play(wav: ByteArray)

    /** Plays a WAV file. A missing or unreadable file sets [state] to [PlayerState.Error]. */
    fun playFile(path: String)

    /** Stops playback, e.g. when leaving the screen or when recording starts. */
    fun stop()
}

/** A player that plays nothing, for previews and tests. */
class SilentAudioPlayer : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow<Long?>(null)
    override val positionMs: StateFlow<Long?> = _position.asStateFlow()

    override fun play(wav: ByteArray) {}
    override fun playFile(path: String) {}
    override fun stop() {}
}

package io.github.rudicilibrasi.pianotrainer.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.NSFileManager

/**
 * iOS playback with AVAudioPlayer (#23). The audio session uses the playback category, so the
 * reference is heard even with the silent switch on.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioPlayer : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow<Long?>(null)
    override val positionMs: StateFlow<Long?> = _position.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: AVAudioPlayer? = null
    private var ticker: Job? = null

    init {
        // A call, Siri or unplugged headphones stops playback.
        AudioSessionEvents.observe { stop() }
    }

    override fun play(wav: ByteArray) {
        val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
        start(data)
    }

    override fun playFile(path: String) {
        val data = NSFileManager.defaultManager.contentsAtPath(path)
        if (data == null) {
            stop()
            _state.value = PlayerState.Error
            return
        }
        start(data)
    }

    private fun start(data: NSData) {
        stop()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
        val created = AVAudioPlayer(data = data, error = null)
        if (!created.prepareToPlay() || !created.play()) {
            _state.value = PlayerState.Error
            return
        }
        player = created
        _state.value = PlayerState.Playing
        ticker = scope.launch {
            while (isActive) {
                val current = player ?: break
                if (!current.playing) {
                    finish()
                    break
                }
                _position.value = (current.currentTime * 1000).toLong()
                delay(TICK_MS)
            }
        }
    }

    private fun finish() {
        player = null
        _position.value = null
        _state.value = PlayerState.Idle
    }

    override fun stop() {
        ticker?.cancel()
        ticker = null
        player?.stop()
        finish()
    }

    private companion object {
        const val TICK_MS = 20L
    }
}

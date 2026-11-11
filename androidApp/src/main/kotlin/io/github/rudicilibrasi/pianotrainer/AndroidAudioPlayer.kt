package io.github.rudicilibrasi.pianotrainer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.rudicilibrasi.pianotrainer.audio.AudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.PlayerState
import io.github.rudicilibrasi.pianotrainer.core.audio.Wav
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
import java.io.File

/**
 * Android playback with a static-mode AudioTrack (#22): the whole WAV is handed to the track, so
 * the playback head gives an exact position for the note highlight. Takes transient audio focus
 * and stops if it is lost (a call, another app playing).
 */
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()

    private val _state = MutableStateFlow(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _position = MutableStateFlow<Long?>(null)
    override val positionMs: StateFlow<Long?> = _position.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var track: AudioTrack? = null
    private var ticker: Job? = null

    override fun play(wav: ByteArray) {
        stop()
        val clip = try {
            Wav.decode(wav)
        } catch (e: IllegalArgumentException) {
            _state.value = PlayerState.Error
            return
        }
        val pcm = ShortArray(clip.samples.size) { i -> (clip.samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        if (pcm.isEmpty()) return
        val created = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(clip.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        } catch (e: Exception) {
            _state.value = PlayerState.Error
            return
        }
        created.write(pcm, 0, pcm.size)
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            created.release()
            _state.value = PlayerState.Error
            return
        }
        track = created
        created.play()
        _state.value = PlayerState.Playing
        val frames = pcm.size
        val sampleRate = clip.sampleRate
        ticker = scope.launch {
            while (isActive) {
                val current = track ?: break
                val head = current.playbackHeadPosition
                if (head >= frames || current.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                    break
                }
                _position.value = head * 1000L / sampleRate
                delay(TICK_MS)
            }
        }
    }

    override fun playFile(path: String) {
        val file = File(path)
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            stop()
            _state.value = PlayerState.Error
            return
        }
        play(bytes)
    }

    @Synchronized
    override fun stop() {
        ticker?.cancel()
        ticker = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        _position.value = null
        if (_state.value == PlayerState.Playing) _state.value = PlayerState.Idle
    }

    private companion object {
        const val TICK_MS = 20L
    }
}

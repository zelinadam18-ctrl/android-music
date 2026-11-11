package io.github.rudicilibrasi.pianotrainer

import androidx.compose.ui.window.ComposeUIViewController
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioRecorder
import io.github.rudicilibrasi.pianotrainer.database.databaseBuilder

/** One container for the whole app process. */
private val container by lazy { AppContainer(databaseBuilder(), IosAudioPlayer(), IosAudioRecorder()) }

fun MainViewController() = ComposeUIViewController { App(container) }

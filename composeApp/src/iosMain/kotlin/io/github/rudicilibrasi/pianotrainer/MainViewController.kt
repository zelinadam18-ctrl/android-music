package io.github.rudicilibrasi.pianotrainer

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioRecorder
import io.github.rudicilibrasi.pianotrainer.database.databaseBuilder

/** One container for the whole app process. */
private val container by lazy { AppContainer(databaseBuilder(), IosAudioPlayer(), IosAudioRecorder(), openAppSettings = ::openAppSettings) }

fun MainViewController() = ComposeUIViewController { App(container) }

/** This app's page in Settings, where the student can allow the microphone. */
private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

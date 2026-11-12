package io.github.rudicilibrasi.pianotrainer

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioPlayer
import io.github.rudicilibrasi.pianotrainer.audio.IosAudioRecorder
import io.github.rudicilibrasi.pianotrainer.database.databaseBuilder

/** One container for the whole app process. */
private val container by lazy { AppContainer(databaseBuilder(), IosAudioPlayer(), IosAudioRecorder(), openAppSettings = ::openAppSettings, storageRoot = applicationSupportPath()) }

fun MainViewController() = ComposeUIViewController { App(container) }

/** This app's page in Settings, where the student can allow the microphone. */
private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}

/** The app's Application Support folder, which iOS keeps across updates and doesn't show to the user. */
@OptIn(ExperimentalForeignApi::class)
private fun applicationSupportPath(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    return requireNotNull(url?.path) { "Application Support directory is unavailable" }
}

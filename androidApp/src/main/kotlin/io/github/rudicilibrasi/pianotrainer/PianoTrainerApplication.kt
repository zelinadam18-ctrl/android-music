package io.github.rudicilibrasi.pianotrainer

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.rudicilibrasi.pianotrainer.database.databaseBuilder

/** Holds the single [AppContainer] for the app process, so it survives activity recreation. */
class PianoTrainerApplication : Application() {
    val recorder: AndroidAudioRecorder by lazy { AndroidAudioRecorder(this) }

    val container: AppContainer by lazy { AppContainer(databaseBuilder(this), AndroidAudioPlayer(this), recorder, openAppSettings = ::openAppSettings, storageRoot = filesDir.absolutePath) }

    /** This app's page in Settings, where the student can allow the microphone. */
    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

package io.github.rudicilibrasi.pianotrainer

import android.app.Application
import io.github.rudicilibrasi.pianotrainer.database.databaseBuilder

/** Holds the single [AppContainer] for the app process, so it survives activity recreation. */
class PianoTrainerApplication : Application() {
    val recorder: AndroidAudioRecorder by lazy { AndroidAudioRecorder(this) }

    val container: AppContainer by lazy { AppContainer(databaseBuilder(this), AndroidAudioPlayer(this), recorder) }
}

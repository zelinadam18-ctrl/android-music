package io.github.rudicilibrasi.pianotrainer

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val application = application as PianoTrainerApplication
        // The recorder outlives the activity; while it's on screen, it can ask for the microphone.
        application.recorder.permission = MicrophonePermission {
            CompletableDeferred<Boolean>().also { deferred ->
                pendingPermission = deferred
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }.await()
        }

        setContent {
            App(application.container)
        }
    }

    override fun onDestroy() {
        (application as PianoTrainerApplication).recorder.permission = null
        pendingPermission?.complete(false)
        super.onDestroy()
    }
}

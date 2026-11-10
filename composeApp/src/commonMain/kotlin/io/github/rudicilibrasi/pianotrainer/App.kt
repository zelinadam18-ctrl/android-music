package io.github.rudicilibrasi.pianotrainer

import androidx.compose.runtime.Composable
import io.github.rudicilibrasi.pianotrainer.navigation.AppNavigation
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme

@Composable
fun App(container: AppContainer) {
    PianoTrainerTheme {
        AppNavigation(container)
    }
}

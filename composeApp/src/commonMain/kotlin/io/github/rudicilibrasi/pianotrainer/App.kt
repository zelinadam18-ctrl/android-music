package io.github.rudicilibrasi.pianotrainer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.rudicilibrasi.pianotrainer.navigation.AppNavigation
import io.github.rudicilibrasi.pianotrainer.ui.theme.PianoTrainerTheme

@Composable
fun App() {
    val container = remember { AppContainer() }
    PianoTrainerTheme {
        AppNavigation(container)
    }
}

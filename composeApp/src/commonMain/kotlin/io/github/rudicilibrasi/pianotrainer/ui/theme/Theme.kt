package io.github.rudicilibrasi.pianotrainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    tertiary = Color(0xFFB8860B),
    onTertiary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C3FF),
    onPrimary = Color(0xFF002388),
    primaryContainer = Color(0xFF2A3A9B),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFFFFD54F),
    onTertiary = Color(0xFF3E2E00),
)

/**
 * The app's Material 3 theme. Follows the system light/dark setting; text sizes use `sp`, so they
 * respect the system font size.
 */
@Composable
fun PianoTrainerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

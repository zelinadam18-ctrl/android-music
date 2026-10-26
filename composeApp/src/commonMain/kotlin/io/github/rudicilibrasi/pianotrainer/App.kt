package io.github.rudicilibrasi.pianotrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.rudicilibrasi.pianotrainer.core.AppInfo

@Composable
@Preview
fun App() {
    MaterialTheme {
        val platformName = remember { getPlatform().name }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
            Text(greeting(platformName), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun greeting(platformName: String): String = "Hello from $platformName!"

package io.github.rudicilibrasi.pianotrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PlaceholderLink(val label: String, val onClick: () -> Unit)

/**
 * Temporary content for a screen that hasn't been built yet. It says so plainly and names the
 * issue that will build it, so nobody mistakes it for the finished screen.
 */
@Composable
fun PlaceholderScreen(
    heading: String,
    issueNumber: Int,
    details: List<String> = emptyList(),
    links: List<PlaceholderLink> = emptyList(),
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Placeholder: this screen is built in issue #$issueNumber.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        details.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        links.forEach { link ->
            OutlinedButton(onClick = link.onClick) { Text(link.label) }
        }
    }
}

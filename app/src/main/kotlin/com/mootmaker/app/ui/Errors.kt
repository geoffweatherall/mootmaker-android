package com.mootmaker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The one way to show an error that is not about a single field and not inside a dialog (the
 * "Errors" rule in the README). Place it ABOVE a screen's scrolling content, never inside the
 * scroll, so it cannot scroll out of view. Every message is shown in full, one per line; the banner
 * grows to a third of the screen's height and then scrolls within itself. Renders nothing when
 * [messages] is empty. [onRetry], when given, adds "Try again" (for a failed refresh).
 */
@Composable
fun ErrorBanner(
    messages: List<String>,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp / 3
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier.weight(1f).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                messages.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

/** A screen with nothing to show yet because its first load failed: the message and Try again. */
@Composable
fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

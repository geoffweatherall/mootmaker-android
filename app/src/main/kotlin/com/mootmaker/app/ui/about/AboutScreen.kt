package com.mootmaker.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mootmaker.data.config.Environment

/** Taps on the version that reveal the developer settings, like Android's own "Build number". */
const val DEVELOPER_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    environment: Environment,
    onSwitchEnvironment: (Environment) -> Unit,
    onBack: () -> Unit,
) {
    var taps by rememberSaveable { mutableIntStateOf(0) }
    val developer = taps >= DEVELOPER_TAPS || !environment.isProduction
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mootmaker for Android", style = MaterialTheme.typography.titleLarge)
            Text("Book rooms and meetings, with the same account and data as the website.")
            Text(
                "Version $versionName ($versionCode)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { taps++ }.padding(vertical = 8.dp),
            )
            if (developer) {
                HorizontalDivider()
                EnvironmentSwitcher(environment, onSwitchEnvironment)
            }
        }
    }
}

@Composable
private fun EnvironmentSwitcher(current: Environment, onSwitch: (Environment) -> Unit) {
    var input by remember { mutableStateOf(if (current.isProduction) "" else current.name) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val environment = Environment.fromInput(input)
        if (environment == null) {
            error = "Use lowercase letters, digits and hyphens only."
        } else {
            error = null
            onSwitch(environment)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer settings", style = MaterialTheme.typography.titleMedium)
        Text("Environment: ${current.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Switching signs you out. Leave the name blank for production.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text("Environment name") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { submit() }) { Text("Switch environment") }
    }
}

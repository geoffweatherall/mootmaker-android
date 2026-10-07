package com.mootmaker.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.Avatar
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.settings.DATE_FORMAT_EXAMPLES
import com.mootmaker.data.settings.TIME_FORMAT_EXAMPLES

data class SettingsActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onName: (String) -> Unit,
    val onSaveName: () -> Unit,
    val onDateFormat: (DateFormat) -> Unit,
    val onTimeFormat: (TimeFormat) -> Unit,
    val onSaveFormats: () -> Unit,
    val onChoosePhoto: () -> Unit,
    val onRemovePhoto: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsState, actions: SettingsActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loadError != null && !state.loaded -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.loadError, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = actions.onRetry) { Text("Try again") }
                }
                !state.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Sections(state, actions)
            }
        }
    }
}

@Composable
private fun Sections(state: SettingsState, actions: SettingsActions) {
    val hasPerson = state.profile != null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PhotoSection(state, hasPerson, actions)
        HorizontalDivider()
        NameSection(state, hasPerson, actions)
        HorizontalDivider()
        FormatSection(state, hasPerson, actions)
    }
}

@Composable
private fun PhotoSection(state: SettingsState, hasPerson: Boolean, actions: SettingsActions) {
    SectionTitle("Photo")
    val profile = state.profile
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(profile?.name ?: "?", profile?.avatarUrl, size = 72.dp, textStyle = MaterialTheme.typography.headlineSmall)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onChoosePhoto, enabled = hasPerson && !state.avatarStatus.saving) { Text("Choose photo") }
            if (profile?.avatarUrl != null) {
                OutlinedButton(onClick = actions.onRemovePhoto, enabled = !state.avatarStatus.saving) { Text("Remove photo") }
            }
        }
    }
    if (!hasPerson) NoPersonNote("Your account has no linked person yet, so a photo can't be set here.")
    Outcome(state.avatarStatus)
}

@Composable
private fun NameSection(state: SettingsState, hasPerson: Boolean, actions: SettingsActions) {
    SectionTitle("Your name")
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text("Name") },
        singleLine = true,
        enabled = hasPerson,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
    if (!hasPerson) NoPersonNote("Your account has no linked person yet, so your name can't be changed here.")
    Button(onClick = actions.onSaveName, enabled = hasPerson && !state.nameStatus.saving) { Text("Save name") }
    Outcome(state.nameStatus)
}

@Composable
private fun FormatSection(state: SettingsState, hasPerson: Boolean, actions: SettingsActions) {
    SectionTitle("Date and time format")
    Text(
        "How dates and times are shown to you and how you type them in. This only changes what you see, not anyone else's view.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChoiceField("Date format", DATE_FORMAT_EXAMPLES, state.dateFormat, hasPerson, actions.onDateFormat)
    ChoiceField("Time format", TIME_FORMAT_EXAMPLES, state.timeFormat, hasPerson, actions.onTimeFormat)
    if (!hasPerson) NoPersonNote("Your account has no linked person yet, so these can't be changed here.")
    Button(onClick = actions.onSaveFormats, enabled = hasPerson && !state.formatStatus.saving) { Text("Save formats") }
    Outcome(state.formatStatus)
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun NoPersonNote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Outcome(status: SectionStatus) {
    status.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    status.success?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
}

/** A read-only field that drops a menu of [options] (value to the example shown for it) under itself. */
@Composable
private fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { open = true }) {
            OutlinedTextField(
                value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                onValueChange = {},
                enabled = false,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outline,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, example) ->
                DropdownMenuItem(text = { Text(example) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

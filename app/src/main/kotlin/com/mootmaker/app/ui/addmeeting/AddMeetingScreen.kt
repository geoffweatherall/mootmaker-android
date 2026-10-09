package com.mootmaker.app.ui.addmeeting

import com.mootmaker.app.ui.ErrorBanner
import com.mootmaker.app.ui.FirstLoad
import com.mootmaker.app.ui.LoadFailed
import com.mootmaker.app.ui.MootmakerIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.formatDate
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.attendeeOptions
import com.mootmaker.data.meeting.organiserOptions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

data class AddMeetingActions(
    val onBack: () -> Unit,
    val onSubject: (String) -> Unit,
    val onOrganiser: (String) -> Unit,
    val onAttendees: (List<String>) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onStart: (LocalTime) -> Unit,
    val onEnd: (LocalTime) -> Unit,
    val onRoom: (String) -> Unit,
    val onSuggestRoom: () -> Unit,
    val onSave: () -> Unit,
    val onDismissErrors: () -> Unit,
    val onRetry: () -> Unit,
    /** The meeting exists; show it. */
    val onSaved: (meetingId: String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMeetingScreen(state: AddMeetingState, actions: AddMeetingActions) {
    LaunchedEffect(state.savedMeetingId) { state.savedMeetingId?.let(actions.onSaved) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editing) "Edit meeting" else "Add meeting") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ErrorBanner(state.errors, actions.onDismissErrors)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val reference = state.reference
                when {
                    reference != null -> Form(state, reference, actions)
                    state.loadError != null -> LoadFailed(state.loadError, actions.onRetry)
                    else -> FirstLoad()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Form(state: AddMeetingState, reference: MeetingFormReference, actions: AddMeetingActions) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingAttendees by remember { mutableStateOf(false) }
    val people = reference.people
    val organiser = people.firstOrNull { it.id == state.organiserId }
    val attendees = people.filter { it.id in state.attendeeIds }
    val room = reference.rooms.firstOrNull { it.id == state.roomId }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.subject,
            onValueChange = actions.onSubject,
            label = { Text("Subject") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        MenuField("Organiser", organiser?.name.orEmpty(), organiserOptions(people, state.attendeeIds).map { it.name to it.id }, actions.onOrganiser)

        PickerField("Attendees", if (attendees.isEmpty()) "" else attendees.joinToString(", ") { it.name }) { pickingAttendees = true }

        PickerField("Date", formatDate("${state.date}T00:00:00", reference.dateFormat)) { pickingDate = true }

        TimeField("Start time", state.start, reference.timeFormat, actions.onStart)
        TimeField("End time", state.end, reference.timeFormat, actions.onEnd)

        MenuField("Room", room?.label.orEmpty(), reference.rooms.map { it.label to it.id }, actions.onRoom)
        OutlinedButton(onClick = actions.onSuggestRoom, enabled = !state.suggesting, modifier = Modifier.fillMaxWidth()) {
            // The sparkle is decorative (the text names the button); the spinner takes its place while loading.
            if (state.suggesting) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(MootmakerIcons.Sparkle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Suggest a room")
        }

        // Stays enabled-looking only when it can act: a second tap during the request does nothing.
        Button(onClick = actions.onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text("Save")
        }
    }

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.date.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    pickerState.selectedDateMillis?.let { actions.onDate(it.toUtcDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(pickerState) }
    }
    if (pickingAttendees) {
        AttendeePicker(
            options = attendeeOptions(people, state.organiserId).map { it.name to it.id },
            selected = state.attendeeIds,
            onChange = actions.onAttendees,
            onDone = { pickingAttendees = false },
        )
    }
}

/**
 * A read-only field that opens something when tapped. The tap target wraps a disabled text field, so
 * the label and value read as one control to touch and to accessibility.
 */
@Composable
private fun PickerField(label: String, value: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        OutlinedTextField(
            value = value,
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
}

/** A [PickerField] that drops a menu of [options] (label to id) under itself. */
@Composable
private fun MenuField(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PickerField(label, value) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.height(320.dp)) {
            options.forEach { (text, id) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

/**
 * Opens the "Select time" dialog ([TimeDialog]). Only quarter hours come back from it, so no other
 * minute can be booked (use case F.41).
 */
@Composable
private fun TimeField(label: String, time: LocalTime, timeFormat: TimeFormat, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, shown(time, timeFormat)) { open = true }
    if (open) {
        TimeDialog(
            initial = time,
            timeFormat = timeFormat,
            onPick = { open = false; onPick(it) },
            onDismiss = { open = false },
        )
    }
}

private fun shown(time: LocalTime, timeFormat: TimeFormat) = formatTime("2000-01-01T${"%02d:%02d:00".format(time.hour, time.minute)}", timeFormat)

@Composable
private fun AttendeePicker(options: List<Pair<String, String>>, selected: List<String>, onChange: (List<String>) -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Attendees") },
        text = {
            if (options.isEmpty()) {
                Text("There is nobody else to invite.")
            } else {
                LazyColumn {
                    items(options, key = { it.second }) { (name, id) ->
                        val checked = id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                                onChange(if (checked) selected - id else selected + id)
                            },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(name, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

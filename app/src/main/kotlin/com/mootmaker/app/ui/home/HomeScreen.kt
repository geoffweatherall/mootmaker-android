package com.mootmaker.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.AgendaRow
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.formatTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the home screen can do. Add meeting opens the native form (M4). */
data class HomeActions(
    val onCalendar: () -> Unit,
    val onRoomAvailabilityToday: () -> Unit,
    val onAddMeeting: () -> Unit,
    val onRetry: () -> Unit,
    val onAbout: () -> Unit,
    val onSignOut: () -> Unit,
    val onOpenMeeting: (meetingId: String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, fallbackName: String?, actions: HomeActions) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("About") }, onClick = { menuOpen = false; actions.onAbout() })
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; actions.onSignOut() })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.data != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                state.data == null && state.error != null -> LoadFailed(state.error, actions.onRetry)
                state.data == null -> Box(Modifier.fillMaxSize()) // First load: the bar or nothing, briefly.
                state.data.agenda == null -> NoLinkedPerson(state, actions)
                else -> Agenda(state, fallbackName, actions)
            }
        }
    }
}

@Composable
private fun Agenda(state: HomeState, fallbackName: String?, actions: HomeActions) {
    val data = state.data!!
    val agenda = data.agenda!!
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Text(
                    data.name ?: fallbackName ?: "Welcome",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntryPoint("Calendar", Icons.Filled.DateRange, actions.onCalendar)
                EntryPoint("Rooms today", Icons.Filled.Home, actions.onRoomAvailabilityToday)
                EntryPoint("Add meeting", Icons.Filled.Add, actions.onAddMeeting)
            }
        }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item {
            Card(
                colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (agenda.isEmpty) {
                    Text(
                        "No meetings today or tomorrow.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    AgendaDaySection("Today", agenda.today, data.timeFormat, actions.onOpenMeeting)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AgendaDaySection("Tomorrow", agenda.tomorrow, data.timeFormat, actions.onOpenMeeting)
                }
            }
        }
    }
}

@Composable
private fun EntryPoint(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AgendaDaySection(title: String, day: AgendaDay, timeFormat: TimeFormat, onOpenMeeting: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                day.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (day.rows.isEmpty()) {
            Text("No meetings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.rows.forEach { AgendaRowItem(it, timeFormat) { onOpenMeeting(it.meetingId) } }
    }
}

@Composable
private fun AgendaRowItem(row: AgendaRow, timeFormat: TimeFormat, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(roomColor(row.roomColorSlot, dark), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(row.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatTime(row.startTime, timeFormat)}–${formatTime(row.endTime, timeFormat)} · ${row.roomName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoLinkedPerson(state: HomeState, actions: HomeActions) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Welcome to Mootmaker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Your account hasn't been set up properly — no profile could be found for your sign-in.",
            color = MaterialTheme.colorScheme.error,
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EntryPoint("Add meeting", Icons.Filled.Add, actions.onAddMeeting)
            EntryPoint("Rooms today", Icons.Filled.Home, actions.onRoomAvailabilityToday)
        }
    }
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}


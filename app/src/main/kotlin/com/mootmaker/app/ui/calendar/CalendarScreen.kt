package com.mootmaker.app.ui.calendar

import com.mootmaker.app.ui.FirstLoad
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.ErrorBanner
import com.mootmaker.app.ui.LoadFailed
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.AgendaRow
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.availability.dayRelativeLabel
import com.mootmaker.data.api.CalendarData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class CalendarActions(
    val onBack: () -> Unit,
    val onPreviousWeek: () -> Unit,
    val onNextWeek: () -> Unit,
    val onThisWeek: () -> Unit,
    val onSelectPerson: (personId: String) -> Unit,
    val onOpenMeeting: (meetingId: String) -> Unit,
    val onRetry: () -> Unit,
    val onDismissError: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(state: CalendarState, actions: CalendarActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val data = state.data
            if (data != null) ErrorBanner(listOfNotNull(state.error), actions.onDismissError, onRetry = actions.onRetry)
            when {
                data == null && state.error != null -> LoadFailed(state.error, actions.onRetry)
                data == null -> FirstLoad()
                data.people.isEmpty() -> Text(
                    "No people exist yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Week(state, data, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Week(state: CalendarState, data: CalendarData, actions: CalendarActions, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonSelector(state.personName ?: "Choose a person", data, actions.onSelectPerson)
                WeekNavigator(state, actions)
            }
        }
        items(data.week, key = { it.date.toString() }) { day ->
            DaySection(day, state.today, data.timeFormat, actions.onOpenMeeting)
        }
    }
}

@Composable
private fun PersonSelector(selectedName: String, data: CalendarData, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Person", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(selectedName)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                data.people.forEach { person ->
                    DropdownMenuItem(text = { Text(person.name) }, onClick = { open = false; onSelect(person.id) })
                }
            }
        }
    }
}

@Composable
private fun WeekNavigator(state: CalendarState, actions: CalendarActions) {
    val last = state.monday.plusDays(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onPreviousWeek, enabled = state.canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        Text(
            "${state.monday.format(SHORT_DATE)} – ${last.format(LONG_DATE)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = actions.onNextWeek, enabled = state.canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
        }
        TextButton(onClick = actions.onThisWeek, enabled = !state.isThisWeek) { Text("This week") }
    }
}

@Composable
private fun DaySection(day: AgendaDay, today: LocalDate, timeFormat: TimeFormat, onOpenMeeting: (String) -> Unit) {
    // "Today"/"Tomorrow" for the two near days, the weekday name beyond that.
    val title = when (dayRelativeLabel(day.date, today)) {
        "today" -> "Today"
        "tomorrow" -> "Tomorrow"
        else -> day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(day.date.format(SHORT_DATE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        if (day.rows.isEmpty()) {
            Text("No meetings", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.rows.forEach { MeetingRow(it, timeFormat) { onOpenMeeting(it.meetingId) } }
    }
}

@Composable
private fun MeetingRow(row: AgendaRow, timeFormat: TimeFormat, onClick: () -> Unit) {
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

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

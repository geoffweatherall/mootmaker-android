package com.mootmaker.app.ui.availability

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.availability.Booking
import com.mootmaker.data.availability.RoomCard
import com.mootmaker.data.availability.dayRelativeLabel
import com.mootmaker.data.availability.segmentsForRoom
import com.mootmaker.data.availability.statusForRoom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class AvailabilityActions(
    val onBack: () -> Unit,
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onPickDate: (LocalDate) -> Unit,
    val onToggleRoom: (String) -> Unit,
    val onAddMeeting: () -> Unit,
    val onOpenMeeting: (meetingId: String) -> Unit,
    val onRetry: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvailabilityScreen(state: AvailabilityState, actions: AvailabilityActions) {
    var picking by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Room availability") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions.onAddMeeting) {
                Icon(Icons.Filled.Add, contentDescription = "Add meeting")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DayNavigator(state, actions, onPick = { picking = true })
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val data = state.data
            when {
                data == null && state.error != null -> LoadFailed(state.error, actions.onRetry)
                data == null -> Box(Modifier.fillMaxSize())
                data.rooms.isEmpty() -> Text(
                    "No rooms exist yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
                else -> RoomList(state, data.timeFormat, data.rooms, actions)
            }
        }
    }
    if (picking) {
        val bounds = state.data?.bounds
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.toUtcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = utcTimeMillis.toUtcDate()
                    return bounds == null || date in bounds.earliest..bounds.latest
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    pickerState.selectedDateMillis?.let { actions.onPickDate(it.toUtcDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(pickerState) }
    }
}

@Composable
private fun DayNavigator(state: AvailabilityState, actions: AvailabilityActions, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        IconButton(onClick = actions.onPreviousDay, enabled = state.canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        TextButton(onClick = onPick) {
            Text(state.date.format(DateTimeFormatter.ofPattern("EEEE d MMM yyyy", Locale.getDefault())))
        }
        IconButton(onClick = actions.onNextDay, enabled = state.canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun RoomList(state: AvailabilityState, timeFormat: TimeFormat, rooms: List<RoomCard>, actions: AvailabilityActions) {
    // "today"/"tomorrow" for the near days, the weekday name otherwise ("See Friday's meetings").
    val dayLabel = dayRelativeLabel(state.date, state.today) ?: state.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rooms, key = { it.id }) { room ->
            RoomCardItem(room, state, timeFormat, dayLabel, expanded = room.id in state.expanded, onOpenMeeting = actions.onOpenMeeting) { actions.onToggleRoom(room.id) }
        }
    }
}

@Composable
private fun RoomCardItem(room: RoomCard, state: AvailabilityState, timeFormat: TimeFormat, dayLabel: String, expanded: Boolean, onOpenMeeting: (String) -> Unit, onToggle: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = roomColor(room.colorSlot, dark)
    val status = statusForRoom(room.bookings, state.isToday, state.nowMinutes, timeFormat)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Capacity ${room.capacity}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (status.free) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        status.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Text(
                    status.subLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Timeline(room.bookings, color)
            val toggleLabel = if (expanded) "Hide $dayLabel's meetings" else "See $dayLabel's meetings (${room.bookings.size})"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
            ) {
                Text(toggleLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (room.bookings.isEmpty()) {
                        Text("No meetings booked for $dayLabel.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    room.bookings.forEach { BookingRow(it, timeFormat) { onOpenMeeting(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun BookingRow(booking: Booking, timeFormat: TimeFormat, onClick: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(
            "${formatTime(booking.startTime, timeFormat)}–${formatTime(booking.endTime, timeFormat)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(booking.subject, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The busy/free bar across the 08:00-18:00 window. A visual summary of what the status and the
 * meeting list already say as text, so it is hidden from accessibility services.
 */
@Composable
private fun Timeline(bookings: List<Booking>, color: androidx.compose.ui.graphics.Color) {
    val segments = segmentsForRoom(bookings).filterNotNull()
    Layout(
        content = {
            segments.forEach { Box(Modifier.background(color)) }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .clearAndSetSemantics { },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val segment = segments[index]
            val segmentWidth = (segment.width / 100f * width).toInt().coerceAtLeast(1)
            measurable.measure(androidx.compose.ui.unit.Constraints.fixed(segmentWidth, height)) to (segment.left / 100f * width).toInt()
        }
        layout(width, height) { placeables.forEach { (placeable, x) -> placeable.placeRelative(x, 0) } }
    }
}

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

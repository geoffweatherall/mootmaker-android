package com.mootmaker.app.ui.meeting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.data.agenda.formatDate
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.meeting.MeetingDetail
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.PersonRef

data class MeetingDetailsActions(
    val onBack: () -> Unit,
    val onShare: (MeetingDetail) -> Unit,
    val onOpenCalendar: (personId: String) -> Unit,
    val onRetry: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailsScreen(state: MeetingDetailsState, actions: MeetingDetailsActions) {
    val meeting = state.data?.meeting
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meeting") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (meeting != null) {
                        IconButton(onClick = { actions.onShare(meeting) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share meeting")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.data == null && state.error != null -> LoadFailed(state.error, actions.onRetry)
                state.data == null -> Box(Modifier.fillMaxSize())
                meeting == null -> Text(
                    "Meeting not found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Details(meeting, state.data, state.error, actions)
            }
        }
    }
}

@Composable
private fun Details(meeting: MeetingDetail, data: MeetingDetailsData, error: String?, actions: MeetingDetailsActions) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(meeting.subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(roomColor(meeting.roomColorSlot, dark), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(meeting.roomName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The date once, then the time as a range: not two full date-times (use case H.71).
        Text(formatDate(meeting.startTime, data.dateFormat), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${formatTime(meeting.startTime, data.timeFormat)}–${formatTime(meeting.endTime, data.timeFormat)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Caption("Organiser")
        PersonRow(meeting.organiser, data.myPersonId, status = null, onClick = { actions.onOpenCalendar(meeting.organiser.id) })
        Caption("Attendees · ${meeting.attendees.size}")
        if (meeting.attendees.isEmpty()) {
            Text("No attendees.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        meeting.attendees.forEach { attendee ->
            PersonRow(attendee.person, data.myPersonId, status = attendee.status.label, onClick = { actions.onOpenCalendar(attendee.person.id) })
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A person: initials in a circle, their name, and either "You" (for the signed-in caller) or their
 * response. The organiser has no response to show: scheduling a meeting counts as going. Tapping
 * the row opens that person's calendar.
 */
@Composable
private fun PersonRow(person: PersonRef, myPersonId: String?, status: String?, onClick: () -> Unit) {
    val isMe = person.id == myPersonId
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(initials(person.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(person.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when {
            isMe -> Text("You", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            status != null -> Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

@Composable
private fun LoadFailed(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

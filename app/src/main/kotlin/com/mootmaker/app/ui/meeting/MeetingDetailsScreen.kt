package com.mootmaker.app.ui.meeting

import com.mootmaker.app.ui.FirstLoad
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.app.ui.Avatar
import com.mootmaker.app.ui.ErrorBanner
import com.mootmaker.app.ui.LoadFailed
import com.mootmaker.data.agenda.formatDate
import com.mootmaker.data.agenda.formatTime
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetail
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.PersonRef
import com.mootmaker.data.meeting.myAttendeeRow

data class MeetingDetailsActions(
    val onBack: () -> Unit,
    val onShare: (MeetingDetail) -> Unit,
    val onOpenCalendar: (personId: String) -> Unit,
    val onRetry: () -> Unit,
    val onEdit: (meetingId: String) -> Unit = {},
    val onRespond: (AttendeeStatus) -> Unit = {},
    val onAskToCancel: () -> Unit = {},
    val onKeepMeeting: () -> Unit = {},
    val onConfirmCancel: () -> Unit = {},
    /** The meeting was deleted; leave the screen. */
    val onCancelled: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailsScreen(state: MeetingDetailsState, actions: MeetingDetailsActions, canEdit: Boolean = false) {
    val meeting = state.data?.meeting
    LaunchedEffect(state.cancelled) { if (state.cancelled) actions.onCancelled() }
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
                        // Hidden outright for anyone who may not use them; the API refuses them anyway.
                        if (canEdit) {
                            IconButton(onClick = { actions.onEdit(meeting.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit meeting")
                            }
                            IconButton(onClick = actions.onAskToCancel) {
                                Icon(Icons.Filled.Delete, contentDescription = "Cancel meeting")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.data != null) {
                // Action errors show on the cancel confirmation while it is up, and here otherwise.
                val messages = listOfNotNull(state.error) + if (state.confirmingCancel) emptyList() else state.actionErrors
                ErrorBanner(messages, actions.onDismissError, onRetry = if (state.error != null) actions.onRetry else null)
            }
            when {
                state.data == null && state.error != null -> LoadFailed(state.error, actions.onRetry)
                state.data == null -> FirstLoad()
                meeting == null -> Text(
                    "Meeting not found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Details(meeting, state.data, state, actions, Modifier.weight(1f))
            }
        }
    }
    val meetingToCancel = meeting
    if (state.confirmingCancel && meetingToCancel != null) CancelDialog(meetingToCancel.subject, state, actions)
}

/** Names the meeting about to be deleted, so it is clear which one it is (use case O.117). */
@Composable
private fun CancelDialog(subject: String, state: MeetingDetailsState, actions: MeetingDetailsActions) {
    AlertDialog(
        onDismissRequest = { if (!state.cancelling) actions.onKeepMeeting() },
        title = { Text("Cancel this meeting?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.actionErrors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("This permanently deletes \"$subject\" for every attendee. This can't be undone.")
            }
        },
        dismissButton = { TextButton(onClick = actions.onKeepMeeting, enabled = !state.cancelling) { Text("Keep meeting") } },
        confirmButton = {
            TextButton(onClick = actions.onConfirmCancel, enabled = !state.cancelling) {
                Text("Cancel meeting", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun Details(meeting: MeetingDetail, data: MeetingDetailsData, state: MeetingDetailsState, actions: MeetingDetailsActions, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        // Only an attendee has anything to answer: the organiser is implicitly going.
        myAttendeeRow(meeting, data.myPersonId)?.let { mine ->
            Caption("Your response")
            ResponseButtons(selected = mine.status, enabled = state.pendingResponse == null, onRespond = actions.onRespond, pending = state.pendingResponse)
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
        Avatar(person.name, person.avatarUrl)
        Spacer(Modifier.width(12.dp))
        Text(person.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when {
            isMe -> Text("You", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            status != null -> Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

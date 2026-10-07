package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.roomColorSlots

/** Mirrors the API's AttendeeStatus enum. */
enum class AttendeeStatus(val label: String) {
    Going("Going"),
    NotGoing("Not going"),
    Maybe("Maybe"),
    NoResponse("No response"),
}

data class PersonRef(val id: String, val name: String)

data class AttendeeRow(val person: PersonRef, val status: AttendeeStatus)

/** A meeting as its details screen shows it. Times are naive local date-time strings. */
data class MeetingDetail(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val roomName: String,
    /** Index into the room palette: the room's own colour, or its position by name. */
    val roomColorSlot: Int,
    val organiser: PersonRef,
    /** Never includes the organiser: the API leaves them out (they are implicitly going). */
    val attendees: List<AttendeeRow>,
    /** Opaque; sent back as `expectedVersion` when editing so a stale edit is refused. */
    val version: String = "",
)

/**
 * What the details screen shows. [meeting] is null when no such meeting exists, or its day has
 * aged out of retention: the API answers both the same way (use case H.73).
 */
data class MeetingDetailsData(
    val meeting: MeetingDetail?,
    /** The signed-in caller's Person, or null for an account with none. */
    val myPersonId: String?,
    val timeFormat: TimeFormat,
    val dateFormat: DateFormat,
)

// The API's shapes, reduced to what the details need, so the logic is testable without Apollo.
data class MeetingRoomInput(val id: String, val name: String)
data class MeetingInput(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val room: MeetingRoomInput,
    val organiser: PersonRef,
    val attendees: List<AttendeeRow>,
    val version: String = "",
)

/**
 * The meeting with its room's colour. The colour depends on the room's position in the whole
 * name-sorted room list, so [rooms] is every room, not just this meeting's.
 */
fun buildMeetingDetail(meeting: MeetingInput, rooms: List<RoomInput>): MeetingDetail = MeetingDetail(
    id = meeting.id,
    subject = meeting.subject,
    startTime = meeting.startTime,
    endTime = meeting.endTime,
    roomId = meeting.room.id,
    roomName = meeting.room.name,
    roomColorSlot = roomColorSlots(rooms)[meeting.room.id] ?: 0,
    organiser = meeting.organiser,
    attendees = meeting.attendees,
    version = meeting.version,
)

/** The link the Share action hands out: the webapp's page for the meeting, which opens anywhere. */
fun meetingShareUrl(siteUrl: String, meetingId: String) = "$siteUrl/meetings/$meetingId"

/** The organiser or an admin may edit and cancel a meeting (the API enforces it; this only decides what to show). */
fun canEditMeeting(meeting: MeetingDetail, myPersonId: String?, isAdmin: Boolean): Boolean =
    isAdmin || (myPersonId != null && meeting.organiser.id == myPersonId)

/** The caller's own attendee row, or null when they organise the meeting or are not part of it. */
fun myAttendeeRow(meeting: MeetingDetail, myPersonId: String?): AttendeeRow? =
    myPersonId?.let { id -> meeting.attendees.firstOrNull { it.person.id == id } }

/** User-facing text for the API's RespondToMeetingError codes. */
fun respondErrorMessage(code: String): String = when (code) {
    "NoLinkedPerson" -> "Your account isn't linked to a person yet, so a response can't be recorded."
    "MeetingNotFound" -> "This meeting no longer exists - it may have been deleted."
    "NotAnAttendee" -> "You aren't an attendee of this meeting, so there's nothing to respond to."
    else -> "Your response could not be saved ($code)."
}

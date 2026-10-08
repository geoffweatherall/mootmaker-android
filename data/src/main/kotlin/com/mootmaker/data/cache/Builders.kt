package com.mootmaker.data.cache

import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.MeetingInput
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.meeting.AttendeeRow
import com.mootmaker.data.meeting.MeetingRoomInput
import com.mootmaker.data.meeting.PersonRef
import com.mootmaker.data.meeting.MeetingInput as DetailInput

/*
 * The store's data in the shapes the existing pure builders take (buildAgenda, buildWeek,
 * buildRoomCards, buildMeetingDetail), resolving people and rooms by id from the reference data.
 */

fun List<CachedRoom>.toRoomInputs(): List<RoomInput> = map { RoomInput(it.id, it.name, it.color) }

/** People by id, for resolving a meeting's organiser and attendees. */
fun Reference.peopleById(): Map<String, CachedPerson> = people.associateBy { it.id }

fun List<CachedDay>.toDayInputs(people: Map<String, CachedPerson>): List<DayInput> = map { day ->
    DayInput(
        date = day.date.toString(),
        meetings = day.meetings.map { meeting ->
            MeetingInput(
                id = meeting.id,
                subject = meeting.subject,
                startTime = meeting.startTime,
                endTime = meeting.endTime,
                roomId = meeting.roomId,
                organiserId = meeting.organiserId,
                attendeeIds = meeting.attendees.map { it.personId },
                organiserName = people[meeting.organiserId]?.name.orEmpty(),
                attendeeStatuses = meeting.attendees.associate { it.personId to it.status },
            )
        },
    )
}

/** A meeting as the details screen's builder takes it, with names and avatars from [reference]. */
fun CachedMeeting.toDetailInput(reference: Reference): DetailInput {
    val people = reference.peopleById()
    fun person(id: String) = people[id].let { PersonRef(id, it?.name.orEmpty(), it?.avatarUrl) }
    return DetailInput(
        id = id,
        subject = subject,
        startTime = startTime,
        endTime = endTime,
        room = MeetingRoomInput(roomId, reference.rooms.firstOrNull { it.id == roomId }?.name.orEmpty()),
        organiser = person(organiserId),
        attendees = attendees.map { AttendeeRow(person(it.personId), it.status) },
        version = version,
    )
}

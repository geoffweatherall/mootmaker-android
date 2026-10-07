package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.formatDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingDetailsTest {
    private val meeting = MeetingInput(
        id = "m1",
        subject = "Planning",
        startTime = "2026-10-07T09:00:00",
        endTime = "2026-10-07T10:00:00",
        room = MeetingRoomInput("r2", "Cellar"),
        organiser = PersonRef("p1", "Pat Example"),
        attendees = listOf(AttendeeRow(PersonRef("p2", "Sam Other"), AttendeeStatus.Maybe)),
    )

    @Test
    fun theRoomColourIsItsPositionInTheWholeNameSortedRoomList() {
        // Cellar is third by name; Atrium is explicitly Green (slot 5), so it doesn't take a position slot.
        val rooms = listOf(RoomInput("r1", "Boardroom", null), RoomInput("r2", "Cellar", null), RoomInput("r3", "Atrium", null))
        assertEquals(2, buildMeetingDetail(meeting, rooms).roomColorSlot)
        val withColour = listOf(RoomInput("r2", "Cellar", RoomColor.Violet))
        assertEquals(RoomColor.Violet.ordinal, buildMeetingDetail(meeting, withColour).roomColorSlot)
    }

    @Test
    fun theMeetingKeepsItsNamesTimesAndAttendeeResponses() {
        val detail = buildMeetingDetail(meeting, emptyList())
        assertEquals("Cellar", detail.roomName)
        assertEquals("2026-10-07T09:00:00", detail.startTime)
        assertEquals(AttendeeStatus.Maybe, detail.attendees.single().status)
        assertEquals("Pat Example", detail.organiser.name)
    }

    // Use case H.71: the date is shown once, in the viewer's format.
    @Test
    fun theDateIsShownInTheViewersFormat() {
        assertEquals("2026-10-07", formatDate("2026-10-07T09:00:00", DateFormat.Iso))
        assertEquals("07/10/2026", formatDate("2026-10-07T09:00:00", DateFormat.British))
        assertEquals("10/07/2026", formatDate("2026-10-07T09:00:00", DateFormat.Usa))
        assertEquals("not a date", formatDate("not a date", DateFormat.Usa))
    }

    @Test
    fun statusLabelsAreWhatTheScreenShows() {
        assertEquals(listOf("Going", "Not going", "Maybe", "No response"), AttendeeStatus.entries.map { it.label })
    }

    @Test
    fun theShareLinkIsTheWebappsMeetingPage() {
        assertEquals("https://www.mootmaker.com/meetings/m1", meetingShareUrl("https://www.mootmaker.com", "m1"))
    }

    // Use cases O.114 and O.115: the organiser and an admin get Edit and Cancel; nobody else does.
    @Test
    fun onlyTheOrganiserOrAnAdminCanEditAndCancel() {
        val detail = buildMeetingDetail(meeting, emptyList())
        assertEquals(true, canEditMeeting(detail, myPersonId = "p1", isAdmin = false))
        assertEquals(true, canEditMeeting(detail, myPersonId = "p9", isAdmin = true))
        assertEquals(false, canEditMeeting(detail, myPersonId = "p2", isAdmin = false))
        assertEquals(false, canEditMeeting(detail, myPersonId = null, isAdmin = false))
    }

    // Use case H.108: only an attendee has a response to give; the organiser is implicitly going.
    @Test
    fun onlyAnAttendeeHasAResponseRow() {
        val detail = buildMeetingDetail(meeting, emptyList())
        assertEquals(AttendeeStatus.Maybe, myAttendeeRow(detail, "p2")?.status)
        assertEquals(null, myAttendeeRow(detail, "p1"))
        assertEquals(null, myAttendeeRow(detail, null))
    }

    @Test
    fun theVersionAndRoomIdComeThroughForEditing() {
        val detail = buildMeetingDetail(meeting.copy(version = "v3"), emptyList())
        assertEquals("v3", detail.version)
        assertEquals("r2", detail.roomId)
    }

    @Test
    fun respondErrorsAreWordedForTheScreen() {
        assertEquals("You aren't an attendee of this meeting, so there's nothing to respond to.", respondErrorMessage("NotAnAttendee"))
        assertEquals("This meeting no longer exists - it may have been deleted.", respondErrorMessage("MeetingNotFound"))
    }
}

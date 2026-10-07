package com.mootmaker.data.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AgendaTest {
    private val today = LocalDate.parse("2026-10-07")
    private val rooms = listOf(
        RoomInput("r-b", "Boardroom", null),
        RoomInput("r-a", "Atrium", RoomColor.Magenta),
        RoomInput("r-c", "Cellar", null),
    )

    private fun meeting(id: String, start: String, organiser: String = "me", attendees: List<String> = emptyList(), room: String = "r-b") =
        MeetingInput(id, "Meeting $id", start, start.replace("T09", "T10"), room, organiser, attendees)

    @Test
    fun onlyMeetingsYouOrganiseOrAttendAreShown() {
        val agenda = buildAgenda(
            personId = "me",
            today = today,
            days = listOf(
                DayInput(
                    "2026-10-07",
                    listOf(
                        meeting("mine", "2026-10-07T09:00:00"),
                        meeting("attending", "2026-10-07T09:30:00", organiser = "them", attendees = listOf("me")),
                        meeting("not-mine", "2026-10-07T09:15:00", organiser = "them", attendees = listOf("other")),
                    ),
                ),
            ),
            rooms = rooms,
        )
        assertEquals(listOf("mine", "attending"), agenda.today.rows.map { it.meetingId })
    }

    @Test
    fun eachDayIsSortedByStartTime() {
        val agenda = buildAgenda(
            "me",
            today,
            listOf(
                DayInput("2026-10-08", listOf(meeting("late", "2026-10-08T15:00:00"), meeting("early", "2026-10-08T08:00:00"))),
            ),
            rooms,
        )
        assertEquals(listOf("early", "late"), agenda.tomorrow.rows.map { it.meetingId })
        assertEquals(LocalDate.parse("2026-10-08"), agenda.tomorrow.date)
    }

    @Test
    fun noMeetingsOnEitherDayIsEmpty() {
        val agenda = buildAgenda("me", today, listOf(DayInput("2026-10-07", emptyList()), DayInput("2026-10-08", emptyList())), rooms)
        assertTrue(agenda.isEmpty)
    }

    @Test
    fun rowsCarryTheRoomNameAndColour() {
        val agenda = buildAgenda("me", today, listOf(DayInput("2026-10-07", listOf(meeting("m", "2026-10-07T09:00:00", room = "r-a")))), rooms)
        val row = agenda.today.rows.single()
        assertEquals("Atrium", row.roomName)
        assertEquals(RoomColor.Magenta.ordinal, row.roomColorSlot)
    }

    @Test
    fun roomsWithoutAColourGetASlotByNameOrder() {
        // Sorted by name: Atrium (explicit Magenta), Boardroom (index 1), Cellar (index 2).
        assertEquals(mapOf("r-a" to 4, "r-b" to 1, "r-c" to 2), roomColorSlots(rooms))
    }
}

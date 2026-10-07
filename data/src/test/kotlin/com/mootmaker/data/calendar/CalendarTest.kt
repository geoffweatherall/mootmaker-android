package com.mootmaker.data.calendar

import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.MeetingInput
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
    private val monday = LocalDate.of(2026, 10, 5)

    @Test
    fun theWorkWeekStartsOnTheMondayOnOrBeforeTheDate() {
        assertEquals(monday, startOfWorkWeek(LocalDate.of(2026, 10, 5)))
        assertEquals(monday, startOfWorkWeek(LocalDate.of(2026, 10, 7)))
        // A weekend belongs to the week that just ended.
        assertEquals(monday, startOfWorkWeek(LocalDate.of(2026, 10, 11)))
        assertEquals(LocalDate.of(2026, 10, 12), startOfWorkWeek(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun aWeekHasFiveWorkDaysMondayFirst() {
        assertEquals((5..9).map { LocalDate.of(2026, 10, it) }, workWeekDates(monday))
    }

    private fun meeting(id: String, day: Int, hour: Int, organiser: String = "p1", attendees: List<String> = emptyList(), room: String = "r1") =
        MeetingInput(id, id, "2026-10-%02dT%02d:00:00".format(day, hour), "2026-10-%02dT%02d:00:00".format(day, hour + 1), room, organiser, attendees)

    private val rooms = listOf(RoomInput("r1", "Boardroom", null), RoomInput("r2", "Atrium", RoomColor.Green))

    private val days = listOf(
        DayInput("2026-10-05", listOf(meeting("late", 5, 15), meeting("early", 5, 9), meeting("not-mine", 5, 10, organiser = "p2"))),
        DayInput("2026-10-06", emptyList()),
        DayInput("2026-10-07", listOf(meeting("attending", 7, 11, organiser = "p2", attendees = listOf("p1", "p3"), room = "r2"))),
    )

    // Use cases G.59 and G.63: the person's meetings only, each day sorted, an empty day is empty.
    @Test
    fun aWeekHoldsOnlyThePersonsMeetingsSortedByStartTimeIncludingEmptyDays() {
        val week = buildWeek("p1", monday, days, rooms)

        assertEquals(5, week.size)
        assertEquals(listOf("early", "late"), week[0].rows.map { it.meetingId })
        assertTrue(week[1].rows.isEmpty())
        assertEquals(listOf("attending"), week[2].rows.map { it.meetingId })
        assertEquals("Atrium", week[2].rows.single().roomName)
        // Thursday and Friday were not sent at all: still shown, as empty.
        assertTrue(week[3].rows.isEmpty() && week[4].rows.isEmpty())
    }

    // Use case G.60: another person's week comes from the same data.
    @Test
    fun anotherPersonSeesTheirOwnMeetings() {
        val week = buildWeek("p2", monday, days, rooms)
        assertEquals(listOf("not-mine"), week[0].rows.map { it.meetingId })
        assertEquals(listOf("attending"), week[2].rows.map { it.meetingId })
        assertTrue(buildWeek("p3", monday, days, rooms)[0].rows.isEmpty())
    }

    // Use case G.62: navigation stops at the server's window.
    @Test
    fun weekNavigationIsBoundedByTheServersWindow() {
        val bounds = CalendarBounds(earliest = LocalDate.of(2026, 9, 21), latest = LocalDate.of(2026, 11, 2))
        // Two weeks back from the 5th is the 21st, the earliest retained Monday.
        assertTrue(canGoToPreviousWeek(LocalDate.of(2026, 9, 28), bounds))
        assertFalse(canGoToPreviousWeek(LocalDate.of(2026, 9, 21), bounds))
        // The 26th's next Monday is the 2nd, the last bookable day itself.
        assertTrue(canGoToNextWeek(LocalDate.of(2026, 10, 26), bounds))
        assertFalse(canGoToNextWeek(LocalDate.of(2026, 11, 2), bounds))
    }

    @Test
    fun unknownBoundsLeaveNavigationOpen() {
        assertTrue(canGoToPreviousWeek(monday, null))
        assertTrue(canGoToNextWeek(monday, null))
    }
}

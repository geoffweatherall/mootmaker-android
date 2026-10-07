package com.mootmaker.data.availability

import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AvailabilityTest {
    private fun booking(subject: String, start: String, end: String, date: String = "2026-09-21") =
        Booking("id-$subject", subject, "${date}T$start:00", "${date}T$end:00")

    private val now = 10 * 60

    @Test
    fun minutesSinceMidnightReadsTheWallClock() {
        assertEquals(9 * 60 + 30, minutesSinceMidnight("2026-09-21T09:30:00"))
        assertEquals(0, minutesSinceMidnight("2026-09-21T00:00:00"))
    }

    // Use case E.26.
    @Test
    fun todayWithNoMeetingsIsFreeNow() {
        assertEquals(RoomStatus("Free now", true, "No more meetings today"), statusForRoom(emptyList(), true, now, TimeFormat.TwentyFourHour))
    }

    @Test
    fun busyUntilTheEndWhenNowIsInsideAMeeting() {
        val status = statusForRoom(listOf(booking("Standup", "09:30", "10:30")), true, now, TimeFormat.TwentyFourHour)
        assertEquals(RoomStatus("Busy until 10:30", false, "Standup"), status)
    }

    @Test
    fun freeAgainAtAMeetingsOwnEndMinute() {
        assertTrue(statusForRoom(listOf(booking("Standup", "09:00", "10:00")), true, now, TimeFormat.TwentyFourHour).free)
    }

    @Test
    fun freeNowNamesTheNextMeeting() {
        val status = statusForRoom(listOf(booking("Design Review", "14:00", "15:00")), true, now, TimeFormat.TwentyFourHour)
        assertEquals(RoomStatus("Free now", true, "Next: Design Review at 14:00"), status)
    }

    @Test
    fun aFinishedMeetingIsNotOfferedAsNext() {
        val status = statusForRoom(listOf(booking("Early Sync", "08:00", "08:30")), true, now, TimeFormat.TwentyFourHour)
        assertEquals(RoomStatus("Free now", true, "No more meetings today"), status)
    }

    @Test
    fun statusTimesFollowThePersonsFormat() {
        val status = statusForRoom(listOf(booking("Design Review", "14:00", "15:00")), true, now, TimeFormat.AmPm)
        assertEquals("Next: Design Review at 02:00 PM", status.subLabel)
    }

    // Use case E.31.
    @Test
    fun anotherDayWithNoMeetingsIsFreeAllDay() {
        assertEquals(RoomStatus("Free all day", true, "No meetings booked yet."), statusForRoom(emptyList(), false, now, TimeFormat.TwentyFourHour))
    }

    @Test
    fun anotherDaySummarisesTheCountAndFirstMeeting() {
        val bookings = listOf(booking("All-Hands", "09:00", "10:00"), booking("Retro", "14:00", "15:00"))
        assertEquals(RoomStatus("2 meetings", false, "First: All-Hands at 09:00"), statusForRoom(bookings, false, now, TimeFormat.TwentyFourHour))
    }

    @Test
    fun oneMeetingIsSingular() {
        assertEquals("1 meeting", statusForRoom(listOf(booking("Retro", "14:00", "15:00")), false, now, TimeFormat.TwentyFourHour).label)
    }

    @Test
    fun segmentIsAPercentageOfTheEightToSixWindow() {
        // 10:00 is 2h into the 10h window; the meeting is 3h long.
        assertEquals(listOf(TimelineSegment(20f, 30f)), segmentsForRoom(listOf(booking("Standup", "10:00", "13:00"))))
    }

    @Test
    fun aVeryShortMeetingKeepsAMinimumWidth() {
        assertEquals(1.5f, segmentsForRoom(listOf(booking("Quick", "09:00", "09:05")))[0]!!.width, 0f)
    }

    @Test
    fun aMeetingStartingBeforeTheWindowIsClippedToTheLeftEdge() {
        assertEquals(TimelineSegment(0f, 10f), segmentsForRoom(listOf(booking("Early", "06:00", "09:00")))[0])
    }

    @Test
    fun aMeetingEndingAfterTheWindowIsClippedToTheRightEdge() {
        assertEquals(TimelineSegment(90f, 10f), segmentsForRoom(listOf(booking("Long", "17:00", "20:00")))[0])
    }

    @Test
    fun aMeetingOutsideTheWindowIsDroppedButStaysIndexAligned() {
        val segments = segmentsForRoom(listOf(booking("Late", "22:00", "23:00"), booking("Standup", "09:00", "09:30")))
        assertNull(segments[0])
        assertNotNull(segments[1])
        assertEquals(emptyList<TimelineSegment?>(), segmentsForRoom(emptyList()))
    }

    // Use cases E.33 and E.34.
    @Test
    fun eachMeetingAppearsOnlyOnItsOwnRoomsCardInOrder() {
        val rooms = listOf(AvailabilityRoom("b", "Boardroom", 8, null), AvailabilityRoom("a", "Atrium", 4, RoomColor.Green))
        fun meeting(id: String, room: String, start: String, end: String) =
            AvailabilityMeeting(id, id, "2026-09-21T$start:00", "2026-09-21T$end:00", room)
        val cards = buildRoomCards(
            rooms,
            listOf(
                meeting("late", "b", "10:00", "11:00"),
                meeting("early", "b", "09:00", "10:00"),
                meeting("overlap", "a", "09:30", "10:30"),
            ),
        )
        assertEquals(listOf("Atrium", "Boardroom"), cards.map { it.name })
        assertEquals(listOf("overlap"), cards[0].bookings.map { it.id })
        assertEquals(listOf("early", "late"), cards[1].bookings.map { it.id })
    }

    // Room colour fallback: explicit colour wins, otherwise the position by name.
    @Test
    fun roomColoursUseTheExplicitChoiceOrTheirPositionByName() {
        val cards = buildRoomCards(
            listOf(AvailabilityRoom("b", "Boardroom", 8, null), AvailabilityRoom("a", "Atrium", 4, RoomColor.Green), AvailabilityRoom("c", "Cellar", 2, null)),
            emptyList(),
        )
        assertEquals(listOf(RoomColor.Green.ordinal, 1, 2), cards.map { it.colorSlot })
    }

    @Test
    fun nearDaysAreNamedAndOthersFallBackToTheWeekday() {
        val today = LocalDate.parse("2026-10-07")
        assertEquals("today", dayRelativeLabel(today, today))
        assertEquals("tomorrow", dayRelativeLabel(today.plusDays(1), today))
        assertNull(dayRelativeLabel(today.plusDays(2), today))
        assertNull(dayRelativeLabel(today.minusDays(1), today))
    }
}

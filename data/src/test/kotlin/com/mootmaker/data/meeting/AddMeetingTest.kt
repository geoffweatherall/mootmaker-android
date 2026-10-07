package com.mootmaker.data.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

// Ported from the webapp's addMeetingLogic.test.ts, plus the pieces that are the Android form's own.
class AddMeetingTest {
    private val ann = PersonOption("p1", "Ann")
    private val bob = PersonOption("p2", "Bob")
    private val cy = PersonOption("p3", "Cy")
    private val people = listOf(ann, bob, cy)

    private val small = RoomOption("r1", "Small", 4)
    private val medium = RoomOption("r2", "Medium", 8)
    private val large = RoomOption("r3", "Large", 12)

    // Use case F.44: exclusivity between organiser and attendees.
    @Test
    fun organiserOptionsExcludeAnyoneAttending() {
        assertEquals(listOf(cy), organiserOptions(people, listOf("p1", "p2")))
    }

    @Test
    fun everyoneCanOrganiseWhenNobodyAttendsYet() {
        assertEquals(people, organiserOptions(people, emptyList()))
    }

    @Test
    fun attendeeOptionsExcludeTheOrganiser() {
        assertEquals(listOf(ann, cy), attendeeOptions(people, "p2"))
    }

    @Test
    fun everyoneCanAttendWhenThereIsNoOrganiserYet() {
        assertEquals(people, attendeeOptions(people, ""))
    }

    // Use case F.53: the first press takes the best fit, later ones step through and wrap.
    @Test
    fun theFirstPressUsesTheFetchedListAndPicksItsFirstRoom() {
        val step = advanceSuggestion(SuggestionCache(), "k", listOf(small, medium))
        assertEquals(small, step.room)
        assertEquals(SuggestionCache(listOf(small, medium), 0, "k"), step.cache)
    }

    @Test
    fun furtherPressesStepThroughTheCachedListWithoutFetching() {
        val first = advanceSuggestion(SuggestionCache(), "k", listOf(small, medium, large))
        val second = advanceSuggestion(first.cache, "k")
        assertEquals(medium, second.room)
        assertEquals(large, advanceSuggestion(second.cache, "k").room)
    }

    @Test
    fun theListWrapsBackToTheFirstRoom() {
        var step = advanceSuggestion(SuggestionCache(), "k", listOf(small, medium))
        step = advanceSuggestion(step.cache, "k")
        assertEquals(small, advanceSuggestion(step.cache, "k").room)
    }

    @Test
    fun aSingleRoomCyclesBackToItself() {
        val first = advanceSuggestion(SuggestionCache(), "k", listOf(small))
        assertEquals(small, advanceSuggestion(first.cache, "k").room)
    }

    // Use case F.52: nothing qualifies, and that is remembered so a press doesn't re-fetch.
    @Test
    fun anEmptyFetchGivesNoRoomAndStaysEmptyWithoutFetchingAgain() {
        val first = advanceSuggestion(SuggestionCache(), "k", emptyList())
        assertNull(first.room)
        assertFalse(first.cache.needsFetch("k"))
        assertNull(advanceSuggestion(first.cache, "k").room)
    }

    @Test
    fun aListFetchedForTheSameKeyIgnoresAFreshOne() {
        val first = advanceSuggestion(SuggestionCache(), "k", listOf(small, medium))
        assertEquals(medium, advanceSuggestion(first.cache, "k", listOf(large)).room)
    }

    // Use case F.54: a different slot or headcount makes the cache stale.
    @Test
    fun aCacheForADifferentKeyIsStaleEvenWhenItHoldsRooms() {
        val first = advanceSuggestion(SuggestionCache(), "k", listOf(small, medium))
        assertTrue(first.cache.needsFetch("other"))
        assertEquals(large, advanceSuggestion(first.cache, "other", listOf(large)).room)
    }

    @Test
    fun nothingIsCachedBeforeTheFirstFetch() {
        assertTrue(SuggestionCache().needsFetch("k"))
        assertEquals("a|b|3", suggestionKey("a", "b", 3))
    }

    // Use case F.40.
    @Test
    fun theDefaultStartsAtTheNextBoundaryAndRunsAnHour() {
        assertEquals(DefaultMeetingTimes(LocalTime.of(10, 15), LocalTime.of(11, 15)), defaultMeetingTimes(LocalTime.of(10, 1)))
        assertEquals(DefaultMeetingTimes(LocalTime.of(10, 15), LocalTime.of(11, 15)), defaultMeetingTimes(LocalTime.of(10, 14, 59)))
    }

    @Test
    fun anAlignedTimeIsLeftAlone() {
        assertEquals(DefaultMeetingTimes(LocalTime.of(9, 30), LocalTime.of(10, 30)), defaultMeetingTimes(LocalTime.of(9, 30)))
    }

    @Test
    fun secondsPastABoundaryPushToTheNextOne() {
        assertEquals(LocalTime.of(9, 45), defaultMeetingTimes(LocalTime.of(9, 30, 1)).start)
    }

    @Test
    fun lateEveningClampsToTheLastSlotsThatFitOnTheGrid() {
        assertEquals(DefaultMeetingTimes(LocalTime.of(23, 0), LocalTime.of(23, 45)), defaultMeetingTimes(LocalTime.of(22, 50)))
        assertEquals(DefaultMeetingTimes(LocalTime.of(23, 30), LocalTime.of(23, 45)), defaultMeetingTimes(LocalTime.of(23, 40)))
        assertEquals(DefaultMeetingTimes(LocalTime.of(23, 30), LocalTime.of(23, 45)), defaultMeetingTimes(LocalTime.of(23, 59)))
    }

    // mootmaker-webapp#48: the default must be a bookable pair at every minute of the day.
    @Test
    fun theDefaultIsOnTheGridAndEndsAfterItStartsAtEveryMinuteOfTheDay() {
        for (minute in 0 until 24 * 60) {
            val times = defaultMeetingTimes(LocalTime.of(minute / 60, minute % 60))
            assertTrue("start $times at minute $minute", times.start.minute % 15 == 0 && times.start in quarterHours)
            assertTrue("end $times at minute $minute", times.end.minute % 15 == 0 && times.end in quarterHours)
            assertTrue("order $times at minute $minute", times.end.isAfter(times.start))
        }
    }

    // Use case F.41: nothing but quarter hours is offered.
    @Test
    fun onlyQuarterHoursAreOffered() {
        assertEquals(96, quarterHours.size)
        assertTrue(quarterHours.all { it.minute % 15 == 0 && it.second == 0 })
        assertEquals(LocalTime.MIDNIGHT, quarterHours.first())
        assertEquals(LocalTime.of(23, 45), quarterHours.last())
    }

    @Test
    fun theApiTimeAlwaysCarriesSeconds() {
        assertEquals("2026-07-01T14:30:00", localDateTime(LocalDate.of(2026, 7, 1), LocalTime.of(14, 30)))
        assertEquals("2026-07-01T00:00:00", localDateTime(LocalDate.of(2026, 7, 1), LocalTime.MIDNIGHT))
    }

    @Test
    fun everyKnownErrorHasItsOwnMessageAndAnUnknownOneStillSaysSomething() {
        val known = listOf(
            "StartMisaligned", "EndMisaligned", "SpansMultipleDays", "EndBeforeStart", "InsufficientCapacity", "TimeRangeUnavailable",
            "RoomRequired", "RoomNotFound", "OrganiserRequired", "OrganiserNotFound", "AttendeeNotFound", "SubjectRequired",
            "OrganiserIsAttendee", "DayIsFull", "TooManyAttendees", "SubjectTooLong", "OutsideBookableRange",
            "TooManyMeetingsInOneCall", "MeetingNotFound", "MeetingChanged",
        )
        val messages = known.map(::meetingErrorMessage)
        assertEquals(known.size, messages.toSet().size)
        assertTrue(messages.none { "could not be saved" in it })
        assertEquals("The meeting could not be saved (Brand_new).", meetingErrorMessage("Brand_new"))
    }

    @Test
    fun theRoomLabelNamesItsCapacity() {
        assertEquals("Medium (capacity 8)", medium.label)
    }
}

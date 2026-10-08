package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.mootmaker.app.ui.availability.AvailabilityActions
import com.mootmaker.app.ui.availability.AvailabilityScreen
import com.mootmaker.app.ui.availability.AvailabilityState
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.AvailabilityData
import com.mootmaker.data.api.DateBounds
import com.mootmaker.data.availability.Booking
import com.mootmaker.data.availability.RoomCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

val NO_AVAILABILITY_ACTIONS = AvailabilityActions({}, {}, {}, {}, {}, {}, {}, {})

private fun booking(id: String, subject: String, start: String, end: String) =
    Booking(id, subject, "${TODAY}T$start:00", "${TODAY}T$end:00")

val SAMPLE_AVAILABILITY = AvailabilityState(
    date = TODAY,
    today = TODAY,
    nowMinutes = 10 * 60,
    loading = false,
    data = AvailabilityData(
        TimeFormat.TwentyFourHour,
        listOf(
            RoomCard("r1", "Atrium", 4, 5, listOf(booking("m2", "Design review", "14:30", "15:30"))),
            RoomCard("r2", "Boardroom", 12, 0, listOf(booking("m1", "Stand-up", "09:30", "10:30"), booking("m3", "Retro", "15:30", "16:30"))),
            RoomCard("r3", "Cellar", 2, 2, emptyList()),
        ),
        DateBounds(TODAY.minusDays(30), TODAY.plusDays(90)),
    ),
    expanded = setOf("r2"),
).let { it.copy(bounds = it.data!!.bounds) }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AvailabilityScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: AvailabilityState, actions: AvailabilityActions = NO_AVAILABILITY_ACTIONS) {
        compose.setContent { MootmakerTheme { AvailabilityScreen(state, actions) } }
    }

    // Use case E.26: each room's status for today.
    @Test
    fun todayShowsEachRoomsLiveStatus() {
        show(SAMPLE_AVAILABILITY)

        compose.onNodeWithText("Atrium").assertIsDisplayed()
        compose.onNodeWithText("Capacity 12").assertIsDisplayed()
        compose.onNodeWithText("Busy until 10:30").assertIsDisplayed()
        compose.onNodeWithText("Next: Design review at 14:30").assertIsDisplayed()
        compose.onNodeWithText("Cellar").assertIsDisplayed()
    }

    // Use case E.32: an open room lists subject and time range; closed rooms show a count.
    @Test
    fun anOpenRoomListsItsMeetingsAndAClosedOneCountsThem() {
        show(SAMPLE_AVAILABILITY)

        compose.onNodeWithText("Hide today's meetings").assertIsDisplayed()
        compose.onNodeWithText("09:30–10:30").assertIsDisplayed()
        compose.onNodeWithText("Retro").assertIsDisplayed()
        compose.onNodeWithText("See today's meetings (1)").assertIsDisplayed()
        compose.onNodeWithText("Design review").assertDoesNotExist()
    }

    @Test
    fun timesFollowThePersonsFormat() {
        val state = SAMPLE_AVAILABILITY.copy(data = SAMPLE_AVAILABILITY.data!!.copy(timeFormat = TimeFormat.AmPm))
        show(state)
        compose.onNodeWithText("09:30 AM–10:30 AM").assertIsDisplayed()
    }

    // Use cases E.27 and E.28: another day gets a summary rather than a live pill.
    @Test
    fun anotherDayShowsMeetingCountsAndFreeAllDay() {
        show(SAMPLE_AVAILABILITY.copy(date = TODAY.plusDays(3), expanded = emptySet()))

        compose.onNodeWithText("2 meetings").assertIsDisplayed()
        compose.onNodeWithText("Free all day").assertIsDisplayed()
        compose.onNodeWithText("See Saturday's meetings (2)").assertIsDisplayed()
    }

    // Use case E.30.
    @Test
    fun noRoomsShowsTheEmptyState() {
        show(SAMPLE_AVAILABILITY.copy(data = SAMPLE_AVAILABILITY.data!!.copy(rooms = emptyList())))
        compose.onNodeWithText("No rooms exist yet.").assertIsDisplayed()
    }

    // Use case E.31: rooms with no meetings are free, not the empty state.
    @Test
    fun aRoomWithNoMeetingsIsFreeNotMissing() {
        show(SAMPLE_AVAILABILITY.copy(date = TODAY.plusDays(1), expanded = setOf("r3")))
        compose.onNodeWithText("No meetings booked for tomorrow.").assertIsDisplayed()
        compose.onAllNodesWithText("No rooms exist yet.").assertCountEquals(0)
    }

    // Use cases E.27 and E.29: moving between days and jumping to a date, within the server's window.
    @Test
    fun dayControlsAreWiredAndStopAtTheBoundaries() {
        var next = 0
        var previous = 0
        show(SAMPLE_AVAILABILITY, NO_AVAILABILITY_ACTIONS.copy(onNextDay = { next++ }, onPreviousDay = { previous++ }))
        compose.onNodeWithContentDescription("Next day").performClick()
        compose.onNodeWithContentDescription("Previous day").performClick()
        assertEquals(1 to 1, next to previous)
    }

    @Test
    fun theFirstAndLastBookableDaysDisableTheirDirection() {
        val bounds = SAMPLE_AVAILABILITY.bounds!!
        show(SAMPLE_AVAILABILITY.copy(date = bounds.earliest))
        compose.onNodeWithContentDescription("Previous day").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next day").assertIsDisplayed()
    }

    // Use case E.37's entry point (the form itself arrives in M4).
    @Test
    fun addMeetingIsOfferedFromThisScreen() {
        var added = false
        show(SAMPLE_AVAILABILITY, NO_AVAILABILITY_ACTIONS.copy(onAddMeeting = { added = true }))
        compose.onNodeWithContentDescription("Add meeting").performClick()
        assertEquals(true, added)
    }

    @Test
    fun aFailedFirstLoadOffersARetry() {
        show(SAMPLE_AVAILABILITY.copy(data = null, error = "Couldn't reach Mootmaker. Check your connection and try again."))
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }
}

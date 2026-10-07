package com.mootmaker.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mootmaker.app.ui.meeting.MeetingDetailsActions
import com.mootmaker.app.ui.meeting.MeetingDetailsScreen
import com.mootmaker.app.ui.meeting.MeetingDetailsState
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.meeting.AttendeeRow
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetail
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.meeting.PersonRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

val NO_MEETING_ACTIONS = MeetingDetailsActions({}, {}, {}, {})

val SAMPLE_MEETING = MeetingDetail(
    id = "m1",
    subject = "Design review",
    startTime = "2026-10-07T14:30:00",
    endTime = "2026-10-07T15:30:00",
    roomId = "room-2",
    roomName = "Atrium",
    roomColorSlot = 5,
    organiser = PersonRef("p2", "Sam Other"),
    attendees = listOf(
        AttendeeRow(PersonRef("p1", "Pat Example"), AttendeeStatus.Going),
        AttendeeRow(PersonRef("p3", "Robin Guest"), AttendeeStatus.NoResponse),
        AttendeeRow(PersonRef("p4", "Alex Third"), AttendeeStatus.NotGoing),
    ),
)

fun meetingState(meeting: MeetingDetail?, myPersonId: String? = "p1", dateFormat: DateFormat = DateFormat.Iso) = MeetingDetailsState(
    data = MeetingDetailsData(meeting, myPersonId, TimeFormat.TwentyFourHour, dateFormat),
    loading = false,
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MeetingDetailsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: MeetingDetailsState, actions: MeetingDetailsActions = NO_MEETING_ACTIONS) {
        compose.setContent { MootmakerTheme { MeetingDetailsScreen(state, actions) } }
    }

    // Use cases H.69 and H.71: a meeting you attend, the date once and the time as a range.
    @Test
    fun showsTheMeetingYouAttendWithTheDateOnceAndATimeRange() {
        show(meetingState(SAMPLE_MEETING))

        compose.onNodeWithText("Design review").assertIsDisplayed()
        compose.onNodeWithText("Atrium").assertIsDisplayed()
        compose.onNodeWithText("2026-10-07").assertIsDisplayed()
        compose.onNodeWithText("14:30–15:30").assertIsDisplayed()
        compose.onNodeWithText("Sam Other").assertIsDisplayed()
        compose.onNodeWithText("Attendees · 3").assertIsDisplayed()
        // The caller's own row says "You"; everyone else shows their response.
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("No response").assertIsDisplayed()
        // Alex's response, and the same words on your own control underneath.
        compose.onAllNodesWithText("Not going").assertCountEquals(2)
    }

    // Use case H.68: the organiser has no response status, and is marked "You" when it is the caller.
    @Test
    fun theOrganiserIsYouWhenItIsTheCaller() {
        show(meetingState(SAMPLE_MEETING.copy(organiser = PersonRef("p1", "Pat Example"), attendees = emptyList())))

        compose.onNodeWithText("Pat Example").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("No attendees.").assertIsDisplayed()
    }

    // Use case H.70: someone with no part in the meeting sees it without a "You" row.
    @Test
    fun aMeetingYouHaveNoPartInShowsNoYouRow() {
        show(meetingState(SAMPLE_MEETING, myPersonId = "p9"))
        compose.onNodeWithText("Sam Other").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("You") == 0)
    }

    @Test
    fun theDateFollowsThePersonsFormat() {
        show(meetingState(SAMPLE_MEETING, dateFormat = DateFormat.British))
        compose.onNodeWithText("07/10/2026").assertIsDisplayed()
    }

    // Use case H.73: a meeting that doesn't exist, or whose day has aged out.
    @Test
    fun aMissingMeetingSaysSo() {
        show(meetingState(null))
        compose.onNodeWithText("Meeting not found.").assertIsDisplayed()
    }

    @Test
    fun aFailedLoadOffersARetry() {
        var retried = false
        show(MeetingDetailsState(loading = false, error = "Couldn't reach Mootmaker."), NO_MEETING_ACTIONS.copy(onRetry = { retried = true }))
        compose.onNodeWithText("Couldn't reach Mootmaker.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    // Use case G.65's Share action, and the way to a person's calendar from a meeting.
    @Test
    fun shareAndPersonRowsReportWhatWasTapped() {
        var shared: String? = null
        var opened: String? = null
        show(
            meetingState(SAMPLE_MEETING),
            NO_MEETING_ACTIONS.copy(onShare = { shared = it.id }, onOpenCalendar = { opened = it }),
        )

        compose.onNodeWithContentDescription("Share meeting").performClick()
        compose.onNodeWithText("Robin Guest").performClick()

        assertEquals("m1", shared)
        assertEquals("p3", opened)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

package com.mootmaker.app.acceptance

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases H.68 to H.71 (and D.22's and E.32's links to them) against a real environment. See
 * [Acceptance]. Every case creates its own uniquely named room and meetings through the real API.
 *
 * H.73 (a meeting that doesn't exist) is Robolectric-only: nothing in the app lets a person type
 * an id, and a meeting cancelled between a list and a tap is not something this suite can stage.
 * H.72 is webapp-specific. H.108's response control is M5.
 */
class MeetingAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    /** D.22 (the row links to its details), H.68 and H.71: the organiser's own meeting. */
    @Test
    fun anAgendaRowOpensTheMeetingYouOrganiseWithItsDateAndTimeRange() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val room = api.createRoom("A-Det $run")
        val today = LocalDate.now()
        api.createMeeting(
            room, api.myPersonId(), "Mine $run", "${today}T09:00:00", "${today}T10:00:00",
            attendeeIds = listOf(Api(Acceptance.standard).myPersonId()),
        )

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Mine $run")
        compose.onNodeWithText("Mine $run").performClick()

        compose.waitForText("Attendees · 1")
        assertTrue(compose.shown("A-Det $run"))
        // The date once, and the time as a range rather than two date-times.
        assertTrue(compose.shown("$today"))
        assertTrue(compose.shown("09:00–10:00"))
        // The organiser's row is the caller's, so it says "You".
        assertTrue(compose.shown("You"))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("Rooms today")
    }

    /** H.69: a meeting you attend but did not organise, seen as that attendee. */
    @Test
    fun anAttendeeSeesTheMeetingWithTheirOwnRowAsYou() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("A-Att $run")
        val today = LocalDate.now()
        admin.createMeeting(
            room, admin.myPersonId(), "Invited $run", "${today}T11:00:00", "${today}T12:00:00",
            attendeeIds = listOf(Api(Acceptance.standard).myPersonId()),
        )

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.standard)
        compose.waitForText("Invited $run")
        compose.onNodeWithText("Invited $run").performClick()

        compose.waitForText("Attendees · 1")
        assertTrue(compose.shown(admin.myName()))
        assertTrue(compose.shown("You"))
        // Their own row shows "You", not a status; the status control is M5.
        assertFalse(compose.shown("No response"))
    }

    /** E.32 (navigation to details) and H.70: a meeting between other people, reached from a room card. */
    @Test
    fun aBookingOnARoomCardOpensAMeetingYouHaveNoPartIn() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("A-Other $run")
        val organiser = admin.createPerson("Guest $run")
        val today = LocalDate.now()
        admin.createMeeting(room, organiser, "Theirs $run", "${today}T13:00:00", "${today}T14:00:00")

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Rooms today")
        compose.onNodeWithText("Rooms today").performClick()
        compose.waitForText("A-Other $run")
        compose.onAllNodesWithTextFirst("See today's meetings (1)").performClick()
        compose.waitForText("Theirs $run")
        compose.onNodeWithText("Theirs $run").performClick()

        compose.waitForText("Attendees · 0")
        assertTrue(compose.shown("Guest $run"))
        assertFalse(compose.shown("You"))
    }
}

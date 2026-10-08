package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Live updates against a real environment (design M6; use cases M.111, M.122 and M.123, and the
 * "booked by someone else" case of the webapp's live-updates.spec.ts). See [Acceptance].
 *
 * The other client is the real API, acting as a second signed-in user. The app is already on the
 * screen when the change is made, and nothing here touches the app afterwards: no tap, no
 * navigation, no Back. The only way the change can appear is the AppSync subscription, or the
 * refetch that follows each (re)subscription.
 */
class LiveUpdatesAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private val today: LocalDate = LocalDate.now()

    /** Home is a lazy list, so a row is composed only once scrolled to; it can be scrolled to once its data has arrived. */
    private fun waitForHomeRow(text: String) = compose.waitUntil(30_000) {
        runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }.isSuccess
    }

    private fun openFromHome(account: Acceptance.Account, subject: String) {
        scenario = Acceptance.launchApp()
        compose.signIn(account)
        compose.scrollHomeTo(hasText(subject))
        compose.onAllNodesWithTextFirst(subject).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Attendees", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A meeting booked by another client appears on an open home screen. */
    @Test
    fun aMeetingBookedByAnotherClientAppearsOnHome() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("Z-Live $run")
        val organiser = admin.myPersonId()

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Needs your response")

        admin.createMeeting(room, organiser, "Booked elsewhere $run", "${today}T08:00:00", "${today}T09:00:00")

        waitForHomeRow("Booked elsewhere $run")
    }

    /** M.122 and M.123: an edit, then a cancellation, by another client reach an open meeting. */
    @Test
    fun anOpenMeetingFollowsAnEditAndThenACancellation() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Follow $run"), admin.myPersonId(), "Follow $run", "${today}T08:00:00", "${today}T09:00:00",
        )
        openFromHome(Acceptance.admin, "Follow $run")

        admin.updateMeeting(meeting, admin.createRoom("Z-Moved $run"), admin.myPersonId(), "Followed $run", "${today}T08:00:00", "${today}T09:00:00")
        compose.waitForText("Followed $run")

        admin.cancelMeeting(meeting)
        compose.waitForText("Meeting not found.")
    }

    /** M.111: an attendee's response, made by another client, shows on an open meeting. */
    @Test
    fun anAttendeesResponseShowsOnTheOpenMeeting() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val standard = Api(Acceptance.standard)
        val room = admin.createRoom("Z-Resp $run")
        val meeting = admin.createMeeting(
            room, admin.myPersonId(), "Awaiting $run", "${today}T08:00:00", "${today}T09:00:00",
            attendeeIds = listOf(standard.myPersonId()),
        )
        openFromHome(Acceptance.admin, "Awaiting $run")
        compose.waitForText("No response")
        compose.untilLive {
            admin.updateMeeting(meeting, room, admin.myPersonId(), it, "${today}T08:00:00", "${today}T09:00:00", listOf(standard.myPersonId()))
        }

        standard.respond(meeting, "Going")

        compose.waitForText("Going")
    }
}

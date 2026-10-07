package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * Use cases D.22, D.23 and D.24 against a real environment. See [Acceptance].
 *
 * Each case signs in as a different fixture user, so no case's data reaches another's: D.22's
 * meetings are the admin user's, the standard user has none (D.23), and the no-person user has no
 * Person at all (D.24).
 */
class HomeAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    /** D.22: entry points, and Today/Tomorrow lists of exactly the user's meetings in start-time order. */
    @Test
    fun linkedPersonSeesEntryPointsAndTheirAgendaSortedByStartTime() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val roomId = api.createRoom("Acceptance $run")
        val me = api.myPersonId()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        // Created out of chronological order on purpose.
        api.createMeeting(roomId, me, "Later today $run", "${today}T10:00:00", "${today}T10:30:00")
        api.createMeeting(roomId, me, "Earlier today $run", "${today}T09:00:00", "${today}T09:30:00")
        api.createMeeting(roomId, me, "Tomorrow $run", "${tomorrow}T09:00:00", "${tomorrow}T09:30:00")

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        // The entry points are at the top, so check them before scrolling down to the agenda.
        compose.waitForText("Needs your response")
        listOf("Calendar", "Rooms today", "Add meeting").forEach { compose.onNodeWithText(it).assertExists() }
        compose.scrollHomeTo(hasText("Earlier today $run"))
        val order = listOf("Today", "Earlier today $run", "Later today $run", "Tomorrow", "Tomorrow $run").map { top(it) }
        assertTrue("Agenda out of order: $order", order.zipWithNext().all { (a, b) -> a < b })
    }

    /** D.23: no meetings today or tomorrow shows one empty state, not empty day lists. */
    @Test
    fun noMeetingsShowsTheEmptyState() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.standard)
        compose.waitForText("No meetings today or tomorrow.")
        assertFalse(compose.shown("Today"))
        assertFalse(compose.shown("Tomorrow"))
    }

    /** D.24: no linked Person replaces Calendar and the agenda; the other entry points remain. */
    @Test
    fun noLinkedPersonSeesTheNotSetUpMessage() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.noPerson)
        compose.waitForText(NO_PERSON)
        assertFalse(compose.shown("Calendar"))
        assertFalse(compose.shown("Today"))
        compose.onNodeWithText("Rooms today").assertExists()
        compose.onNodeWithText("Add meeting").assertExists()
    }

    /** Top edge of the one node showing exactly [text]. Unclipped: boundsInRoot is empty (0) for a node scrolled off screen. */
    private fun top(text: String): Float =
        compose.onNode(hasText(text)).fetchSemanticsNode().positionInRoot.y

    private companion object {
        const val NO_PERSON = "Your account hasn't been set up properly — no profile could be found for your sign-in."
    }
}

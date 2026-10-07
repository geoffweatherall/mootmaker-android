package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.data.calendar.startOfWorkWeek
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases G.59 to G.63, G.65 and G.67 against a real environment. See [Acceptance].
 *
 * The meetings are on next week's Monday and Tuesday, which are working days in the future whatever
 * day the suite runs (a weekend shows the week that just ended, and the past may not be bookable).
 * Each case reaches them by tapping Next week once.
 *
 * G.64 (no people) is Robolectric-only, since an environment always has people. G.61 and G.66 are
 * webapp-specific; the Monday to Friday layout is pinned by unit and Robolectric tests.
 */
class CalendarAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val nextMonday: LocalDate = startOfWorkWeek(LocalDate.now()).plusWeeks(1)

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun openCalendarAsAdmin() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Calendar")
        compose.onNodeWithText("Calendar").performClick()
        compose.waitForText("This week")
    }

    private fun nextWeek() {
        compose.onNodeWithContentDescription("Next week").performClick()
    }

    /** G.59, G.62, G.63 and G.65: your own week, sorted, with empty days; weeks move; a row opens its meeting. */
    @Test
    fun yourOwnCalendarListsTheWeeksMeetingsInOrderAndMovesBetweenWeeks() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val room = api.createRoom("A-Cal $run")
        val me = api.myPersonId()
        // Created out of order.
        api.createMeeting(room, me, "Later $run", "${nextMonday}T15:00:00", "${nextMonday}T16:00:00")
        api.createMeeting(room, me, "Earlier $run", "${nextMonday}T09:00:00", "${nextMonday}T10:00:00")

        openCalendarAsAdmin()
        // This week: nothing of ours, and the button that returns to it is disabled.
        assertFalse(compose.shown("Earlier $run"))
        nextWeek()
        compose.waitForText("Earlier $run")
        assertTrue(compose.shown("Later $run"))
        val earlier = compose.onNode(hasText("Earlier $run")).fetchSemanticsNode().positionInRoot.y
        val later = compose.onNode(hasText("Later $run")).fetchSemanticsNode().positionInRoot.y
        assertTrue("Meetings out of order", earlier < later)
        // The days without meetings of ours say so.
        assertTrue(compose.shown("No meetings"))

        compose.onNodeWithText("Earlier $run").performClick()
        compose.waitForText("Attendees · 0")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("Earlier $run")

        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.waitUntil(30_000) { !compose.shown("Earlier $run") }
        nextWeek()
        compose.waitForText("Earlier $run")
        compose.onNodeWithText("This week").performClick()
        compose.waitUntil(30_000) { !compose.shown("Earlier $run") }
    }

    /** G.60: choose another person and see their week, not yours. */
    @Test
    fun theSelectorShowsAnotherPersonsCalendar() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val room = api.createRoom("A-Sel $run")
        val guest = api.createPerson("Guest $run")
        api.createMeeting(room, guest, "Guest meeting $run", "${nextMonday.plusDays(1)}T14:00:00", "${nextMonday.plusDays(1)}T15:00:00")

        openCalendarAsAdmin()
        nextWeek()
        // Next week has loaded once the way back to this week is enabled.
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("This week") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(compose.shown("Guest meeting $run"))

        compose.onNodeWithText(api.myName()).performClick()
        compose.onNodeWithText("Guest $run").performClick()

        compose.waitForText("Guest meeting $run")
        assertTrue(compose.shown("Guest $run"))
    }

    /** G.67: with no linked Person there is no calendar to open. */
    @Test
    fun anAccountWithNoPersonHasNoCalendarEntryPoint() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.noPerson)
        compose.waitForText("Rooms today")
        assertFalse(compose.shown("Calendar"))
    }
}

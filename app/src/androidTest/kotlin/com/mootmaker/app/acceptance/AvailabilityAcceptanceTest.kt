package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasText
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
 * Use cases D.25 and E.26 to E.34 against a real environment. See [Acceptance].
 *
 * Every case creates its own uniquely named rooms through the real API, so the cases are
 * independent of each other and of whatever else the environment holds. Room names start with
 * "A-" so they sort to the top of the list, where the screen has composed them.
 *
 * E.29 (the date picker) and E.30 (no rooms) are not run here: the environment always has the
 * rooms other cases created, and the picker is a platform dialog. Both are covered by Robolectric.
 */
class AvailabilityAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun openAvailabilityAsAdmin() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Rooms today")
        compose.onNodeWithText("Rooms today").performClick()
        compose.waitForText("Room availability")
    }

    /** D.25, E.26, E.32, E.33, E.34: today's rooms from home, each meeting on its own room only, back to back kept apart. */
    @Test
    fun todaysRoomsListEachMeetingOnItsOwnRoomInOrder() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val roomA = api.createRoom("A-One $run")
        val roomB = api.createRoom("A-Two $run")
        val me = api.myPersonId()
        val today = LocalDate.now()
        // The same hour in two rooms, and two back-to-back meetings in room A, created out of order.
        api.createMeeting(roomA, me, "Second $run", "${today}T11:00:00", "${today}T12:00:00")
        api.createMeeting(roomA, me, "First $run", "${today}T10:00:00", "${today}T11:00:00")
        api.createMeeting(roomB, me, "Other room $run", "${today}T10:00:00", "${today}T11:00:00")

        openAvailabilityAsAdmin()
        compose.waitForText("A-One $run")
        compose.waitForText("A-Two $run")
        assertTrue(compose.shown("Capacity 6"))

        // Each card opens independently: open room A's, and room B's meeting stays hidden.
        compose.onAllNodesWithTextFirst("See today's meetings (2)").performClick()
        compose.waitForText("First $run")
        assertTrue(compose.shown("Second $run"))
        assertFalse(compose.shown("Other room $run"))
        val first = compose.onNode(hasText("First $run")).fetchSemanticsNode().boundsInRoot.top
        val second = compose.onNode(hasText("Second $run")).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Back-to-back meetings out of order", first < second)
    }

    /** E.27, E.28, E.31: next and previous day, a room with nothing booked is free all day. */
    @Test
    fun movingBetweenDaysShowsThatDaysScheduleAndFreeRooms() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        val room = api.createRoom("A-Days $run")
        val me = api.myPersonId()
        val tomorrow = LocalDate.now().plusDays(1)
        api.createMeeting(room, me, "Tomorrow $run", "${tomorrow}T09:00:00", "${tomorrow}T10:00:00")

        openAvailabilityAsAdmin()
        compose.waitForText("A-Days $run")
        assertTrue(compose.shown("Free now") || compose.shown("No more meetings today"))

        compose.onNodeWithContentDescription("Next day").performClick()
        compose.waitForText("First: Tomorrow $run at 09:00")
        assertTrue(compose.shown("1 meeting"))

        // Back to today and one day before it: that room has nothing booked, so it is free all day.
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.waitForText("A-Days $run")
        compose.waitUntil(30_000) { compose.shown("Free all day") }
    }
}

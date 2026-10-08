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
import java.time.LocalTime
import java.util.UUID

/**
 * Use cases D.25, E.26 to E.34, E.37 and N.106 against a real environment. See [Acceptance].
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
        // Hours that are not now, whenever the suite runs: a meeting in progress changes what a card shows.
        val hour = if (LocalTime.now().hour in 8..13) 16 else 9
        fun at(h: Int) = "${today}T%02d:00:00".format(h)
        // The same hour in two rooms, and two back-to-back meetings in room A, created out of order.
        api.createMeeting(roomA, me, "Second $run", at(hour + 1), at(hour + 2))
        api.createMeeting(roomA, me, "First $run", at(hour), at(hour + 1))
        api.createMeeting(roomB, me, "Other room $run", at(hour), at(hour + 1))

        openAvailabilityAsAdmin()
        compose.waitForText("A-One $run")
        compose.waitForText("A-Two $run")
        assertTrue(compose.shown("Capacity 6"))

        // Each card opens independently: open room A's, and room B's meeting stays hidden.
        compose.onAllNodesWithTextFirst("See today's meetings (2)").performClick()
        compose.waitForText("First $run")
        assertTrue(compose.shown("Second $run"))
        assertFalse(compose.shown("Other room $run"))
        val first = compose.onNode(hasText("First $run")).fetchSemanticsNode().positionInRoot.y
        val second = compose.onNode(hasText("Second $run")).fetchSemanticsNode().positionInRoot.y
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

    /** E.37: Add meeting from a day other than today opens the form on that day, not today. */
    @Test
    fun addMeetingFromAnotherDayStartsOnThatDay() {
        val viewed = LocalDate.now().plusDays(3)
        openAvailabilityAsAdmin()
        repeat(3) { compose.onNodeWithContentDescription("Next day").performClick() }
        compose.onNodeWithContentDescription("Add meeting").performClick()

        compose.waitForText("Organiser")
        compose.waitForText("$viewed")
    }

    /** N.106: the times in a room's opened meeting list follow your time format. */
    @Test
    fun aRoomsMeetingTimesFollowYourFormat() {
        val run = UUID.randomUUID().toString().take(6)
        val api = Api(Acceptance.admin)
        // "A-0" sorts ahead of the other cases' rooms, so this is the first card with a meeting that day.
        val room = api.createRoom("A-0 Format $run")
        val tomorrow = LocalDate.now().plusDays(1)
        api.createMeeting(room, api.myPersonId(), "Twelve hour $run", "${tomorrow}T09:00:00", "${tomorrow}T10:00:00")
        val original = api.preferences().split("/")
        api.setPreferences(original[0], "AmPm", original[2])
        try {
            openAvailabilityAsAdmin()
            compose.onNodeWithContentDescription("Next day").performClick()
            compose.waitForText("A-0 Format $run")
            compose.onAllNodesWithTextFirst("See tomorrow's meetings (1)").performClick()

            compose.waitForText("Twelve hour $run")
            compose.waitForTextContaining("09:00 AM–10:00 AM")
        } finally {
            api.setPreferences(original[0], original[1], original[2])
        }
    }
}

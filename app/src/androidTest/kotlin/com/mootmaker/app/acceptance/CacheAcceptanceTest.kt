package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
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
 * Use cases 144 to 149: another user changes what this one is viewing, and the app's store of what
 * it has seen follows (mootmaker/designs/android-cache.md). See [Acceptance].
 *
 * User B is the app, signed in as the admin fixture. User A is the standard fixture, making the same
 * API calls the webapp makes; the rooms are set up by the admin, since only an admin may create one.
 * Once B's screen is open, nothing here touches the app except where a case says so: the change can
 * only arrive through the AppSync subscription, or the refetch after a (re)subscription.
 */
class CacheAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private val run = UUID.randomUUID().toString().take(6)
    private val admin = Api(Acceptance.admin)
    private val standard = Api(Acceptance.standard)
    private val today: LocalDate = LocalDate.now()
    private val nextMonday: LocalDate = startOfWorkWeek(today).plusWeeks(1)

    private fun at(date: LocalDate, hour: Int) = "${date}T%02d:00:00".format(hour)

    /** A meeting A organises with B attending, in a room the admin made for this run. */
    private fun meetingForB(room: String, subject: String, date: LocalDate, hour: Int): String =
        standard.createMeeting(room, standard.myPersonId(), subject, at(date, hour), at(date, hour + 1), listOf(admin.myPersonId()))

    private fun renameAsA(meeting: String, room: String, subject: String, date: LocalDate, hour: Int) =
        standard.updateMeeting(meeting, room, standard.myPersonId(), subject, at(date, hour), at(date, hour + 1), listOf(admin.myPersonId()))

    private fun signInAsB() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.admin)
        compose.waitForText("Needs your response")
    }

    private fun openCalendar() {
        compose.onNodeWithText("Calendar").performClick()
        compose.waitForText("This week")
    }

    private fun nextWeek() = compose.onNodeWithContentDescription("Next week").performClick()

    private fun previousWeek() = compose.onNodeWithContentDescription("Previous week").performClick()

    /** uc-144: on B's open calendar, A's booking appears, then its new name, then it goes. */
    @Test
    fun aCalendarFollowsABookingItsRenameAndItsCancellation() {
        val room = admin.createRoom("Z-CalLive $run")
        val anchor = meetingForB(room, "Anchor $run", nextMonday, 9)

        signInAsB()
        openCalendar()
        nextWeek()
        compose.waitForText("Anchor $run")
        compose.untilLive { renameAsA(anchor, room, it, nextMonday, 9) }

        val booked = meetingForB(room, "Booked $run", nextMonday.plusDays(1), 10)
        compose.waitForText("Booked $run")

        renameAsA(booked, room, "Renamed $run", nextMonday.plusDays(1), 10)
        compose.waitForText("Renamed $run")
        assertFalse(compose.shown("Booked $run"))

        standard.cancelMeeting(booked)
        compose.waitForTextToGo("Renamed $run")
    }

    /** uc-145: on B's open room availability, A's booking appears in the room's card, then leaves it. */
    @Test
    fun roomAvailabilityFollowsABookingAndItsCancellation() {
        val name = "Z-AvailLive $run"
        val room = admin.createRoom(name)
        val tomorrow = today.plusDays(1)
        // Late in the day, so a booking made before it becomes the card's "First:".
        val anchor = meetingForB(room, "Evening $run", tomorrow, 17)

        signInAsB()
        compose.onNodeWithText("Rooms today").performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        compose.waitForTextContaining("See tomorrow's meetings")
        compose.scrollClearOfTheBottom(hasScrollToIndexAction(), hasText(name))
        compose.waitForTextContaining("Evening $run")
        compose.untilLive { renameAsA(anchor, room, it, tomorrow, 17) }

        val booked = meetingForB(room, "Morning $run", tomorrow, 9)
        compose.waitForTextContaining("Morning $run")

        standard.cancelMeeting(booked)
        compose.waitForTextToGo("Morning $run")
    }

    /** uc-146: a meeting A moves out of B's open Home leaves it, and is on its new date in B's calendar. */
    @Test
    fun aMeetingMovedAwayLeavesHomeAndIsOnItsNewDate() {
        val room = admin.createRoom("Z-Move $run")
        val anchor = meetingForB(room, "Here $run", today, 8)
        val moving = meetingForB(room, "Moving $run", today, 9)
        // Next week's Wednesday is always beyond the three days Home shows.
        val newDate = nextMonday.plusDays(2)

        signInAsB()
        compose.scrollHomeTo(hasText("Moving $run"))
        compose.untilLive { renameAsA(anchor, room, it, today, 8) }

        renameAsA(moving, room, "Moved $run", newDate, 9)
        compose.waitForHomeToLose("Moving $run")
        assertFalse(compose.shown("Moved $run"))

        openCalendar()
        nextWeek()
        compose.waitForText("Moved $run")
    }

    /**
     * uc-147: a week B has seen and left is changed by A. Back on it, B sees it at once from what the
     * app holds, with no spinner, and then the change.
     */
    @Test
    fun aWeekSeenBeforeShowsAtOnceAndThenTheChangeMadeWhileAway() {
        val room = admin.createRoom("Z-Away $run")
        val meeting = meetingForB(room, "Before $run", nextMonday, 11)

        signInAsB()
        openCalendar()
        nextWeek()
        compose.waitForText("Before $run")
        compose.untilLive { renameAsA(meeting, room, it, nextMonday, 11) }

        // To the week after, which the app has not seen, and A renames the meeting meanwhile.
        nextWeek()
        compose.waitUntil(30_000) { !compose.spinnerShown() }
        renameAsA(meeting, room, "After $run", nextMonday, 11)

        previousWeek()
        compose.waitForIdle()
        assertFalse("a week already held must not show the spinner", compose.spinnerShown())
        assertTrue(
            "the held week draws at once, with the old name or already the new; on screen: ${compose.screenText()}",
            compose.shownContaining("Live check") || compose.shown("After $run"),
        )
        compose.waitForText("After $run")
    }

    /**
     * uc-148: B's open meeting is changed while the app is in the background, where the live socket
     * is closed. On return, the reconnect refetches everything held: the meeting's new name, and its
     * room's, which no broadcast carries.
     */
    @Test
    fun changesMadeWhileInTheBackgroundAreThereOnReturn() {
        val roomName = "Z-Back $run"
        val room = admin.createRoom(roomName)
        val meeting = meetingForB(room, "Open $run", today, 12)

        signInAsB()
        compose.scrollHomeTo(hasText("Open $run"))
        compose.onAllNodesWithTextFirst("Open $run").performClick()
        compose.waitForTextContaining("Attendees")
        compose.untilLive { renameAsA(meeting, room, it, today, 12) }

        scenario.moveToState(Lifecycle.State.CREATED)
        renameAsA(meeting, room, "While away $run", today, 12)
        admin.renameRoom(room, "$roomName renamed")
        scenario.moveToState(Lifecycle.State.RESUMED)

        compose.waitForText("While away $run")
        compose.waitForTextContaining("$roomName renamed")
    }

    /** uc-149: A renames the meeting B has open; the details follow, and Home already has it on return. */
    @Test
    fun oneChangeReachesEveryScreenThatShowsIt() {
        val room = admin.createRoom("Z-Every $run")
        val meeting = meetingForB(room, "First name $run", today, 13)

        signInAsB()
        compose.scrollHomeTo(hasText("First name $run"))
        compose.onAllNodesWithTextFirst("First name $run").performClick()
        compose.waitForTextContaining("Attendees")
        compose.untilLive { renameAsA(meeting, room, it, today, 13) }

        renameAsA(meeting, room, "Second name $run", today, 13)
        compose.waitForText("Second name $run")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertFalse("Home was held all along: no spinner on return", compose.spinnerShown())
        compose.scrollHomeTo(hasText("Second name $run"))
        assertFalse(compose.shown("First name $run"))
    }
}

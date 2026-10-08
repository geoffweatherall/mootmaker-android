package com.mootmaker.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.AppContainer
import com.mootmaker.app.FakeLiveUpdates
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.data.calendar.startOfWorkWeek
import com.mootmaker.data.live.LiveEvent
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakePerson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.concurrent.CountDownLatch

/**
 * The workspace store under the real screens (android-cache.md, "Testing impacts: Flow"): what is
 * requested, and what is on screen while it is. [FakeBackend] counts requests exactly and can hold
 * a response open, which a real environment cannot.
 *
 * It records a request only once its response is released, so "no request" is asserted after the
 * latch is released and the screen has settled, never while a response is held.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class CacheFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val today = LocalDate.now()
    private val monday = startOfWorkWeek(today)
    private val live = FakeLiveUpdates()
    private val backend = FakeBackend().apply {
        otherPeople = listOf(FakePerson("person-2", "Sam Other"))
        meetings = listOf(FakeBackend.meeting("m1", "Stand-up", today, 9))
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var container: AppContainer

    @After
    fun tearDown() {
        backend.holdGraphql?.countDown()
        scenario?.close()
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(5_000) { shown(text, substring) }

    private fun spinnerShown() = compose.onAllNodes(hasContentDescription("Loading")).fetchSemanticsNodes().isNotEmpty()

    private fun progressShown() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty()

    private fun count(operation: String) = backend.requests.count { it == "graphql $operation" }

    private fun graphqlRequests() = backend.requests.count { it.startsWith("graphql ") }

    private fun hold() { backend.holdGraphql = CountDownLatch(1) }

    private fun release() { backend.holdGraphql!!.countDown() }

    /** Lets anything the screen was about to do happen, so that "no request" is checked after it. */
    private fun settle() {
        try {
            compose.waitUntil(600) { false }
        } catch (_: ComposeTimeoutException) {
        }
    }

    private fun signIn() {
        container = useFakeBackend(backend, live)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")
    }

    private fun openStandUp() {
        compose.onAllNodes(hasText("Stand-up")).onFirst().performClick()
    }

    private fun back() = compose.onNodeWithContentDescription("Back").performClick()

    private fun nextDay(times: Int) = repeat(times) { compose.onNodeWithContentDescription("Next day").performClick() }

    private fun broadcast(vararg dates: LocalDate) = runBlocking { live.push(LiveEvent.DaysChanged(dates.map { it.toString() })) }

    private fun openCalendar() {
        compose.onNodeWithText("Calendar").performClick()
        waitForText("Person")
    }

    private fun weekMeetings() {
        backend.meetings = backend.meetings + listOf(
            FakeBackend.meeting("w1", "Week one sync", monday, 11),
            FakeBackend.meeting("w2", "Week two sync", monday.plusWeeks(1), 11),
        )
    }

    /** #22: a meeting reopened from Home makes no MeetingById or Days request and never shows the spinner. */
    @Test
    fun reopeningAMeetingMakesNoRequestAndNeverSpins() {
        signIn()
        openStandUp()
        waitForText("Attendees · 0")
        back()
        waitForText("Stand-up")
        settle()
        val days = count("Days")
        val requests = graphqlRequests()

        openStandUp()
        assertFalse(spinnerShown())
        waitForText("Attendees · 0")
        assertFalse(spinnerShown())
        settle()

        assertEquals(0, count("MeetingById"))
        assertEquals(days, count("Days"))
        assertEquals(requests, graphqlRequests())
    }

    /** #22: a meeting opened from Home renders its subject with no GraphQL request at all. */
    @Test
    fun aMeetingOpenedFromHomeNeedsNoRequest() {
        signIn()
        settle()
        val before = graphqlRequests()

        openStandUp()
        waitForText("Attendees · 0")
        assertTrue(shown("Stand-up"))
        settle()

        assertEquals(before, graphqlRequests())
        assertEquals(0, count("MeetingById"))
    }

    /** #24: a week never fetched spins instead of showing the old week; going back shows the first at once. */
    @Test
    fun aNewWeekSpinsAndAWeekSeenBeforeShowsAtOnce() {
        weekMeetings()
        signIn()
        openCalendar()
        waitForText("Week one sync")

        hold()
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.waitUntil(5_000) { spinnerShown() }
        assertFalse(shown("Week one sync"))
        assertFalse(shown("Week two sync"))
        release()
        waitForText("Week two sync")
        assertFalse(spinnerShown())
        assertFalse(shown("Week one sync"))

        settle()
        val days = count("Days")
        compose.onNodeWithContentDescription("Previous week").performClick()
        assertTrue(shown("Week one sync"))
        assertFalse(spinnerShown())
        assertFalse(shown("Week two sync"))
        settle()
        assertEquals(days, count("Days"))
        assertTrue(shown("Week one sync"))
    }

    /** #24, changing person: another person's meeting in a week already held shows at once, with no request. */
    @Test
    fun changingPersonOnAHeldWeekMakesNoRequest() {
        weekMeetings()
        backend.meetings = backend.meetings + FakeBackend.meeting("s1", "Sam's review", monday, 14, organiserId = "person-2")
        signIn()
        openCalendar()
        waitForText("Week one sync")
        assertFalse(shown("Sam's review"))
        settle()
        val before = graphqlRequests()

        hold()
        compose.onNodeWithText("Pat Example").performClick()
        compose.onAllNodes(hasText("Sam Other")).onFirst().performClick()
        assertTrue(shown("Sam's review"))
        assertFalse(spinnerShown())
        assertFalse(shown("Week one sync"))
        release()
        settle()
        assertTrue(shown("Sam's review"))
        assertEquals(before, graphqlRequests())
    }

    /** #25: a day Home doesn't hold spins, never showing rooms or "no meetings", and a day seen before shows at once. */
    @Test
    fun anUnseenDaySpinsAndADaySeenBeforeShowsAtOnce() {
        signIn()
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        settle()

        hold()
        nextDay(3)
        compose.waitUntil(5_000) { spinnerShown() }
        assertFalse(shown("Boardroom"))
        assertFalse(shown("Atrium"))
        assertFalse(shown("No meetings booked", substring = true))
        release()
        waitForText("Boardroom")
        assertFalse(spinnerShown())
        settle()

        val before = graphqlRequests()
        compose.onNodeWithContentDescription("Previous day").performClick()
        assertTrue(shown("Boardroom"))
        assertFalse(spinnerShown())
        settle()
        assertTrue(shown("Boardroom"))
        assertEquals(before, graphqlRequests())
    }

    /** #23: pausing and resuming the activity (the share sheet's path) makes no GraphQL request. */
    @Test
    fun pausingAndResumingMakesNoRequest() {
        signIn()
        openStandUp()
        waitForText("Attendees · 0")
        settle()
        val before = graphqlRequests()

        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        settle()

        assertTrue(shown("Stand-up"))
        assertEquals(before, graphqlRequests())
    }

    /** #23: stopping and starting the activity (the lock screen) refetches on the new subscription. */
    @Test
    fun stoppingAndStartingRefetchesOnSubscribed() {
        signIn()
        settle()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        backend.meetings = backend.meetings + FakeBackend.meeting("m2", "Added while stopped", today, 11)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        val days = count("Days")

        // The channel reconnects asynchronously and an event with nobody listening is dropped, so
        // keep announcing the subscription until the screen has seen it.
        compose.waitUntil(5_000) {
            runBlocking { live.push(LiveEvent.Subscribed) }
            shown("Added while stopped")
        }
        assertTrue(count("Days") > days)
    }

    /** #22 and the live channel: a change to a day that is held but not shown makes no request until it is shown again. */
    @Test
    fun aChangeToAHeldButUnshownDayWaitsUntilItIsShownAgain() {
        val later = today.plusDays(3)
        backend.meetings = backend.meetings + FakeBackend.meeting("later", "Later meeting", later, 10)
        signIn()
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        nextDay(3)
        waitForText("Later meeting", substring = true)
        back()
        waitForText("Stand-up")
        settle()

        backend.meetings = backend.meetings.map { if (it.id == "later") it.copy(subject = "Renamed meeting") else it }
        val before = graphqlRequests()
        broadcast(later)
        settle()
        assertEquals(before, graphqlRequests())

        hold()
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        nextDay(3)
        assertFalse(spinnerShown())
        waitForText("Later meeting", substring = true)
        assertFalse(shown("Renamed meeting", substring = true))
        release()
        waitForText("Renamed meeting", substring = true)
        assertFalse(shown("Later meeting", substring = true))
        assertTrue(graphqlRequests() > before)
    }

    /** #22 and the live channel: an open meeting follows a change to its day, keeping the old subject up meanwhile. */
    @Test
    fun anOpenMeetingKeepsItsSubjectWhileTheChangeIsFetched() {
        signIn()
        openStandUp()
        waitForText("Attendees · 0")
        settle()

        backend.meetings = backend.meetings.map { it.copy(subject = "Stand-up, renamed", version = it.version + 1) }
        hold()
        broadcast(today)
        settle()
        assertTrue(shown("Stand-up"))
        assertFalse(shown("Stand-up, renamed"))
        assertFalse(spinnerShown())

        release()
        waitForText("Stand-up, renamed")
        assertFalse(shown("Stand-up"))
    }

    /** Identity (android-cache.md Decision 8): signing out and in again refetches rather than serving the old store. */
    @Test
    fun signingOutAndInAgainRefetches() {
        signIn()
        settle()
        val days = count("Days")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()
        waitForText("Create an account")
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")
        settle()

        assertTrue("Days should be requested again after signing in", count("Days") > days)
    }
}

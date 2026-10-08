package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.data.calendar.startOfWorkWeek
import com.mootmaker.testing.FakeBackend
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

/**
 * The whole app — config, sign-in, the home query and navigation — against [FakeBackend].
 * The same journeys run on an emulator against a real environment in the e2e suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AppFlowTest {
    /**
     * When today's meetings start. An hour that is not the current one, so no meeting is in progress
     * (a different, time-dependent room status) whenever the suite runs.
     */
    private val meetingHour = if (java.time.LocalTime.now().hour == 9) 15 else 9

    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend().apply {
        val today = LocalDate.now()
        meetings = listOf(
            FakeBackend.meeting("m1", "Stand-up", today, meetingHour),
            FakeBackend.meeting("m2", "Someone else's meeting", today, 10, organiserId = "person-2"),
            FakeBackend.meeting("m3", "Planning", today.plusDays(1), 11),
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun signInButton() = compose.onNode(hasText("Sign in") and hasClickAction())

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    // Use case B.7 (and D.22's agenda) through the real app wiring.
    @Test
    fun signingInWithTheDemoAccountShowsYourDay() {
        useFakeBackend(backend)
        launch()

        waitForText("demo@mootmaker.com")
        signInButton().performClick()

        waitForText("Stand-up")
        compose.onNodeWithText("Pat Example").assertIsDisplayed()
        compose.onNodeWithText("Planning").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Someone else's meeting")).fetchSemanticsNodes().isEmpty())
        assertTrue(backend.requests.containsAll(listOf("cognito InitiateAuth", "cognito RespondToAuthChallenge", "graphql Reference", "graphql Days")))
    }

    // Use case B.9: the error is shown and nothing past sign-in is reachable.
    @Test
    fun aWrongPasswordKeepsYouOnSignIn() {
        backend.signInError = "NotAuthorizedException" to "Incorrect username or password."
        useFakeBackend(backend)
        launch()

        waitForText("demo@mootmaker.com")
        compose.onNodeWithText("Password").performTextReplacement("wrong")
        signInButton().performClick()

        waitForText("Incorrect username or password.")
        assertTrue("graphql Days" !in backend.requests)
    }

    // Use case B.13: signing out returns to sign-in, and Back can't reach the signed-in screen.
    @Test
    fun signingOutLocksTheAppAgain() {
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()

        waitForText("Create an account")
        assertTrue(backend.requests.contains("cognito RevokeToken"))
        var finishing = false
        scenario!!.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
            finishing = it.isFinishing
        }
        assertTrue("Back should leave the app, not return home", finishing)
    }

    @Test
    fun theSessionSurvivesAnActivityRestart() {
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")

        scenario!!.recreate()

        waitForText("Stand-up")
        assertEquals(1, backend.requests.count { it == "cognito RespondToAuthChallenge" })
    }

    // Use case D.24 through the real app wiring.
    @Test
    fun anAccountWithNoLinkedPersonSeesTheSetUpMessage() {
        backend.personId = null
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()

        waitForText("Your account hasn't been set up properly — no profile could be found for your sign-in.")
    }

    @Test
    fun theEnvironmentCanBeSwitchedFromAbout() {
        useFakeBackend(backend)
        launch()
        waitForText("About")
        compose.onNodeWithText("About").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Version", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val version = compose.onNode(hasText("Version", substring = true))
        repeat(7) { version.performClick() }

        compose.onNodeWithText("Environment name").performTextReplacement("test")
        compose.onNodeWithText("Switch environment").performClick()

        compose.waitUntil(5_000) { "config www.test.mootmaker.com" in backend.requests }
        waitForText("Environment: test")
    }

    // Use cases D.25, E.26, E.27, E.32 and E.33 through the real app wiring: today's rooms from
    // home, a room's meetings opened, then the next day, with each meeting on its own room only.
    @Test
    fun roomsTodayOpensAvailabilityAndTheNextDayCanBeViewed() {
        val today = LocalDate.now()
        backend.meetings = listOf(
            FakeBackend.meeting("a1", "Board sync", today, meetingHour, roomId = "room-1"),
            FakeBackend.meeting("a2", "Atrium chat", today, meetingHour, roomId = "room-2"),
            FakeBackend.meeting("a3", "Tomorrow only", today.plusDays(1), 11, roomId = "room-1"),
        )
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Rooms today")

        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Atrium")
        compose.onNodeWithText("Boardroom").assertIsDisplayed()
        assertTrue("graphql Days" in backend.requests)

        compose.onAllNodes(hasText("See today's meetings (1)")).onFirst().performClick()
        // Rooms sort by name, so the first card is the Atrium: its meeting shows, the Boardroom's doesn't.
        waitForText("Atrium chat")
        assertTrue(compose.onAllNodes(hasText("Board sync")).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("Next day").performClick()
        waitForText("Free all day")
        waitForText("First: Tomorrow only at 11:00")
        // The new day starts collapsed, and the previous day's meetings are gone.
        assertTrue(compose.onAllNodes(hasText("Atrium chat")).fetchSemanticsNodes().isEmpty())
    }

    // Use cases D.22 (rows link to details), H.69 and H.71 through the real app wiring.
    @Test
    fun anAgendaRowOpensItsMeetingDetailsAndBackReturnsHome() {
        val today = LocalDate.now()
        backend.meetings = listOf(
            FakeBackend.meeting("m1", "Stand-up", today, meetingHour, organiserId = "person-2")
                .copy(attendeeIds = listOf("person-1"), responses = mapOf("person-1" to "Going")),
        )
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")

        compose.onNodeWithText("Stand-up").performClick()

        waitForText("Attendees · 1")
        compose.onNodeWithText("Sam Other").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("%02d:00–%02d:00".format(meetingHour, meetingHour + 1)).assertIsDisplayed()
        compose.onNodeWithText("$today").assertIsDisplayed()
        // Opened from Home, the meeting comes from the day Home already holds: no request (#22).
        assertFalse("graphql MeetingById" in backend.requests)

        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Rooms today")
    }

    // Use case H.73: the lookup answers null for an id that doesn't exist.
    @Test
    fun aMeetingThatNoLongerExistsSaysSo() {
        val today = LocalDate.now()
        backend.meetings = listOf(FakeBackend.meeting("m1", "Stand-up", today, meetingHour))
        val container = useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")
        compose.onNodeWithText("Stand-up").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Attendees", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Cancelled by someone else while open: its day is invalidated (the live channel's job), the
        // refetched day no longer holds it, and the lookup by id that follows says it is gone.
        backend.meetings = emptyList()
        container.workspace.invalidateDays(listOf(today))

        waitForText("Meeting not found.")
    }

    // Use cases E.32 (navigation half) and H.68: a booking on a room card opens its details.
    @Test
    fun aBookingOnTheAvailabilityScreenOpensItsMeetingDetails() {
        val today = LocalDate.now()
        backend.meetings = listOf(FakeBackend.meeting("a1", "Board sync", today, meetingHour, roomId = "room-1"))
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Rooms today")
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Atrium")
        compose.onAllNodes(hasText("See today's meetings (1)")).onFirst().performClick()
        waitForText("Board sync")

        compose.onNodeWithText("Board sync").performClick()

        waitForText("Attendees · 0")
        compose.onNodeWithText("Pat Example").assertIsDisplayed()
    }

    // Use cases G.59, G.60 and G.65 through the real app wiring: your own week from home, another
    // person's from the selector, and a meeting row opening its details.
    @Test
    fun theCalendarShowsYourWeekAnotherPersonsAndOpensAMeeting() {
        val monday = startOfWorkWeek(LocalDate.now())
        backend.meetings = listOf(
            FakeBackend.meeting("c1", "Mine on Monday", monday, 9),
            FakeBackend.meeting("c2", "Sam's on Tuesday", monday.plusDays(1), 10, organiserId = "person-2"),
        )
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Calendar")
        compose.onNodeWithText("Calendar").performClick()

        waitForText("Mine on Monday")
        assertTrue(compose.onAllNodes(hasText("Sam's on Tuesday")).fetchSemanticsNodes().isEmpty())
        assertTrue("graphql Days" in backend.requests)

        compose.onNodeWithText("Pat Example").performClick()
        compose.onNodeWithText("Sam Other").performClick()
        waitForText("Sam's on Tuesday")
        assertTrue(compose.onAllNodes(hasText("Mine on Monday")).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Sam's on Tuesday").performClick()
        waitForText("Attendees · 0")
        compose.onNodeWithText("Sam Other").assertIsDisplayed()
    }

    // Use case G.62: Previous and Next week load those weeks.
    @Test
    fun thePreviousAndNextWeekButtonsLoadThoseWeeks() {
        val monday = startOfWorkWeek(LocalDate.now())
        backend.meetings = listOf(
            FakeBackend.meeting("c1", "Next week's", monday.plusWeeks(1), 9),
            FakeBackend.meeting("c2", "Last week's", monday.minusWeeks(1), 9),
        )
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Calendar")
        compose.onNodeWithText("Calendar").performClick()
        waitForText("This week")

        compose.onNodeWithContentDescription("Next week").performClick()
        waitForText("Next week's")
        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.onNodeWithContentDescription("Previous week").performClick()
        waitForText("Last week's")
        compose.onNodeWithText("This week").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Last week's")).fetchSemanticsNodes().isEmpty() }
    }
}

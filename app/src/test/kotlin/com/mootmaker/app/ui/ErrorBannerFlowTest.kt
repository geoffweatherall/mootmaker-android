package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.AppContainer
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakePerson
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
import java.time.LocalTime

/**
 * The README's "Errors" rule on every screen, through the real app wiring against [FakeBackend]:
 * with the content scrolled away from the top, the screen's error is DISPLAYED (in the viewport, not
 * merely in the tree), it can be dismissed, and a failed refresh offers Try again. The window is short
 * (600dp) so forms and lists genuinely scroll, which is what hid the errors in #35.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h600dp")
class ErrorBannerFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val today = LocalDate.now()
    private val invitedHour = if (LocalTime.now().hour == 9) 15 else 9
    private val unreachable = "Couldn't reach Mootmaker. Check your connection and try again."

    private val backend = FakeBackend().apply {
        otherPeople = listOf(FakePerson("person-2", "Sam Other")) + (3..12).map { FakePerson("person-$it", "Guest Number$it") }
        meetings = listOf(
            // Sam invites you, and you haven't answered; many others are invited so the page scrolls.
            FakeBackend.meeting("invite", "Invite me", today, invitedHour, organiserId = "person-2")
                .copy(attendeeIds = listOf("person-1") + (3..12).map { "person-$it" }),
            FakeBackend.meeting("mine", "Mine", today.plusDays(1), 11).copy(attendeeIds = listOf("person-2")),
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var container: AppContainer

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) = compose.waitUntil(5_000) { shown(text, substring) }

    private fun waitForNoText(text: String) = compose.waitUntil(5_000) { !shown(text) }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun launch() {
        container = useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
    }

    private fun signInToHome() {
        launch()
        button("Sign in").performClick()
        waitForText("Needs your response")
    }

    private fun scrollContentToBottom() = repeat(3) {
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
    }

    private fun scrollContentToTop() = repeat(3) {
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeDown() }
    }

    private fun assertDisplayed(vararg messages: String) = messages.forEach { compose.onNodeWithText(it).assertIsDisplayed() }

    private fun waitForIcon(description: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun dismiss() = compose.onNodeWithContentDescription("Dismiss").performClick()

    private fun assertDismissable(message: String) {
        dismiss()
        waitForNoText(message)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Dismiss")).fetchSemanticsNodes().isEmpty())
    }

    private fun openMeeting(subject: String) {
        compose.onAllNodes(hasText(subject)).onFirst().performScrollTo().performClick()
        waitForText("Attendees", substring = true)
    }

    /** Makes the next refresh of whatever is on screen fail, as a dropped connection would. */
    private fun failTheNextRefresh() {
        backend.networkDown = true
        container.workspace.invalidateAll()
    }

    // #35: Add meeting, two refusals at once, with the form scrolled to Save at the bottom.
    @Test
    fun addMeetingShowsEveryRefusalPinnedAboveTheScrolledForm() {
        signInToHome()
        button("Add meeting").performClick()
        waitForText("Organiser")

        button("Save").performScrollTo().performClick()
        waitForText("Please enter a subject.")

        assertDisplayed("Please enter a subject.", "Please select a room.")
        // Pinned: scrolling the form back to the top leaves the messages where they were.
        scrollContentToTop()
        assertDisplayed("Please enter a subject.", "Please select a room.")
        assertDismissable("Please enter a subject.")
        assertFalse(shown("Please select a room."))
    }

    // #35 as reported: Edit meeting, a save the API refuses.
    @Test
    fun editMeetingShowsAServerRefusalAboveTheScrolledForm() {
        signInToHome()
        openMeeting("Mine")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        waitForText("Organiser")
        waitForText("Mine")

        backend.meetings = backend.meetings.map { if (it.id == "mine") it.copy(subject = "Theirs", version = it.version + 1) else it }
        field("Subject").performTextReplacement("Mine, edited")
        button("Save").performScrollTo().performClick()

        val refusal = "Someone else changed this meeting after you opened it"
        waitForText(refusal, substring = true)
        compose.onNode(hasText(refusal, substring = true)).assertIsDisplayed()
    }

    @Test
    fun meetingDetailsShowsAnActionRefusalAboveTheScrolledPage() {
        signInToHome()
        openMeeting("Invite me")
        waitForText("Your response")
        // The invitation is withdrawn behind the screen's back, so answering is refused.
        backend.meetings = backend.meetings.map { it.copy(attendeeIds = emptyList()) }

        button("Maybe").performScrollTo().performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Dismiss")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        assertTrue("graphql RespondToMeeting" in backend.requests)
        dismiss()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Dismiss")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun meetingDetailsShowsAFailedRefreshWithTryAgain() {
        signInToHome()
        openMeeting("Invite me")
        waitForText("Your response")
        scrollContentToBottom()

        failTheNextRefresh()
        waitForText(unreachable)
        assertDisplayed(unreachable)
        compose.onNodeWithText("Try again").assertIsDisplayed()

        backend.networkDown = false
        val before = backend.requests.size
        compose.onNodeWithText("Try again").performClick()
        waitForNoText(unreachable)
        assertTrue("Try again made a new request", backend.requests.size > before)
    }

    @Test
    fun homeShowsAFailedRefreshWithTryAgainAndDismiss() {
        signInToHome()
        scrollContentToBottom()

        failTheNextRefresh()
        waitForText(unreachable)
        assertDisplayed(unreachable)
        compose.onNodeWithText("Try again").assertIsDisplayed()
        assertDismissable(unreachable)
    }

    // Try again while still offline fails again and the banner stays; once the network is back, Try again clears it.
    @Test
    fun homeTryAgainWhileStillOfflineShowsTheNewFailureThenSucceeds() {
        signInToHome()
        failTheNextRefresh()
        waitForText(unreachable)

        compose.onNodeWithText("Try again").performClick()
        waitForText(unreachable)
        assertDisplayed(unreachable)

        backend.networkDown = false
        val beforeSuccess = backend.requests.size
        compose.onNodeWithText("Try again").performClick()
        waitForNoText(unreachable)
        assertTrue(backend.requests.size > beforeSuccess)
    }

    @Test
    fun homeShowsARefusedResponseAboveTheScrolledList() {
        signInToHome()
        waitForText("Invite me")
        backend.meetings = backend.meetings.map { it.copy(attendeeIds = emptyList()) }
        scrollContentToBottom()

        compose.onNode(hasText("Going") and hasClickAction()).performScrollTo().performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Dismiss")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
        assertTrue("graphql RespondToMeeting" in backend.requests)
    }

    @Test
    fun calendarShowsAFailedRefreshWithTryAgain() {
        signInToHome()
        button("Calendar").performClick()
        waitForText("Person")
        scrollContentToBottom()

        failTheNextRefresh()
        waitForText(unreachable)
        assertDisplayed(unreachable)
        backend.networkDown = false
        val before = backend.requests.size
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        waitForNoText(unreachable)
        assertTrue(backend.requests.size > before)
    }

    @Test
    fun availabilityShowsAFailedRefreshWithTryAgain() {
        signInToHome()
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        scrollContentToBottom()

        failTheNextRefresh()
        waitForText(unreachable)
        assertDisplayed(unreachable)
        backend.networkDown = false
        val before = backend.requests.size
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        waitForNoText(unreachable)
        assertTrue(backend.requests.size > before)
    }

    @Test
    fun adminRoomEditorShowsEveryRefusalPinned() {
        backend.isAdmin = true
        signInToHome()
        compose.onNodeWithContentDescription("More options").performClick()
        button("Rooms").performClick()
        waitForText("Manage the rooms available for booking.")
        waitForIcon("Add room")
        compose.onNodeWithContentDescription("Add room").performClick()
        waitForText("Add room")
        field("Capacity").performTextReplacement("1")
        scrollContentToBottom()
        button("Save").performClick()

        waitForText("Name must not be blank.")
        assertDisplayed("Name must not be blank.", "Room capacity must be at least 2.")
        assertDismissable("Name must not be blank.")
    }

    @Test
    fun adminPersonEditorShowsARefusalPinned() {
        backend.isAdmin = true
        signInToHome()
        compose.onNodeWithContentDescription("More options").performClick()
        button("Persons").performClick()
        waitForText("Manage the people who can be booked into meetings.")
        waitForIcon("Add person")
        compose.onNodeWithContentDescription("Add person").performClick()
        waitForText("Add person")
        scrollContentToBottom()
        button("Save").performClick()

        waitForText("Name must not be blank.")
        assertDisplayed("Name must not be blank.")
        assertDismissable("Name must not be blank.")
    }

    @Test
    fun settingsShowsASectionsRefusalPrefixedWithItsNameAndStaysPinned() {
        signInToHome()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Settings")).performClick()
        waitForText("Your name")
        field("Name").performTextReplacement("")
        button("Save name").performScrollTo().performClick()

        waitForText("Your name: Name must not be blank.")
        assertDisplayed("Your name: Name must not be blank.")
        assertEquals("Pat Example", backend.personName)
        assertDismissable("Your name: Name must not be blank.")
    }

    @Test
    fun signInShowsAFailureInTheBannerAboveTheForm() {
        backend.signInError = "NotAuthorizedException" to "Incorrect username or password."
        launch()
        scrollContentToBottom()
        button("Sign in").performScrollTo().performClick()

        waitForText("Incorrect username or password.")
        assertDisplayed("Incorrect username or password.")
        assertDismissable("Incorrect username or password.")
    }

    @Test
    fun signUpShowsARefusalInTheBanner() {
        backend.signUpError = "UsernameExistsException" to "An account with the given email already exists."
        launch()
        button("Create an account").performClick()
        waitForText("Already have an account?")
        field("Name").performTextReplacement("New Person")
        field("Email").performTextReplacement("new@example.com")
        field("Password").performTextReplacement("a-good-pw-123")
        button("Sign up").performScrollTo().performClick()

        waitForText("An account with the given email already exists.")
        assertDisplayed("An account with the given email already exists.")
        assertDismissable("An account with the given email already exists.")
    }
}

package com.mootmaker.app.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakePerson
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Use cases D.107, H.108 and O.112 to O.120 through the real app wiring, against [FakeBackend].
 * Meetings use an hour that is not the current one, so none is in progress whenever the suite runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class EditCancelRespondFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val today = LocalDate.now()
    private val invitedHour = if (LocalTime.now().hour == 9) 15 else 9

    private val backend = FakeBackend().apply {
        otherPeople = listOf(FakePerson("person-2", "Sam Other"))
        meetings = listOf(
            // Sam invites you, and you haven't answered.
            FakeBackend.meeting("invite", "Invite me", today, invitedHour, organiserId = "person-2").copy(attendeeIds = listOf("person-1")),
            // You organise this one; Sam is invited.
            FakeBackend.meeting("mine", "Mine", today.plusDays(1), 11).copy(attendeeIds = listOf("person-2")),
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { shown(text, substring) }

    private fun waitForNoText(text: String) = compose.waitUntil(5_000) { !shown(text) }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun signInToHome() {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        button("Sign in").performClick()
        waitForText("Needs your response")
    }

    private fun openMeeting(subject: String) {
        compose.onAllNodes(hasText(subject)).onFirst().performClick()
        waitForText("Attendees", substring = true)
    }

    private fun hasIcon(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    // Use case D.107: an unanswered invitation is listed with one-tap answers, and answering clears it.
    @Test
    fun answeringAnInvitationFromHomeClearsItFromNeedsYourResponse() {
        signInToHome()
        waitForText("Invite me")
        assertTrue(shown("Nothing waiting", substring = true).not())
        // The agenda row shows your own response too.
        waitForText("No response", substring = true)

        button("Going").performClick()

        waitForText("Nothing waiting on a response between", substring = true)
        assertEquals("Going", backend.meetings.first { it.id == "invite" }.responses["person-1"])
        assertTrue("graphql RespondToMeeting" in backend.requests)
        waitForText("· Going", substring = true)
    }

    // Use case D.107: the window is named, and Search further ahead widens it.
    @Test
    fun theRangeIsNamedAndSearchFurtherAheadWidensIt() {
        signInToHome()
        val today = LocalDate.now()
        waitForText("· ", substring = true)
        button("Search further ahead").performClick()
        compose.waitUntil(5_000) { backend.requests.count { it == "graphql Days" } >= 2 }
        assertTrue(shown("Needs your response"))
    }

    // Use case H.108: an attendee has a three-way control on the details, set through the API.
    @Test
    fun anAttendeeAnswersFromTheMeetingDetails() {
        signInToHome()
        openMeeting("Invite me")
        waitForText("Your response")

        button("Maybe").performClick()

        compose.waitUntil(5_000) { backend.meetings.first { it.id == "invite" }.responses["person-1"] == "Maybe" }
        // The organiser has no control of their own.
        assertTrue(!hasIcon("Edit meeting"))
        assertTrue(!hasIcon("Cancel meeting"))
    }

    @Test
    fun theOrganiserHasNoResponseControl() {
        signInToHome()
        openMeeting("Mine")
        assertTrue(!shown("Your response"))
        assertTrue(hasIcon("Edit meeting"))
        assertTrue(hasIcon("Cancel meeting"))
    }

    // Use case O.115: someone who is neither the organiser nor an admin sees no Edit or Cancel.
    @Test
    fun anAttendeeWhoIsNotAnAdminSeesNoEditOrCancel() {
        signInToHome()
        openMeeting("Invite me")
        assertTrue(!hasIcon("Edit meeting"))
        assertTrue(!hasIcon("Cancel meeting"))
    }

    // Use case O.114: an admin edits a meeting they don't organise.
    @Test
    fun anAdminSeesEditAndCancelOnSomeoneElsesMeeting() {
        backend.isAdmin = true
        signInToHome()
        openMeeting("Invite me")
        waitForText("Your response")
        assertTrue(hasIcon("Edit meeting"))
        assertTrue(hasIcon("Cancel meeting"))
    }

    // Use cases O.112 and O.121: the form opens on the meeting; saving shows the change on the details.
    @Test
    fun editingTheSubjectSavesAndShowsOnTheDetails() {
        signInToHome()
        openMeeting("Mine")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        waitForText("Organiser")
        // The form starts from the meeting as it is.
        waitForText("Mine")

        compose.onNode(hasText("Subject") and hasSetTextAction()).performTextReplacement("Renamed")
        button("Save").performClick()

        waitForText("Renamed")
        waitForText("Attendees · 1")
        val saved = backend.meetings.first { it.id == "mine" }
        assertEquals("Renamed", saved.subject)
        assertEquals(2, saved.version)
        assertEquals(listOf("person-2"), saved.attendeeIds)
        assertTrue("graphql UpdateMeeting" in backend.requests)
    }

    // The version the form was opened at goes back with the save: an edit made from a stale copy is refused.
    @Test
    fun anEditMadeFromAStaleCopyIsRefusedAndNothingIsSaved() {
        signInToHome()
        openMeeting("Mine")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        waitForText("Organiser")
        waitForText("Mine")

        // Someone else changes it while the form is open.
        backend.meetings = backend.meetings.map { if (it.id == "mine") it.copy(subject = "Theirs", version = it.version + 1) else it }
        compose.onNode(hasText("Subject") and hasSetTextAction()).performTextReplacement("Mine, edited")
        button("Save").performClick()

        waitForText("Someone else changed this meeting after you opened it", substring = true)
        assertEquals("Theirs", backend.meetings.first { it.id == "mine" }.subject)
    }

    // Use case O.117: the confirmation names the meeting about to be deleted, and confirming removes it.
    @Test
    fun cancellingAMeetingNamesItThenRemovesIt() {
        signInToHome()
        openMeeting("Mine")
        compose.onNodeWithContentDescription("Cancel meeting").performClick()

        waitForText("Cancel this meeting?")
        assertTrue(shown("This permanently deletes \"Mine\" for every attendee. This can't be undone."))
        button("Cancel meeting").performClick()

        // Back on home, where the meeting is gone.
        waitForText("Needs your response")
        waitForNoText("Mine")
        assertTrue(backend.meetings.none { it.id == "mine" })
    }

    @Test
    fun keepingTheMeetingChangesNothing() {
        signInToHome()
        openMeeting("Mine")
        compose.onNodeWithContentDescription("Cancel meeting").performClick()
        waitForText("Cancel this meeting?")

        button("Keep meeting").performClick()

        waitForNoText("Cancel this meeting?")
        assertTrue(backend.meetings.any { it.id == "mine" })
        assertTrue("graphql CancelMeeting" !in backend.requests)
    }

    // Use case O.119: cancelling a meeting someone else already deleted says so, gracefully.
    @Test
    fun cancellingAMeetingThatIsAlreadyGoneSaysSo() {
        signInToHome()
        openMeeting("Mine")
        backend.meetings = backend.meetings.filter { it.id != "mine" }
        compose.onNodeWithContentDescription("Cancel meeting").performClick()
        waitForText("Cancel this meeting?")

        button("Cancel meeting").performClick()

        waitForText("This meeting no longer exists - it may have been deleted.")
    }
}

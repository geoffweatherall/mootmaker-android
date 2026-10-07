package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases D.107, H.108 and O.112 to O.121 against a real environment. See [Acceptance]. Every case
 * makes its own uniquely named room and meetings through the real API, and checks the outcome there
 * as well as on screen.
 *
 * O.116 (the API refusing a caller who is neither organiser nor admin) is the API's own acceptance
 * case: nothing in the app can send that request. O.120 (past and in-progress meetings are as
 * editable as upcoming ones) has no app-side rule to test: Edit and Cancel check nobody's clock.
 * O.119's two simultaneous admins is staged by cancelling through the API between the app's
 * confirmation and its tap.
 */
class MeetingChangeAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private val today: LocalDate = LocalDate.now()

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun hasIcon(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntilGone(text: String) = compose.waitUntil(30_000) { !compose.shown(text) }

    private fun openFromHome(account: Acceptance.Account, subject: String) {
        scenario = Acceptance.launchApp()
        compose.signIn(account)
        compose.scrollHomeTo(hasText(subject))
        compose.onAllNodesWithTextFirst(subject).performClick()
        waitForTextContaining("Attendees")
    }

    private fun waitForTextContaining(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** D.107: an unanswered invitation has one-tap answers on home, and answering clears it from the list. */
    @Test
    fun answeringAnInvitationFromHomeClearsItFromNeedsYourResponse() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val standardId = Api(Acceptance.standard).myPersonId()
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Resp $run"), admin.myPersonId(), "Respond $run", "${today}T08:00:00", "${today}T09:00:00",
            attendeeIds = listOf(standardId),
        )

        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.standard)
        val going = hasContentDescription("Going for Respond $run")
        compose.scrollHomeTo(going)
        assertEquals("NoResponse", admin.responseOf(meeting, standardId))

        compose.onNode(going).performClick()

        compose.waitUntil(30_000) { compose.onAllNodes(going).fetchSemanticsNodes().isEmpty() }
        assertEquals("Going", admin.responseOf(meeting, standardId))
    }

    /** H.108: the attendee's own three-way control on the details, persisted by the API. */
    @Test
    fun anAttendeeAnswersFromTheMeetingDetails() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val standardId = Api(Acceptance.standard).myPersonId()
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Det $run"), admin.myPersonId(), "Details $run", "${today}T08:00:00", "${today}T09:00:00",
            attendeeIds = listOf(standardId),
        )

        openFromHome(Acceptance.standard, "Details $run")
        compose.waitForText("Your response")
        button("Maybe").performClick()

        compose.waitUntil(30_000) { admin.responseOf(meeting, standardId) == "Maybe" }
        // O.115: they are neither the organiser nor an admin, so they have no Edit or Cancel.
        assertFalse(hasIcon("Edit meeting"))
        assertFalse(hasIcon("Cancel meeting"))
    }

    /** O.112 and O.121: the organiser edits the subject, and the change shows on the details. */
    @Test
    fun theOrganiserEditsTheSubject() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Edit $run"), admin.myPersonId(), "Before $run", "${today}T08:00:00", "${today}T09:00:00",
        )

        openFromHome(Acceptance.admin, "Before $run")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        compose.waitForText("Organiser")
        // The form opens on the meeting as it is.
        compose.waitForText("Before $run")
        compose.onNode(hasText("Subject") and hasSetTextAction()).performTextReplacement("After $run")
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()

        compose.waitForText("After $run")
        compose.waitForText("Attendees · 0")
        assertEquals("After $run", admin.subjectOf(meeting))
    }

    /** O.113: lengthening a meeting into the time it already holds in its own room is not a clash. */
    @Test
    fun aMeetingCanBeExtendedWithinItsOwnRoom() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Own $run"), admin.myPersonId(), "Extend $run", "${today}T08:00:00", "${today}T09:00:00",
        )

        openFromHome(Acceptance.admin, "Extend $run")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        compose.waitForText("Organiser")
        compose.waitForText("Extend $run")
        compose.onNode(hasText("09:00") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("10:00") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("10:00") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()

        compose.waitForText("08:00–10:00")
        assertEquals("${today}T10:00:00", admin.meeting(meeting)!!["endTime"]!!.jsonPrimitive.content)
    }

    /** O.114 and O.118: an admin edits and cancels a meeting they neither organise nor attend by choice of the organiser. */
    @Test
    fun anAdminCancelsAMeetingSomeoneElseOrganises() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val organiser = admin.createPerson("Organiser $run")
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Adm $run"), organiser, "Theirs $run", "${today}T08:00:00", "${today}T09:00:00",
            attendeeIds = listOf(admin.myPersonId()),
        )

        openFromHome(Acceptance.admin, "Theirs $run")
        assertTrue(hasIcon("Edit meeting"))
        compose.onNodeWithContentDescription("Cancel meeting").performClick()
        compose.waitForText("Cancel this meeting?")
        button("Cancel meeting").performClick()

        compose.waitForText("Needs your response")
        waitUntilGone("Theirs $run")
        assertNull(admin.meeting(meeting))
    }

    /** O.117: the confirmation names the meeting about to be deleted; keeping it changes nothing. */
    @Test
    fun theCancelConfirmationNamesTheMeetingAndKeepingItChangesNothing() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Keep $run"), admin.myPersonId(), "Keep $run", "${today}T08:00:00", "${today}T09:00:00",
        )

        openFromHome(Acceptance.admin, "Keep $run")
        compose.onNodeWithContentDescription("Cancel meeting").performClick()
        compose.waitForText("This permanently deletes \"Keep $run\" for every attendee. This can't be undone.")
        button("Keep meeting").performClick()

        waitUntilGone("Cancel this meeting?")
        assertEquals("Keep $run", admin.subjectOf(meeting))
    }

    /** O.119: cancelling a meeting that has just been cancelled elsewhere says it no longer exists. */
    @Test
    fun cancellingAMeetingThatIsAlreadyGoneSaysSo() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val meeting = admin.createMeeting(
            admin.createRoom("Z-Gone $run"), admin.myPersonId(), "Gone $run", "${today}T08:00:00", "${today}T09:00:00",
        )

        openFromHome(Acceptance.admin, "Gone $run")
        compose.onNodeWithContentDescription("Cancel meeting").performClick()
        compose.waitForText("Cancel this meeting?")
        admin.cancelMeeting(meeting)
        // Live updates (M6) can reach the open screen before this click. Then the screen has already
        // dropped the dialog and says "Meeting not found.", so the click may find nothing to press.
        // Either way the person is told the meeting is gone, which is what this case is about.
        runCatching { button("Cancel meeting").performClick() }

        compose.waitUntil(30_000) {
            compose.shown("This meeting no longer exists - it may have been deleted.") || compose.shown("Meeting not found.")
        }
    }

    /** An edit made from a copy someone else has since changed is refused, and theirs stays. */
    @Test
    fun anEditFromAStaleCopyIsRefused() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("Z-Stale $run")
        val me = admin.myPersonId()
        val meeting = admin.createMeeting(room, me, "Original $run", "${today}T08:00:00", "${today}T09:00:00")

        openFromHome(Acceptance.admin, "Original $run")
        compose.onNodeWithContentDescription("Edit meeting").performClick()
        compose.waitForText("Organiser")
        compose.waitForText("Original $run")
        // Someone else edits it while the form is open.
        admin.updateMeeting(meeting, room, me, "Theirs $run", "${today}T08:00:00", "${today}T09:00:00")
        compose.onNode(hasText("Subject") and hasSetTextAction()).performTextReplacement("Mine $run")
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()

        waitForTextContaining("Someone else changed this meeting after you opened it")
        assertEquals("Theirs $run", admin.subjectOf(meeting))
    }
}

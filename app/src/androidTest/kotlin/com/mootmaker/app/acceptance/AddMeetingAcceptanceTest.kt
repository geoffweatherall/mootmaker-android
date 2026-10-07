package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * Use cases F.38 to F.56 against a real environment. See [Acceptance]. Every case makes its own
 * uniquely named room. F.40 and F.41 (default times, quarter-hour menu) and F.54 (cache staleness)
 * are covered by unit tests: what the default is depends on the clock, and the cache is invisible.
 */
class AddMeetingAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun openForm(account: Acceptance.Account = Acceptance.admin) {
        scenario = Acceptance.launchApp()
        compose.signIn(account)
        compose.waitForText("Add meeting")
        compose.onNodeWithText("Add meeting").performClick()
        compose.waitForText("Organiser")
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun pick(label: String, option: String) {
        field(label).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(option) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(option) and hasClickAction()).performClick()
    }

    private fun typeSubject(subject: String) = compose.onNode(hasText("Subject") and hasSetTextAction()).performTextInput(subject)

    private fun save() = compose.onNode(hasText("Save") and hasClickAction()).performClick()

    /** F.38 and F.39: the organiser is you, and booking lands on the new meeting's details. */
    @Test
    fun aBookedMeetingOpensItsDetailsWithYouAsOrganiser() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("A-Add $run")

        openForm()
        compose.waitForText(admin.myName())
        typeSubject("Booked $run")
        pick("Room", "A-Add $run (capacity 6)")
        save()

        compose.waitForText("Attendees · 0")
        assertTrue(compose.shown("Booked $run"))
        assertTrue(compose.shown("A-Add $run"))
        assertTrue(compose.shown("${LocalDate.now()}"))
        // You organise it, so your own row says "You".
        assertTrue(compose.shown("You"))
    }

    /** F.44: choosing an attendee removes them from the organiser menu. */
    @Test
    fun anAttendeeIsNoLongerOfferedAsOrganiser() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val guest = "Guest $run"
        admin.createPerson(guest)

        openForm()
        field("Attendees").performClick()
        compose.waitForText(guest)
        compose.onNodeWithText(guest).performClick()
        compose.onNodeWithText("Done").performClick()
        field("Organiser").performClick()
        compose.waitForText(admin.myName())
        // The one clickable node showing the guest's name is the Attendees field itself.
        assertTrue(compose.onAllNodes(hasText(guest) and hasClickAction()).fetchSemanticsNodes().size == 1)
    }

    /** F.46, F.47 and F.51: nothing filled in lists every problem together, and nothing is booked. */
    @Test
    fun savingWithNothingFilledInListsEveryProblem() {
        openForm()
        save()

        compose.waitForText("Please enter a subject.")
        assertTrue(compose.shown("Please select a room."))
        assertFalse(compose.shown("Attendees · 0"))
    }

    /** F.49: a room too small for the people. */
    @Test
    fun aRoomTooSmallForThePeopleIsRejected() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("A-Tiny $run", capacity = 2)
        val guest = "Guest $run"
        admin.createPerson(guest)

        openForm()
        typeSubject("Crowded $run")
        field("Attendees").performClick()
        compose.waitForText(guest)
        compose.onNodeWithText(guest).performClick()
        compose.onNodeWithText(Api(Acceptance.standard).myName()).performClick()
        compose.onNodeWithText("Done").performClick()
        pick("Room", "A-Tiny $run (capacity 2)")
        save()

        compose.waitForText("The room does not have enough capacity for all attendees.")
    }

    /** F.50: the room is already booked for the default time. */
    @Test
    fun aRoomAlreadyBookedForTheTimeIsRejected() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("A-Busy $run")
        val today = LocalDate.now()
        admin.createMeeting(room, admin.myPersonId(), "Taken $run", "${today}T00:00:00", "${today}T23:45:00")

        openForm()
        typeSubject("Clash $run")
        pick("Room", "A-Busy $run (capacity 6)")
        save()

        compose.waitForText("The room already has a meeting scheduled during that time range.")
    }

    /** F.53: Suggest a room fills the Room field. Which room is the API's ranking, covered by its own tests. */
    @Test
    fun suggestARoomFillsTheRoomField() {
        val run = UUID.randomUUID().toString().take(6)
        Api(Acceptance.admin).createRoom("A-Sug $run")

        openForm()
        assertFalse(compose.shown("(capacity"))
        compose.onNodeWithText("Suggest a room").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("(capacity", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** F.55: Back discards the form. */
    @Test
    fun backDiscardsTheForm() {
        openForm()
        typeSubject("Never saved")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("Rooms today")
        assertFalse(compose.shown("Never saved"))
    }
}

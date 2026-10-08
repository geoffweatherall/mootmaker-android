package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Use cases F.38 to F.56 and N.103 against a real environment. See [Acceptance]. Every case makes its own
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

    // The form is taller than an emulator screen: scroll to a control before touching it.
    private fun field(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    private fun pick(label: String, option: String) {
        field(label).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(option) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(option) and hasClickAction()).performScrollTo().performClick()
    }

    private fun typeSubject(subject: String) = compose.onNode(hasText("Subject") and hasSetTextAction()).performTextInput(subject)

    private fun save() = compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()

    /** F.38 and F.39: the organiser is you, and booking lands on the new meeting's details. */
    @Test
    fun aBookedMeetingOpensItsDetailsWithYouAsOrganiser() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("Z-Add $run")

        openForm()
        compose.waitForText(admin.myName())
        typeSubject("Booked $run")
        pick("Room", "Z-Add $run (capacity 6)")
        save()

        compose.waitForText("Attendees · 0")
        assertTrue(compose.shown("Booked $run"))
        assertTrue(compose.shown("Z-Add $run"))
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
        admin.createRoom("Z-Tiny $run", capacity = 2)
        val guest = "Guest $run"
        admin.createPerson(guest)

        openForm()
        typeSubject("Crowded $run")
        field("Attendees").performClick()
        compose.waitForText(guest)
        compose.onNodeWithText(guest).performClick()
        compose.onNodeWithText(Api(Acceptance.standard).myName()).performClick()
        compose.onNodeWithText("Done").performClick()
        pick("Room", "Z-Tiny $run (capacity 2)")
        save()

        compose.waitForText("The room does not have enough capacity for all attendees.")
    }

    /** F.50: the room is already booked for the default time. */
    @Test
    fun aRoomAlreadyBookedForTheTimeIsRejected() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = admin.createRoom("Z-Busy $run")
        val today = LocalDate.now()
        admin.createMeeting(room, admin.myPersonId(), "Taken $run", "${today}T00:00:00", "${today}T23:45:00")

        openForm()
        typeSubject("Clash $run")
        pick("Room", "Z-Busy $run (capacity 6)")
        save()

        compose.waitForText("The room already has a meeting scheduled during that time range.")
    }

    /** F.53: Suggest a room fills the Room field. Which room is the API's ranking, covered by its own tests. */
    @Test
    fun suggestARoomFillsTheRoomField() {
        openForm()
        assertFalse(compose.shown("(capacity"))
        compose.onNodeWithText("Suggest a room").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("(capacity", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** F.42: an end before the start, then an end equal to it, are both refused, and nothing is booked. */
    @Test
    fun anEndAtOrBeforeTheStartIsRefused() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("Z-Order $run")

        openForm()
        typeSubject("Backwards $run")
        pick("Room", "Z-Order $run (capacity 6)")
        compose.pickFromMenu("Start time", "14:00")
        compose.pickFromMenu("End time", "10:00")
        save()
        compose.waitForText("End time must be after the start time.")

        compose.onNodeWithText("Dismiss").performClick()
        compose.pickFromMenu("End time", "14:00")
        save()
        compose.waitForText("End time must be after the start time.")
        assertFalse("Backwards $run" in admin.subjectsOn("${LocalDate.now()}"))
    }

    /**
     * F.43: a start late in the evening and an end after midnight. The form books one date, so the
     * pair reaches the API as an end before the start on that date, and is refused as one. The API's
     * own SpansMultipleDays rule (two calendar dates) can't be asked for from the form.
     */
    @Test
    fun aMeetingAcrossMidnightIsRefused() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("Z-Night $run")

        openForm()
        typeSubject("Overnight $run")
        pick("Room", "Z-Night $run (capacity 6)")
        compose.pickFromMenu("Start time", "23:45")
        compose.pickFromMenu("End time", "00:15")
        save()

        compose.waitForText("End time must be after the start time.")
        assertFalse("Overnight $run" in admin.subjectsOn("${LocalDate.now()}"))
    }

    /** F.45: the organiser is never offered as an attendee; forced through the API, the pair is refused. */
    @Test
    fun theOrganiserCannotAlsoAttend() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        // Names that sort next to each other, so when one is on screen in the picker the other would be.
        val organiser = "0-$run Organiser"
        val other = "0-$run Other"
        val organiserId = admin.createPerson(organiser)
        admin.createPerson(other)
        val room = admin.createRoom("Z-Both $run")

        openForm()
        compose.pickFromMenu("Organiser", organiser)
        field("Attendees").performClick()
        compose.waitForText("Done")
        compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog())).performScrollToNode(hasText(other))
        assertTrue(compose.shown(other))
        assertFalse(compose.onAllNodes(hasText(organiser) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Done").performClick()

        val refused = runCatching {
            admin.createMeeting(room, organiserId, "Both $run", "${LocalDate.now()}T10:00:00", "${LocalDate.now()}T11:00:00", listOf(organiserId))
        }.exceptionOrNull()
        assertTrue("Expected OrganiserIsAttendee, got $refused", refused?.message?.contains("OrganiserIsAttendee") == true)
    }

    /** F.48: an account with no linked Person starts with no organiser, and saving without one is refused. */
    @Test
    fun noOrganiserIsRefused() {
        val run = UUID.randomUUID().toString().take(6)
        Api(Acceptance.admin).createRoom("Z-NoOrg $run")

        openForm(Acceptance.noPerson)
        typeSubject("Unowned $run")
        pick("Room", "Z-NoOrg $run (capacity 6)")
        save()

        compose.waitForText("Please select an organiser.")
        assertFalse("Unowned $run" in Api(Acceptance.admin).subjectsOn("${LocalDate.now()}"))
    }

    /**
     * N.103: on US dates and a 12-hour clock, the form offers times that way, and the meeting it books
     * holds the same naive time a default-format account would have booked.
     */
    @Test
    fun aMeetingBookedInYourOwnFormatStoresTheSameTime() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        admin.createRoom("Z-Usa $run")
        val original = admin.preferences().split("/")
        admin.setPreferences("Usa", "AmPm", original[2])
        try {
            val today = LocalDate.now()
            openForm()
            compose.waitForText(today.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")))
            typeSubject("American $run")
            pick("Room", "Z-Usa $run (capacity 6)")
            compose.pickFromMenu("Start time", "02:00 PM")
            compose.pickFromMenu("End time", "03:30 PM")
            save()

            compose.waitForText("Attendees · 0")
            compose.waitForTextContaining("02:00 PM–03:30 PM")
            assertEquals("${today}T14:00:00" to "${today}T15:30:00", admin.timesOn("$today", "American $run"))
        } finally {
            admin.setPreferences(original[0], original[1], original[2])
        }
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

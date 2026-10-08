package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakePerson
import com.mootmaker.testing.FakeRoom
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

/** Use cases L.89, P.124 to P.131 and Q.132 to Q.142 through the real app wiring, against [FakeBackend]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AdminFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend().apply {
        isAdmin = true
        otherPeople = listOf(
            FakePerson("person-2", "Sam Other", linkedEmails = listOf("sam@example.com")),
            FakePerson("person-3", "Guest Gale"),
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun signInAndOpenMenu() {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { shown("Rooms today") }
        compose.onNodeWithContentDescription("More options").performClick()
    }

    private fun open(screen: String) {
        signInAndOpenMenu()
        compose.onNode(hasText(screen) and hasClickAction()).performClick()
        waitForText(if (screen == "Rooms") "Manage the rooms available for booking." else "Manage the people who can be booked into meetings.")
    }

    // L.89, P.124, Q.132: a standard user is not offered either screen.
    @Test
    fun aStandardUserIsNotOfferedTheAdminScreens() {
        backend.isAdmin = false
        signInAndOpenMenu()
        waitForText("Settings")
        assertFalse(shown("Rooms"))
        assertFalse(shown("Persons"))
    }

    // P.125: an added room appears in the list.
    @Test
    fun addingARoomListsIt() {
        open("Rooms")
        compose.onNodeWithContentDescription("Add room").performClick()
        waitForText("Add room")
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Summit")
        compose.onNode(hasText("Capacity") and hasSetTextAction()).performTextReplacement("8")
        compose.onNodeWithContentDescription("Violet").performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        waitForText("Manage the rooms available for booking.")
        waitForText("Summit")
        assertTrue(shown("Capacity 8"))
        assertTrue(backend.rooms.any { it.name == "Summit" && it.capacity == 8 && it.color == "Violet" })
    }

    // P.126 and P.127: the API's refusals show in the dialog, which stays open.
    @Test
    fun aBlankNameAndATooSmallCapacityAreRefused() {
        open("Rooms")
        compose.onNodeWithContentDescription("Add room").performClick()
        waitForText("Add room")
        compose.onNode(hasText("Capacity") and hasSetTextAction()).performTextReplacement("1")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        waitForText("Name must not be blank.")
        assertTrue(shown("Room capacity must be at least 2."))
        assertEquals(2, backend.rooms.size)
    }

    // P.128: an edit is saved and listed.
    @Test
    fun editingARoomSavesIt() {
        open("Rooms")
        compose.onNodeWithContentDescription("Edit Boardroom").performClick()
        waitForText("Edit room")
        compose.onNode(hasText("Boardroom") and hasSetTextAction()).performTextReplacement("Board Room")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        // The new name is already in the field, so wait for the editor to close back to the list.
        waitForText("Manage the rooms available for booking.")
        waitForText("Board Room")
        assertEquals("Board Room", backend.rooms.first { it.id == "room-1" }.name)
    }

    // P.130 and P.131: a room with no upcoming meeting goes; one with an upcoming meeting is refused.
    @Test
    fun removingARoomWithUpcomingMeetingsIsRefused() {
        backend.rooms = listOf(FakeRoom("room-1", "Boardroom"), FakeRoom("room-2", "Atrium"))
        backend.meetings = listOf(FakeBackend.meeting("m1", "Planning", LocalDate.now().plusDays(1), 10, roomId = "room-1"))
        open("Rooms")

        compose.onNodeWithContentDescription("Remove Boardroom").performClick()
        waitForText("Remove Boardroom?")
        inDialog("Remove room").performClick()
        waitForText("This room has one or more meetings booked from today onward - reassign or cancel them before deleting it.")
        inDialog("Cancel").performClick()

        compose.onNodeWithContentDescription("Remove Atrium").performClick()
        waitForText("Remove Atrium?")
        inDialog("Remove room").performClick()
        compose.waitUntil(5_000) { !shown("Atrium") }
        assertEquals(listOf("room-1"), backend.rooms.map { it.id })
    }

    // Q.137: emails for linked people, an Admin badge, and "Not signed up yet" for a guest.
    @Test
    fun personsShowEmailsAdminBadgeAndGuests() {
        open("Persons")
        waitForText("Sam Other")
        assertTrue(shown("sam@example.com"))
        assertTrue(shown("Admin"))
        assertTrue(shown("Not signed up yet"))
    }

    // The filter matches a name or an email.
    @Test
    fun theFilterMatchesNamesAndEmails() {
        open("Persons")
        waitForText("Sam Other")
        compose.onNode(hasText("Filter") and hasSetTextAction()).performTextReplacement("SAM@")
        compose.waitUntil(5_000) { !shown("Guest Gale") }
        assertTrue(shown("Sam Other"))
        compose.onNode(hasText("Filter") and hasSetTextAction()).performTextReplacement("nobody")
        waitForText("No people match that filter.")
    }

    // Q.133 and Q.134: a new guest is listed; a blank name and a duplicate are refused.
    @Test
    fun addingAPersonListsThemAndRefusesBlanksAndDuplicates() {
        open("Persons")
        compose.onNodeWithContentDescription("Add person").performClick()
        waitForText("Add person")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        waitForText("Name must not be blank.")

        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("sam other")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        waitForText("A person with this name already exists.")

        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Robin New")
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        waitForText("Manage the people who can be booked into meetings.")
        waitForText("Robin New")
        assertTrue(backend.requests.contains("graphql CreatePerson"))
    }

    // Q.135, Q.136 and Q.138: rename someone and grant them admin in one save.
    @Test
    fun renamingAndGrantingAdminSavesBoth() {
        open("Persons")
        compose.onNodeWithContentDescription("Edit Sam Other").performClick()
        waitForText("Edit person")
        assertTrue(shown("Can add, edit and remove rooms and people, and grant admin to others."))
        compose.onNode(hasText("Sam Other") and hasSetTextAction()).performTextReplacement("Samantha Other")
        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        // The new name is already in the field, so wait for the editor to close back to the list.
        waitForText("Manage the people who can be booked into meetings.")
        val sam = backend.otherPeople.first { it.id == "person-2" }
        assertEquals("Samantha Other", sam.name)
        assertTrue(sam.isAdmin)
    }

    // Q.139: a guest's admin switch is off, with the reason.
    @Test
    fun aGuestsAdminSwitchIsDisabled() {
        open("Persons")
        compose.onNodeWithContentDescription("Edit Guest Gale").performClick()
        waitForText("This person hasn't signed in yet - admin access can only be granted once they've signed up.")
        compose.onNodeWithContentDescription("Admin").assertIsNotEnabled()
    }

    // Q.140: your own admin switch is off, with the reason.
    @Test
    fun yourOwnAdminSwitchIsDisabled() {
        open("Persons")
        compose.onNodeWithContentDescription("Edit Pat Example").performClick()
        waitForText("You can't change your own admin access here.")
        compose.onNodeWithContentDescription("Admin").assertIsNotEnabled()
    }

    // When admin access saves but doesn't reach the sign-in account, Retry resends it.
    @Test
    fun aFailedSyncOffersRetry() {
        backend.adminSyncFails = true
        open("Persons")
        compose.onNodeWithContentDescription("Edit Sam Other").performClick()
        waitForText("Edit person")
        compose.onNodeWithContentDescription("Admin").performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        waitForText("Sync to sign-in failed")
        inDialog("Retry").performClick()
        waitForText("Still not synced - you can try again or cancel.")
        backend.adminSyncFails = false
        inDialog("Retry").performClick()
        compose.waitUntil(5_000) { !shown("Sync to sign-in failed") }
    }

    // Q.141: removing someone cancels the meetings they organise and takes them off the rest.
    @Test
    fun removingAPersonCascades() {
        val tomorrow = LocalDate.now().plusDays(1)
        backend.meetings = listOf(
            FakeBackend.meeting("m1", "Sam's meeting", tomorrow, 10, organiserId = "person-2"),
            FakeBackend.meeting("m2", "Pat's meeting", tomorrow, 12).copy(attendeeIds = listOf("person-2")),
        )
        open("Persons")
        compose.onNodeWithContentDescription("Remove Sam Other").performClick()
        waitForText("Remove Sam Other?")
        inDialog("Remove person").performClick()

        compose.waitUntil(5_000) { !shown("Sam Other") }
        assertEquals(listOf("m2"), backend.meetings.map { it.id })
        assertTrue(backend.meetings.single().attendeeIds.isEmpty())
    }

    // Q.142: removing yourself is refused, with a pointer to Delete account.
    @Test
    fun removingYourselfIsRefused() {
        open("Persons")
        compose.onNode(hasContentDescription("Remove Pat Example")).performClick()
        waitForText("Remove Pat Example?")
        inDialog("Remove person").performClick()
        waitForText("You cannot delete your own person this way - use Delete account in Settings instead.")
    }
}

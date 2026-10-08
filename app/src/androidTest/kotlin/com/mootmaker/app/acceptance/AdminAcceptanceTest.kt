package com.mootmaker.app.acceptance

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.data.auth.CognitoClient
import com.mootmaker.data.auth.IdTokenClaims
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Use cases L.89 to L.91, M.98, P.124 to P.131 and Q.132 to Q.142 against a real environment. See
 * [Acceptance]. Every case works on rooms and people it names uniquely, and the one account it signs
 * up (for the cases that need a second linked account) is deleted afterwards.
 */
class AdminAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val toDelete = mutableListOf<Acceptance.Account>()
    private val run = UUID.randomUUID().toString().take(6)
    private val admin by lazy { Api(Acceptance.admin) }
    private val tomorrow = LocalDate.now().plusDays(1)

    @After
    fun tearDown() {
        scenario?.close()
        toDelete.forEach { runCatching { Api(it).deleteMyAccount() } }
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun openMenu(account: Acceptance.Account) {
        scenario = Acceptance.launchApp()
        compose.signIn(account)
        compose.waitForText("Add meeting")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.waitForText("Settings")
    }

    /**
     * Opens Rooms or Persons from Home's overflow menu, then waits for the menu to be gone and the list
     * to have loaded before anything is touched. The menu is a focusable popup with an exit animation:
     * input sent while it is still closing goes to the wrong window, so a tap "fails to inject" and
     * typing "fails to perform text input".
     */
    private fun openAdmin(screen: String) {
        openMenu(Acceptance.admin)
        compose.onNodeWithText(screen).performClick()
        compose.waitForText(if (screen == "Rooms") "Manage the rooms available for booking." else "Manage the people who can be booked into meetings.")
        compose.waitUntil(30_000) { !compose.shown("Sign out") }
        val add = if (screen == "Rooms") "Add room" else "Add person"
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription(add)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * The row control for [description] ("Edit X", "Remove X"), scrolled into view in the list first.
     * The list is found by its scroll-to-index action, not any scroll action: a filter holding a long
     * email overflows its single line and becomes scrollable too. The row is scrolled clear of the
     * Add button, which would otherwise take a tap on a row at the bottom of the screen.
     */
    private fun rowAction(description: String): SemanticsNodeInteraction {
        compose.scrollClearOfTheBottom(hasScrollToIndexAction(), hasContentDescription(description))
        return compose.onNode(hasContentDescription(description))
    }

    /** Persons: narrows the list to [name] with the filter, so its row is on screen. */
    private fun filterTo(name: String) {
        compose.field("Filter").performTextReplacement(name)
        compose.waitForText(name)
    }

    private fun save() {
        button("Save").performClick()
    }

    /** A confirmed, signed-up standard account made through Cognito with a real emailed code. */
    private fun signedUpAccount(): Pair<EmailHelper.Identity, Acceptance.Account> {
        val identity = EmailHelper.freshAccount()
        val cognito = CognitoClient(Acceptance.http, Acceptance.config.userPoolId, Acceptance.config.androidClientId)
        runBlocking { cognito.signUp(identity.email, identity.password, identity.name) }
        val code = EmailHelper.waitForCode(identity.email)
        runBlocking { cognito.confirmSignUp(identity.email, code) }
        val account = Acceptance.Account(identity.email, identity.password)
        toDelete += account
        return identity to account
    }

    private fun claimsOf(account: Acceptance.Account): IdTokenClaims {
        val cognito = CognitoClient(Acceptance.http, Acceptance.config.userPoolId, Acceptance.config.androidClientId)
        return IdTokenClaims.parse(runBlocking { cognito.signIn(account.email, account.password) }.idToken)
    }

    /** L.89, P.124 and Q.132: a standard user is offered neither Rooms nor Persons. */
    @Test
    fun aStandardUserIsNotOfferedTheAdminScreens() {
        openMenu(Acceptance.standard)
        assertFalse(compose.shown("Rooms"))
        assertFalse(compose.shown("Persons"))
    }

    /** L.90: a standard user calling an admin mutation directly is refused by the API. */
    @Test
    fun aStandardUsersAdminMutationIsRefused() {
        val refused = runCatching { Api(Acceptance.standard).createRoom("Z-Forbidden $run") }.exceptionOrNull()
        assertTrue("Expected a refusal, got $refused", refused?.message?.contains("Forbidden") == true)
        assertFalse(admin.roomNames().contains("Z-Forbidden $run"))
    }

    /** L.91: a standard user can rename themself, and nothing lets them rename anyone else. */
    @Test
    fun aStandardUserRenamesOnlyThemself() {
        val standard = Api(Acceptance.standard)
        val before = standard.myName()
        try {
            standard.updateMyName("$before $run")
            assertEquals("$before $run", standard.myName())
            val other = admin.myPersonId()
            val refused = runCatching {
                standard.query(
                    "mutation(\$id: ID!, \$name: String!) { renamePerson(id: \$id, name: \$name) { errors } }",
                    buildJsonObject { put("id", other); put("name", "Renamed $run") },
                )
            }.exceptionOrNull()
            assertTrue("Expected a refusal, got $refused", refused?.message?.contains("Forbidden") == true)
        } finally {
            standard.updateMyName(before)
        }
    }

    /** P.125, then P.128 and P.129: an added room is offered for booking; an edit, below a booking's size, is saved. */
    @Test
    fun addARoomThenEditIt() {
        val name = "Z-Room $run"
        openAdmin("Rooms")
        compose.onNodeWithContentDescription("Add room").performClick()
        compose.waitForText("Add room")
        compose.field("Name").performTextReplacement(name)
        compose.field("Capacity").performTextReplacement("6")
        save()
        compose.waitForText("Manage the rooms available for booking.")
        assertTrue(name in admin.roomNames())

        // Booked for five, then cut to two: allowed, not checked against the booking (P.129).
        val roomId = admin.roomIdNamed(name)
        val guests = (1..4).map { admin.createPerson("Z-Guest $run $it") }
        admin.createMeeting(roomId, admin.myPersonId(), "Big $run", "${tomorrow}T10:00:00", "${tomorrow}T11:00:00", guests)

        rowAction("Edit $name").performClick()
        compose.waitForText("Edit room")
        compose.onNode(hasText(name) and hasSetTextAction()).performTextReplacement("$name renamed")
        compose.field("Capacity").performTextReplacement("2")
        save()
        compose.waitForText("Manage the rooms available for booking.")
        compose.waitUntil(30_000) { admin.roomNames().contains("$name renamed") }
        assertEquals("Z-Room $run renamed", admin.subjectsRoomOn(tomorrow.toString(), "Big $run"))
    }

    /** M.98: a room renamed on Rooms reads with its new name on home and room availability, with no refresh. */
    @Test
    fun aRenamedRoomIsRenamedEverywhere() {
        val name = "Z-Cache $run"
        val room = admin.createRoom(name)
        val today = LocalDate.now()
        admin.createMeeting(room, admin.myPersonId(), "Cached $run", "${today}T07:00:00", "${today}T07:30:00")
        openAdmin("Rooms")

        rowAction("Edit $name").performClick()
        compose.waitForText("Edit room")
        compose.onNode(hasText(name) and hasSetTextAction()).performTextReplacement("$name renamed")
        save()
        compose.waitForText("Manage the rooms available for booking.")
        compose.waitForText("$name renamed")

        compose.onNodeWithContentDescription("Back").performClick()
        // Rooms' list must be gone first: while it leaves, it is a second scrollable list on screen.
        compose.waitUntil(30_000) { !compose.shown("Manage the rooms available for booking.") }
        // Home's agenda row names the meeting's room after its time, and only by the new name.
        compose.scrollHomeTo(hasText("· $name renamed", substring = true))
        assertFalse(compose.onAllNodes(hasText("· $name", substring = true) and !hasText("renamed", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // Back up to the top of home's lazy list, where the agenda scroll left Rooms today uncomposed.
        compose.scrollHomeTo(hasText("Rooms today") and hasClickAction())
        compose.onNode(hasText("Rooms today") and hasClickAction()).performClick()
        compose.waitForText("Room availability")
        compose.scrollClearOfTheBottom(hasScrollToIndexAction(), hasText("$name renamed"))
        assertFalse(compose.shown(name))
    }

    /** P.126 and P.127: a blank name and a capacity of 1 are refused, in the API's words. */
    @Test
    fun aBlankNameAndATooSmallCapacityAreRefused() {
        openAdmin("Rooms")
        compose.onNodeWithContentDescription("Add room").performClick()
        compose.waitForText("Add room")
        compose.field("Capacity").performTextReplacement("1")
        save()
        compose.waitForText("Name must not be blank.")
        compose.waitForText("Room capacity must be at least 2.")
    }

    /** P.130 and P.131: a room with a meeting from today on is refused; without one, it goes. */
    @Test
    fun removingARoomNeedsItToHaveNoUpcomingMeetings() {
        val busy = "Z-Busy $run"
        val free = "Z-Free $run"
        val busyId = admin.createRoom(busy)
        admin.createRoom(free)
        admin.createMeeting(busyId, admin.myPersonId(), "Keeps $run", "${tomorrow}T10:00:00", "${tomorrow}T11:00:00")
        openAdmin("Rooms")

        rowAction("Remove $busy").performClick()
        compose.waitForText("Remove $busy?")
        inDialog("Remove room").performClick()
        compose.waitForText("This room has one or more meetings booked from today onward - reassign or cancel them before deleting it.")
        inDialog("Cancel").performClick()
        assertTrue(busy in admin.roomNames())

        rowAction("Remove $free").performClick()
        compose.waitForText("Remove $free?")
        inDialog("Remove room").performClick()
        compose.waitUntil(30_000) { !compose.shown(free) }
        assertFalse(free in admin.roomNames())
    }

    /** Q.133, Q.134 and Q.137: a guest is added and shown as not signed up; a blank name is refused. */
    @Test
    fun addAGuest() {
        val name = "Z-Person $run"
        openAdmin("Persons")
        compose.onNodeWithContentDescription("Add person").performClick()
        compose.waitForText("Add person")
        save()
        compose.waitForText("Name must not be blank.")
        compose.field("Name").performTextReplacement(name)
        save()
        compose.waitForText("Manage the people who can be booked into meetings.")
        filterTo(name)
        compose.waitForText("Not signed up yet")
    }

    /** Q.137: a linked person shows their email; an admin shows the badge. */
    @Test
    fun linkedPeopleShowEmailsAndAdminsABadge() {
        openAdmin("Persons")
        filterTo(Acceptance.admin.email)
        compose.waitForText(Acceptance.admin.email)
        compose.waitForText("Admin")
    }

    /** Q.135, Q.138: a linked account renamed and made admin sees both at its next sign-in. */
    @Test
    fun renameAndGrantAdminToALinkedAccount() {
        val (identity, account) = signedUpAccount()
        openAdmin("Persons")
        filterTo(identity.email)
        rowAction("Edit ${identity.name}").performClick()
        compose.waitForText("Edit person")
        compose.onNode(hasText(identity.name) and hasSetTextAction()).performTextReplacement("${identity.name} Renamed")
        compose.onNode(isToggleable()).performClick()
        save()
        compose.waitForText("Manage the people who can be booked into meetings.")

        val claims = claimsOf(account)
        assertTrue(claims.isAdmin)
        assertEquals("${identity.name} Renamed", Api(account).myName())
    }

    /** Q.136, Q.139: a guest is renamed, and their admin switch is off with the reason. */
    @Test
    fun renameAGuestWhoseAdminSwitchIsOff() {
        val name = "Z-Guest $run"
        admin.createPerson(name)
        openAdmin("Persons")
        filterTo(name)
        rowAction("Edit $name").performClick()
        compose.waitForText("This person hasn't signed in yet - admin access can only be granted once they've signed up.")
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNode(hasText(name) and hasSetTextAction()).performTextReplacement("$name renamed")
        save()
        compose.waitForText("Manage the people who can be booked into meetings.")
        compose.waitUntil(30_000) { admin.personNames().contains("$name renamed") }
    }

    /** Q.140: your own admin switch is off. */
    @Test
    fun yourOwnAdminSwitchIsOff() {
        val me = admin.myName()
        openAdmin("Persons")
        filterTo(Acceptance.admin.email)
        rowAction("Edit $me").performClick()
        compose.waitForText("You can't change your own admin access here.")
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    /** Q.141: removing a person cancels what they organise and takes them off what they attend. */
    @Test
    fun removingAPersonCascades() {
        val name = "Z-Leaver $run"
        val leaver = admin.createPerson(name)
        val room = admin.createRoom("Z-Cascade $run")
        val theirs = admin.createMeeting(room, leaver, "Theirs $run", "${tomorrow}T09:00:00", "${tomorrow}T10:00:00")
        val mine = admin.createMeeting(room, admin.myPersonId(), "Mine $run", "${tomorrow}T11:00:00", "${tomorrow}T12:00:00", listOf(leaver))

        openAdmin("Persons")
        filterTo(name)
        rowAction("Remove $name").performClick()
        compose.waitForText("Remove $name?")
        inDialog("Remove person").performClick()
        compose.waitForText("No people match that filter.")

        assertNull(admin.meeting(theirs))
        assertNull(admin.responseOf(mine, leaver))
        assertEquals("Mine $run", admin.subjectOf(mine))
    }

    /** Q.142: removing yourself is refused, with a pointer to Delete account. */
    @Test
    fun removingYourselfIsRefused() {
        val me = admin.myName()
        openAdmin("Persons")
        filterTo(Acceptance.admin.email)
        rowAction("Remove $me").performClick()
        compose.waitForText("Remove $me?")
        inDialog("Remove person").performClick()
        compose.waitForText("You cannot delete your own person this way - use Delete account in Settings instead.")
    }
}

package com.mootmaker.app.acceptance

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.data.auth.CognitoClient
import com.mootmaker.data.auth.IdTokenClaims
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases A.1 to A.6 (sign up), C.16 to C.20 (forgot password) and delete account, against a real
 * environment with real emailed codes (see [EmailHelper]). Every case uses a fresh identity and
 * deletes any account it created, so nothing is left in the shared pool.
 */
class AccountAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Accounts a test confirmed and has not yet deleted itself, deleted after it whatever happens. */
    private val toDelete = mutableListOf<Acceptance.Account>()

    @After
    fun tearDown() {
        scenario?.close()
        toDelete.forEach { runCatching { Api(it).deleteMyAccount() } }
    }

    private val cognito by lazy { CognitoClient(Acceptance.http, Acceptance.config.userPoolId, Acceptance.config.androidClientId) }

    /**
     * Scrolled into view first: once a field has focus the keyboard covers the bottom of these forms,
     * and a tap on a button under it lands on the keyboard instead.
     */
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction()).performScrollTo()

    private fun startSignUp(identity: EmailHelper.Identity) {
        scenario = Acceptance.launchApp()
        compose.waitForText("Create an account")
        button("Create an account").performClick()
        compose.waitForText("Already have an account?")
        compose.field("Name").performTextReplacement(identity.name)
        compose.field("Email").performTextReplacement(identity.email)
        compose.field("Password").performTextReplacement(identity.password)
        button("Sign up").performClick()
    }

    /** A confirmed account made through Cognito directly: the starting point for the reset cases. */
    private fun confirmedAccount(): EmailHelper.Identity {
        val identity = EmailHelper.freshAccount()
        runBlocking { cognito.signUp(identity.email, identity.password, identity.name) }
        val code = EmailHelper.waitForCode(identity.email)
        runBlocking { cognito.confirmSignUp(identity.email, code) }
        toDelete += Acceptance.Account(identity.email, identity.password)
        return identity
    }

    /**
     * A.1, A.4, A.5 and A.6, then delete account. One account goes through the whole lifecycle: a
     * wrong code is refused and the real one signs in; the new Person has the entered name and is
     * standard; it can book a meeting as itself straight away; deleting it signs out, cancels the
     * meeting it organised, and its password no longer signs in.
     */
    @Test
    fun signUpWithARealCodeUseTheAccountThenDeleteIt() {
        val run = UUID.randomUUID().toString().take(6)
        val admin = Api(Acceptance.admin)
        val room = "Z-Account $run"
        admin.createRoom(room)
        val identity = EmailHelper.freshAccount()
        val account = Acceptance.Account(identity.email, identity.password)

        startSignUp(identity)
        compose.waitForText("Verification code")
        val code = EmailHelper.waitForCode(identity.email)
        toDelete += account

        // A.4: a wrong code is refused, and the code step stays.
        val wrong = code.dropLast(1) + ((code.last().digitToInt() + 1) % 10)
        compose.field("Verification code").performTextReplacement(wrong)
        button("Confirm").performClick()
        compose.waitForTextContaining("Invalid verification code")
        assertTrue(compose.shown("Confirm"))
        assertFalse(compose.shown("Add meeting"))

        // A.1: the real code confirms and signs in, with no separate sign-in step.
        compose.field("Verification code").performTextReplacement(code)
        button("Confirm").performClick()
        compose.waitForText("Add meeting")

        // A.5: the Person was created with the entered name, and the account is standard.
        compose.waitForText(identity.name)
        val claims = IdTokenClaims.parse(runBlocking { cognito.signIn(identity.email, identity.password) }.idToken)
        assertFalse(claims.isAdmin)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForText("Your name")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(identity.name) and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()

        // A.6: book a meeting straight away; the organiser is already you.
        compose.waitForText("Add meeting")
        compose.onNodeWithText("Add meeting").performClick()
        compose.waitForText("Organiser")
        compose.waitForText(identity.name)
        val subject = "Signed up $run"
        compose.onNode(hasText("Subject") and hasSetTextAction()).performTextInput(subject)
        compose.onNode(hasText("Room") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("$room (capacity 6)") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("$room (capacity 6)") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().performClick()
        compose.waitForText("Attendees · 0")
        assertTrue(compose.shown("You"))
        // The form defaults to today, as AddMeetingAcceptanceTest relies on too.
        val bookedOn = LocalDate.now()
        assertTrue(subject in admin.subjectsOn(bookedOn.toString()))
        compose.onNodeWithContentDescription("Back").performClick()

        // Delete account: confirm, and the app is signed out.
        compose.waitForText("Add meeting")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForText("Your name")
        button("Delete my account").performClick()
        compose.waitForText("Delete your account?")
        compose.onNode(hasText("Delete my account") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForText("Create an account")
        toDelete -= account

        // The meeting it organised is cancelled, and its password no longer signs in.
        assertFalse(subject in admin.subjectsOn(bookedOn.toString()))
        compose.signIn(account)
        compose.waitForText("Incorrect username or password.")
    }

    /** A.2: a password below the policy is refused by Cognito, and no code step is reached. */
    @Test
    fun aWeakPasswordIsRefused() {
        startSignUp(EmailHelper.freshAccount().copy(password = "short1"))
        compose.waitForTextContaining("conform")
        assertFalse(compose.shown("Verification code"))
    }

    /** A.3: an email that already has an account is refused, and no code step is reached. */
    @Test
    fun anEmailThatAlreadyHasAnAccountIsRefused() {
        startSignUp(EmailHelper.freshAccount().copy(email = Acceptance.standard.email))
        compose.waitForTextContaining("already exists")
        assertFalse(compose.shown("Verification code"))
    }

    private fun startReset(email: String) {
        scenario = Acceptance.launchApp()
        compose.waitForText("Forgot password?")
        button("Forgot password?").performClick()
        compose.waitForText("Remembered it?")
        compose.field("Email").performTextReplacement(email)
        button("Send code").performClick()
        compose.waitForText("New password")
    }

    /** C.18 then C.16: a wrong code is refused; the real code with a new password signs in with it. */
    @Test
    fun aWrongCodeIsRefusedThenTheRealOneResetsThePasswordAndSignsIn() {
        val identity = confirmedAccount()
        startReset(identity.email)
        val code = EmailHelper.waitForCode(identity.email)
        val newPassword = identity.password + "-new"

        val wrong = code.dropLast(1) + ((code.last().digitToInt() + 1) % 10)
        compose.field("Verification code").performTextReplacement(wrong)
        compose.field("New password").performTextReplacement(newPassword)
        button("Reset password").performClick()
        compose.waitForTextContaining("Invalid verification code")
        assertFalse(compose.shown("Add meeting"))

        compose.field("Verification code").performTextReplacement(code)
        button("Reset password").performClick()
        compose.waitForText("Add meeting")
        compose.waitForText(identity.name)
        toDelete.clear()
        toDelete += Acceptance.Account(identity.email, newPassword)

        // The old password no longer works; the new one does.
        assertTrue(runCatching { runBlocking { cognito.signIn(identity.email, identity.password) } }.isFailure)
        assertEquals(identity.email, IdTokenClaims.parse(runBlocking { cognito.signIn(identity.email, newPassword) }.idToken).email)
    }

    /** C.19: the real code with a new password below the policy is refused, and nothing signs in. */
    @Test
    fun aWeakNewPasswordIsRefused() {
        val identity = confirmedAccount()
        startReset(identity.email)
        val code = EmailHelper.waitForCode(identity.email)

        compose.field("Verification code").performTextReplacement(code)
        compose.field("New password").performTextReplacement("short1")
        button("Reset password").performClick()
        compose.waitForTextContaining("conform")
        assertFalse(compose.shown("Add meeting"))
    }

    /** C.17 and C.20: an email with no account moves to the code step just the same; the flow links back to sign-in. */
    @Test
    fun anEmailWithNoAccountBehavesTheSameAndLinksBackToSignIn() {
        startReset("nobody-${UUID.randomUUID()}@mail.mootmaker.com")
        assertTrue(compose.shown("Verification code"))
        button("Sign in").performClick()
        compose.waitForText("No account yet?")
    }
}

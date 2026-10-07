package com.mootmaker.app.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
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

/**
 * Use cases A (sign up), C (forgot password) and delete account through the real app wiring,
 * against [FakeBackend]. The acceptance suite runs the same flows with real emailed codes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AccountFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())

    private fun launch() {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
    }

    private fun fillSignUp(name: String = "New Person", email: String = "new@example.com", password: String = "a-good-pw-123") {
        button("Create an account").performClick()
        waitForText("Already have an account?")
        field("Name").performTextReplacement(name)
        field("Email").performTextReplacement(email)
        field("Password").performTextReplacement(password)
        button("Sign up").performClick()
    }

    // A.1 and A.5: details, then the code, then signed in to home as the new person.
    @Test
    fun signingUpConfirmsWithTheCodeAndSignsIn() {
        launch()
        fillSignUp()
        waitForText("Verification code")
        assertTrue(shown("We sent a verification code to new@example.com. Enter it below to finish creating your account."))
        assertEquals("new@example.com/New Person", backend.signedUp)

        field("Verification code").performTextReplacement("123456")
        button("Confirm").performClick()

        compose.waitUntil(5_000) { shown("Rooms today") }
        assertTrue(backend.requests.containsAll(listOf("cognito ConfirmSignUp", "cognito RespondToAuthChallenge")))
    }

    // A.2 and A.3: Cognito's refusal is shown, and the code step is never reached.
    @Test
    fun aRefusedSignUpShowsCognitosMessageAndStaysOnTheDetails() {
        backend.signUpError = "UsernameExistsException" to "An account with the given email already exists."
        launch()
        fillSignUp()

        waitForText("An account with the given email already exists.")
        assertFalse(shown("Verification code"))
    }

    // The PreSignUp trigger's rejection reads as its own sentence, without Cognito's wrapper.
    @Test
    fun aNameCollisionShowsTheTriggersOwnSentence() {
        backend.signUpError = "UserLambdaValidationException" to
            "PreSignUp failed with error Someone called New Person already has an account. Add something to tell you apart.."
        launch()
        fillSignUp()

        waitForText("Someone called New Person already has an account. Add something to tell you apart.")
    }

    // A.4: a wrong code is refused and leaves you on the code step; the right one then works.
    @Test
    fun aWrongCodeIsRefusedThenTheRightOneSignsIn() {
        launch()
        fillSignUp()
        waitForText("Verification code")

        field("Verification code").performTextReplacement("000000")
        button("Confirm").performClick()
        waitForText("Invalid verification code provided, please try again.")
        assertTrue(shown("Confirm"))

        field("Verification code").performTextReplacement("123456")
        button("Confirm").performClick()
        compose.waitUntil(5_000) { shown("Rooms today") }
    }

    // Empty fields are named before anything is sent.
    @Test
    fun emptyFieldsAreNamedAndNothingIsSent() {
        launch()
        button("Create an account").performClick()
        waitForText("Already have an account?")
        button("Sign up").performClick()

        waitForText("Enter your name.")
        assertTrue(shown("Enter your email."))
        assertTrue(shown("Enter your password."))
        assertFalse(backend.requests.contains("cognito SignUp"))
    }

    // C.16: a code for the email, then the code with a new password, then signed in with it.
    @Test
    fun resettingThePasswordSignsInWithTheNewOne() {
        launch()
        button("Forgot password?").performClick()
        waitForText("Remembered it?")
        field("Email").performTextReplacement("pat@example.com")
        button("Send code").performClick()

        waitForText("We sent a verification code to pat@example.com. Enter it below with your new password.")
        assertEquals("pat@example.com", backend.resetRequestedFor)
        field("Verification code").performTextReplacement("123456")
        field("New password").performTextReplacement("a-new-pw-456")
        button("Reset password").performClick()

        compose.waitUntil(5_000) { shown("Rooms today") }
        assertEquals("a-new-pw-456", backend.lastPassword)
    }

    // C.18: a wrong reset code is refused and nothing signs in.
    @Test
    fun aWrongResetCodeIsRefused() {
        launch()
        button("Forgot password?").performClick()
        waitForText("Remembered it?")
        field("Email").performTextReplacement("pat@example.com")
        button("Send code").performClick()
        waitForText("New password")

        field("Verification code").performTextReplacement("000000")
        field("New password").performTextReplacement("a-new-pw-456")
        button("Reset password").performClick()

        waitForText("Invalid verification code provided, please try again.")
        assertFalse(backend.requests.contains("cognito RespondToAuthChallenge"))
    }

    // C.20 (and its sign-up counterpart): each flow links back to sign-in.
    @Test
    fun bothFlowsLinkBackToSignIn() {
        launch()
        button("Forgot password?").performClick()
        waitForText("Remembered it?")
        button("Sign in").performClick()
        waitForText("No account yet?")

        button("Create an account").performClick()
        waitForText("Already have an account?")
        button("Sign in").performClick()
        waitForText("No account yet?")
    }

    private fun openSettings() {
        launch()
        button("Sign in").performClick()
        compose.waitUntil(5_000) { shown("Rooms today") }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Settings")).performClick()
        waitForText("Your name")
    }

    // Delete account: confirm, and the app is signed out back to sign-in.
    @Test
    fun deletingTheAccountSignsOut() {
        openSettings()
        button("Delete my account").performScrollTo().performClick()
        waitForText("Delete your account?")
        compose.onNode(hasText("Delete my account") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

        waitForText("No account yet?")
        assertTrue(backend.accountDeleted)
    }

    // Cancel leaves the account alone.
    @Test
    fun cancellingTheConfirmationKeepsTheAccount() {
        openSettings()
        button("Delete my account").performScrollTo().performClick()
        waitForText("Delete your account?")
        button("Cancel").performClick()

        compose.waitUntil(5_000) { !shown("Delete your account?") }
        assertFalse(backend.accountDeleted)
        assertFalse(backend.requests.contains("graphql DeleteMyAccount"))
    }

    // A refusal (the demo user is reserved) is shown in the dialog, and the session stays.
    @Test
    fun aRefusedDeletionIsShownAndStaysSignedIn() {
        backend.deleteAccountError = "This account is reserved and can't be deleted."
        openSettings()
        button("Delete my account").performScrollTo().performClick()
        waitForText("Delete your account?")
        compose.onNode(hasText("Delete my account") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

        waitForText("This account is reserved and can't be deleted.")
        assertTrue(shown("Delete your account?"))
        assertFalse(shown("No account yet?"))
    }
}

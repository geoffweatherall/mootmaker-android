package com.mootmaker.app.acceptance

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Use cases B.7, B.9, B.10, B.13 and B.15 against a real environment. See [Acceptance].
 *
 * B.14 (a protected page visited while signed out returns you there after signing in) has no
 * Android counterpart: nothing outside the app can open one of its screens, so signed out, the app
 * starts on sign-in and nothing behind it is reachable (B.13).
 */
class SignInAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    /** B.7, then B.13: sign in with correct credentials, then sign out to a locked-down state. */
    @Test
    fun signInWithCorrectCredentialsThenSignOut() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.standard)
        compose.waitForText("Home")
        compose.waitForText("E2E Standard")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()
        compose.waitForText("Create an account")
        assertFalse(compose.shown("E2E Standard"))

        // Signed out is locked down: Back leaves the app rather than returning to home. Android 12+
        // moves a task's root activity to the back instead of finishing it, so "left" means no
        // longer resumed, not necessarily destroyed.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { scenario.state != Lifecycle.State.RESUMED }
    }

    /** B.15: signed out, sign-up, forgot password and About each open, and lead back to sign-in. */
    @Test
    fun thePublicScreensOpenWhileSignedOut() {
        scenario = Acceptance.launchApp()
        compose.waitForText("Create an account")

        compose.onNodeWithText("Create an account").performClick()
        compose.waitForText("Already have an account?")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForText("Forgot password?")

        compose.onNodeWithText("Forgot password?").performClick()
        compose.waitForText("Remembered it?")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForText("About")

        compose.onNodeWithText("About").performClick()
        compose.waitForText("Mootmaker for Android")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("Create an account")
        assertFalse(compose.shown("Home"))
    }

    /** B.9: a wrong password shows an error and doesn't sign in. */
    @Test
    fun wrongPasswordShowsAnErrorAndDoesNotSignIn() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.standard.copy(password = Acceptance.standard.password + "x"))
        compose.waitForText(INCORRECT)
        assertFalse(compose.shown("Home"))
    }

    /** B.10: an unknown email shows the same error as a wrong password, so it reveals nothing. */
    @Test
    fun unknownEmailShowsTheSameErrorAsAWrongPassword() {
        scenario = Acceptance.launchApp()
        compose.signIn(Acceptance.Account("nobody-${UUID.randomUUID()}@mail.mootmaker.com", "Not-a-real-password-1"))
        compose.waitForText(INCORRECT)
        assertFalse(compose.shown("Home"))
    }

    private companion object {
        /** Cognito's own message, shown as is. Identical for both cases (prevent_user_existence_errors). */
        const val INCORRECT = "Incorrect username or password."
    }
}

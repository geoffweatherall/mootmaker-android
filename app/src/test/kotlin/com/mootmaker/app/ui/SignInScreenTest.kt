package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mootmaker.app.ui.signin.SignInConfig
import com.mootmaker.app.ui.signin.SignInScreen
import com.mootmaker.app.ui.signin.SignInViewModel
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.auth.CognitoException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val attempts = mutableListOf<Pair<String, String>>()
    private var failure: Exception? = null
    private var retried = 0

    private fun show(config: SignInConfig = SignInConfig.Ready(hasDemoUser = false)): SignInViewModel {
        val viewModel = SignInViewModel { email, password ->
            attempts += email to password
            failure?.let { throw it }
        }
        compose.setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MootmakerTheme {
                SignInScreen(
                    state = state,
                    config = config,
                    onEmailChange = viewModel::onEmailChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onSubmit = viewModel::submit,
                    onRetryConfig = { retried++ },
                    onCreateAccount = {},
                    onForgotPassword = {},
                    onAbout = {},
                )
            }
        }
        return viewModel
    }

    private fun signInButton() = compose.onNode(hasText("Sign in") and hasClickAction())

    private fun fill(email: String, password: String) {
        compose.onNodeWithText("Email").performTextReplacement(email)
        compose.onNodeWithText("Password").performTextReplacement(password)
    }

    @Test
    fun emptyFieldsAreCaughtBeforeAnyRequest() {
        show()
        signInButton().performClick()

        compose.onNodeWithText("Enter your email address.").assertIsDisplayed()
        compose.onNodeWithText("Enter your password.").assertIsDisplayed()
        assertEquals(emptyList<Pair<String, String>>(), attempts)
    }

    @Test
    fun anEmailWithoutAnAtSignIsRejected() {
        show()
        fill("not-an-email", "secret")
        signInButton().performClick()

        compose.onNodeWithText("Enter a valid email address.").assertIsDisplayed()
        assertEquals(emptyList<Pair<String, String>>(), attempts)
    }

    @Test
    fun theEmailIsTrimmedBeforeSigningIn() {
        show()
        fill("  pat@example.com ", "secret")
        signInButton().performClick()
        compose.waitForIdle()

        assertEquals(listOf("pat@example.com" to "secret"), attempts)
    }

    // Use cases B.9 and B.10: the pool doesn't reveal whether the account exists, so both read the same.
    @Test
    fun wrongPasswordShowsCognitosMessage() {
        failure = CognitoException("NotAuthorizedException", "Incorrect username or password.")
        show()
        fill("pat@example.com", "wrong")
        signInButton().performClick()

        compose.onNodeWithText("Incorrect username or password.").assertIsDisplayed()
        // The form stays filled in so the user can correct it.
        compose.onNodeWithText("pat@example.com").assertIsDisplayed()
    }

    @Test
    fun noNetworkSaysSo() {
        failure = IOException("offline")
        show()
        fill("pat@example.com", "secret")
        signInButton().performClick()

        compose.onNodeWithText("Couldn't reach Mootmaker. Check your connection and try again.").assertIsDisplayed()
    }

    @Test
    fun demoCredentialsArePrefilled() {
        val viewModel = show(SignInConfig.Ready(hasDemoUser = true))
        compose.runOnIdle { viewModel.prefill("demo@mootmaker.com", "demo-password") }

        compose.onNodeWithText("demo@mootmaker.com").assertIsDisplayed()
        compose.onNodeWithText("The demo account is filled in. Tap Sign in to look around.").assertIsDisplayed()
    }

    @Test
    fun anotherEnvironmentsDemoUserReplacesThePrefill() {
        val viewModel = show(SignInConfig.Ready(hasDemoUser = true))
        compose.runOnIdle {
            viewModel.prefill("demo@mootmaker.com", "demo-password")
            viewModel.prefill("demo@test.mootmaker.com", "test-password")
        }

        compose.onNodeWithText("demo@test.mootmaker.com").assertIsDisplayed()
    }

    @Test
    fun aPrefillNeverReplacesWhatWasTyped() {
        val viewModel = show(SignInConfig.Ready(hasDemoUser = true))
        compose.runOnIdle { viewModel.prefill("demo@mootmaker.com", "demo-password") }
        fill("pat@example.com", "secret")
        compose.runOnIdle { viewModel.prefill("demo@test.mootmaker.com", "test-password") }

        compose.onNodeWithText("pat@example.com").assertIsDisplayed()
    }

    @Test
    fun aConfigThatWontLoadOffersARetry() {
        show(SignInConfig.Failed("test"))
        compose.onNodeWithText("Couldn't load Mootmaker's settings for test. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }
}

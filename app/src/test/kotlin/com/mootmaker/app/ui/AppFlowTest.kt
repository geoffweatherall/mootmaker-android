package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
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

/**
 * The whole app — config, sign-in, the home query and navigation — against [FakeBackend].
 * The same journeys run on an emulator against a real environment in the e2e suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AppFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend().apply {
        val today = LocalDate.now()
        meetings = listOf(
            FakeBackend.meeting("m1", "Stand-up", today, 9),
            FakeBackend.meeting("m2", "Someone else's meeting", today, 10, organiserId = "person-2"),
            FakeBackend.meeting("m3", "Planning", today.plusDays(1), 11),
        )
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun signInButton() = compose.onNode(hasText("Sign in") and hasClickAction())

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    // Use case B.7 (and D.22's agenda) through the real app wiring.
    @Test
    fun signingInWithTheDemoAccountShowsYourDay() {
        useFakeBackend(backend)
        launch()

        waitForText("demo@mootmaker.com")
        signInButton().performClick()

        waitForText("Stand-up")
        compose.onNodeWithText("Pat Example").assertIsDisplayed()
        compose.onNodeWithText("Planning").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Someone else's meeting")).fetchSemanticsNodes().isEmpty())
        assertTrue(backend.requests.containsAll(listOf("cognito InitiateAuth", "cognito RespondToAuthChallenge", "graphql Home")))
    }

    // Use case B.9: the error is shown and nothing past sign-in is reachable.
    @Test
    fun aWrongPasswordKeepsYouOnSignIn() {
        backend.signInError = "NotAuthorizedException" to "Incorrect username or password."
        useFakeBackend(backend)
        launch()

        waitForText("demo@mootmaker.com")
        compose.onNodeWithText("Password").performTextReplacement("wrong")
        signInButton().performClick()

        waitForText("Incorrect username or password.")
        assertTrue("graphql Home" !in backend.requests)
    }

    // Use case B.13: signing out returns to sign-in, and Back can't reach the signed-in screen.
    @Test
    fun signingOutLocksTheAppAgain() {
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()

        waitForText("Create an account")
        assertTrue(backend.requests.contains("cognito RevokeToken"))
        var finishing = false
        scenario!!.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
            finishing = it.isFinishing
        }
        assertTrue("Back should leave the app, not return home", finishing)
    }

    @Test
    fun theSessionSurvivesAnActivityRestart() {
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()
        waitForText("Stand-up")

        scenario!!.recreate()

        waitForText("Stand-up")
        assertEquals(1, backend.requests.count { it == "cognito RespondToAuthChallenge" })
    }

    // Use case D.24 through the real app wiring.
    @Test
    fun anAccountWithNoLinkedPersonSeesTheSetUpMessage() {
        backend.personId = null
        useFakeBackend(backend)
        launch()
        waitForText("demo@mootmaker.com")
        signInButton().performClick()

        waitForText("Your account hasn't been set up properly — no profile could be found for your sign-in.")
    }

    @Test
    fun theEnvironmentCanBeSwitchedFromAbout() {
        useFakeBackend(backend)
        launch()
        waitForText("About")
        compose.onNodeWithText("About").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Version", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val version = compose.onNode(hasText("Version", substring = true))
        repeat(7) { version.performClick() }

        compose.onNodeWithText("Environment name").performTextReplacement("test")
        compose.onNodeWithText("Switch environment").performClick()

        compose.waitUntil(5_000) { "config www.test.mootmaker.com" in backend.requests }
        waitForText("Environment: test")
    }
}

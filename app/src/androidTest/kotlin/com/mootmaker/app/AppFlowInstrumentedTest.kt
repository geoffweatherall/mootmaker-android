package com.mootmaker.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.testing.FakeBackend
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The app on a real device (Keystore, Custom Tabs available, real lifecycle) against [FakeBackend].
 * Runs on every push; the same journey against a real Cognito pool is [ProductionDemoE2eTest].
 */
class AppFlowInstrumentedTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun signInSeeYourDayAndSignOut() {
        val backend = FakeBackend().apply {
            meetings = listOf(FakeBackend.meeting("m1", "Stand-up", LocalDate.now(), 9))
        }
        val application = ApplicationProvider.getApplicationContext<MootmakerApplication>()
        application.replaceContainer(AppContainer(application, backend.httpClient, store = InMemoryKeyValueStore()))
        scenario = ActivityScenario.launch(MainActivity::class.java)

        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()
        waitForText("Create an account")
    }
}

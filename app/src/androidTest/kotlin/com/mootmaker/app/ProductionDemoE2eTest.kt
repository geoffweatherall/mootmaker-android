package com.mootmaker.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.config.MobileConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * e2e: real Cognito SRP sign-in and a real GraphQL query, as production's public demo user.
 * Read-only, the same thing anyone does on the website's home page.
 *
 * CI reads production's configuration and passes it as instrumentation arguments
 * (.github/scripts/production-demo-args.py). It prefers `mobile-config.json` with the Android
 * Cognito client. Until a release publishes that file, it falls back to the webapp's
 * `env-config.js` and signs in through the webapp's client, which also allows SRP. A check-run
 * notice names the source used. Skipped when the arguments are absent.
 */
class ProductionDemoE2eTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) = compose.waitUntil(timeoutMs, condition)

    private companion object {
        const val NO_PERSON = "Your account hasn't been set up properly — no profile could be found for your sign-in."
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun demoUserSignsInSeesTheirDayAndSignsOut() {
        val args = InstrumentationRegistry.getArguments()
        val graphqlUrl = args.getString("e2eGraphqlUrl")
        assumeTrue("No e2e arguments: skipped", !graphqlUrl.isNullOrBlank())
        val config = MobileConfig(
            graphqlApiUrl = graphqlUrl!!,
            userPoolId = args.getString("e2eUserPoolId")!!,
            androidClientId = args.getString("e2eClientId")!!,
            demoUserEmail = args.getString("e2eEmail")!!,
            demoUserPassword = args.getString("e2ePassword")!!,
        )
        val store = InMemoryKeyValueStore()
        runBlocking { store.put("config.production", config.toJson()) }
        val application = ApplicationProvider.getApplicationContext<MootmakerApplication>()
        application.replaceContainer(AppContainer(application, store = store))
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // B.7: the demo credentials are pre-filled; signing in reaches home.
        waitFor { shown(config.demoUserEmail!!) }
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitFor { shown("Home") }

        // The home query returned: either the agenda (D.22/D.23) or the no-person message (D.24).
        waitFor {
            shown("Today") || shown("No meetings today or tomorrow.") || shown(NO_PERSON) || shown("Try again")
        }
        check(!shown("Try again")) { "The home query failed" }

        // B.13: signing out returns to sign-in.
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Sign out").performClick()
        waitFor { shown("Create an account") }
    }
}

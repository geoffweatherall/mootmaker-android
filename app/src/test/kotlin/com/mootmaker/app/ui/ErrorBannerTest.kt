package com.mootmaker.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.mootmaker.app.ui.settings.SectionStatus
import com.mootmaker.app.ui.settings.SettingsState
import com.mootmaker.app.ui.settings.settingsBannerErrors
import com.mootmaker.app.ui.theme.MootmakerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared [ErrorBanner] on its own: the rule's guarantees, independent of any screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h600dp")
class ErrorBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersNothingWithoutMessages() {
        compose.setContent { MootmakerTheme { ErrorBanner(emptyList(), onDismiss = {}, onRetry = {}) } }
        assertTrue(compose.onAllNodes(hasContentDescription("Dismiss")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun showsEveryMessageAndDismissAndOnlyOffersTryAgainWhenAsked() {
        var dismissed = 0
        compose.setContent {
            MootmakerTheme { ErrorBanner(listOf("First problem.", "Second problem."), onDismiss = { dismissed++ }) }
        }
        compose.onNodeWithText("First problem.").assertIsDisplayed()
        compose.onNodeWithText("Second problem.").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Try again")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun tryAgainRunsTheRetry() {
        var retried = 0
        compose.setContent {
            MootmakerTheme { ErrorBanner(listOf("Offline."), onDismiss = {}, onRetry = { retried++ }) }
        }
        compose.onAllNodes(androidx.compose.ui.test.hasText("Try again"))[0].performClick()
        assertEquals(1, retried)
    }

    // At 200% font a handful of messages outgrow a third of the screen; the banner scrolls inside itself.
    @Test
    fun atLargestFontTheLastMessageIsReachableByScrollingTheBanner() {
        val messages = (1..8).map { "Problem number $it needs your attention before this can be saved." }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MootmakerTheme { ErrorBanner(messages, onDismiss = {}) }
            }
        }
        compose.onNodeWithText(messages.first()).assertIsDisplayed()
        compose.onNodeWithText(messages.last()).assertIsNotDisplayed()

        compose.onNodeWithText(messages.last()).performScrollTo().assertIsDisplayed()
        // The dismiss control stays put while the messages scroll.
        compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed()
    }

    @Test
    fun settingsPrefixesEachSectionsErrorsWithItsTitleInPageOrder() {
        val state = SettingsState(
            nameStatus = SectionStatus(errors = listOf("Name must not be blank.", "Name is too long.")),
            formatStatus = SectionStatus(errors = listOf("Pick a format.")),
            avatarStatus = SectionStatus(errors = listOf("That image could not be read.")),
        )
        assertEquals(
            listOf(
                "Photo: That image could not be read.",
                "Your name: Name must not be blank.",
                "Your name: Name is too long.",
                "Date and time format: Pick a format.",
            ),
            settingsBannerErrors(state),
        )
        assertEquals(emptyList<String>(), settingsBannerErrors(SettingsState(nameStatus = SectionStatus(success = "Saved"))))
    }
}

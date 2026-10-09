package com.mootmaker.app.ui

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Use cases I.74 to I.76 and N.100 to N.105 (and the avatar half of Q.143) through the real app wiring, against [FakeBackend]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SettingsFlowTest {
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

    private fun openSettings() {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        button("Sign in").performClick()
        compose.waitUntil(5_000) { shown("Rooms today") }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Settings")).performClick()
        waitForText("Your name")
    }

    private fun choose(field: String, option: String) {
        compose.onNode(hasText(field) and hasClickAction()).performClick()
        compose.onAllNodes(hasText(option) and hasClickAction()).onFirstNode().performClick()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirstNode() = get(0)

    // Use case I.74: a new name is saved and shown straight away.
    @Test
    fun changingYourNameSavesItAndKeepsItInTheField() {
        openSettings()
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("Patricia Example")
        button("Save name").performClick()

        waitForText("Your name was updated.")
        assertEquals("Patricia Example", backend.personName)
    }

    // Use case I.75: a blank name is refused, and the stored name does not change.
    @Test
    fun aBlankNameIsRefused() {
        openSettings()
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("")
        button("Save name").performClick()

        waitForText("Your name: Name must not be blank.")
        assertEquals("Pat Example", backend.personName)
    }

    // Use cases N.100, N.101 and N.102: both formats are saved in one action, with a confirmation.
    @Test
    fun theDateAndTimeFormatsSaveTogether() {
        openSettings()
        choose("Date format", "24/08/2026")
        choose("Time format", "02:30 PM")
        button("Save formats").performClick()

        waitForText("Your date and time formats were updated.")
        assertEquals("British/AmPm/Monday", backend.lastPreferences)
    }

    // Use case N.104/N.100: what you saved is what every other screen shows you.
    @Test
    fun aSavedFormatIsUsedByTheOtherScreens() {
        backend.meetings = listOf(FakeBackend.meeting("m1", "Planning", java.time.LocalDate.now(), 15))
        openSettings()
        choose("Time format", "02:30 PM")
        button("Save formats").performClick()
        waitForText("Your date and time formats were updated.")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("03:00 PM", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    // Use cases I.76 and N.105: an account with no Person gets disabled controls and a reason, not a failing save.
    @Test
    fun anAccountWithNoPersonCannotChangeAnything() {
        backend.personId = null
        openSettings()

        waitForText("Your account has no linked person yet, so your name can't be changed here.")
        assertTrue(shown("Your account has no linked person yet, so these can't be changed here."))
        compose.onNode(hasText("Save name") and hasClickAction()).assert(isNotEnabled())
        compose.onNode(hasText("Save formats") and hasClickAction()).assert(isNotEnabled())
        compose.onNode(hasText("Name") and isNotEnabled()).assertExists()
    }

    // Use case Q.143: someone with an avatar is shown with it, and removing it falls back to initials.
    @Test
    fun anAvatarIsShownAndCanBeRemoved() {
        backend.avatarUrl = "https://${FakeBackend.AVATAR_HOST}/v1/person-1/abc.png"
        openSettings()
        try {
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Photo of Pat Example")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("requests: ${backend.requests}", e)
        }

        button("Remove photo").performClick()

        waitForText("Your photo was removed.")
        assertNull(backend.avatarUrl)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Initials of Pat Example")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Remove photo")).fetchSemanticsNodes().isEmpty())
    }

    // Use case Q.143: with no avatar, a person is shown by their initials.
    @Test
    fun aPersonWithNoAvatarIsShownByInitials() {
        openSettings()
        assertTrue(compose.onAllNodes(hasContentDescription("Initials of Pat Example")).fetchSemanticsNodes().isNotEmpty())
    }
}

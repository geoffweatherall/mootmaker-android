package com.mootmaker.app.acceptance

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Use cases I.74 to I.76, N.100 to N.102, N.105 and Q.143 against a real environment. See [Acceptance].
 *
 * The fixture users are shared with the other acceptance cases, so every case that changes one puts
 * it back as it found it, in a `finally`: there is no sign-up in the app yet (M8), so there is no
 * fresh account to spend instead, as the webapp's suite does.
 */
class SettingsAcceptanceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @After
    fun tearDown() {
        scenario.close()
    }

    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun openSettings(account: Acceptance.Account) {
        scenario = Acceptance.launchApp()
        compose.signIn(account)
        compose.waitForText("Rooms today")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Settings")).performClick()
        compose.waitForText("Your name")
    }

    private fun choose(field: String, option: String) {
        compose.onNode(hasText(field) and hasClickAction()).performScrollTo().performClick()
        compose.onAllNodes(hasText(option) and hasClickAction()).onFirst().performClick()
    }

    /** I.74: a new name is saved, and Home shows it straight away. */
    @Test
    fun changingYourNameSavesItAndHomeShowsIt() {
        val original = Api(Acceptance.standard).myName()
        val renamed = "Renamed ${UUID.randomUUID().toString().take(6)}"
        try {
            openSettings(Acceptance.standard)
            compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement(renamed)
            button("Save name").performScrollTo().performClick()

            compose.waitForText("Your name was updated.")
            assertEquals(renamed, Api(Acceptance.standard).myName())
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForText(renamed)
        } finally {
            Api(Acceptance.standard).updateMyName(original)
        }
    }

    /** I.75: a blank name is refused, and the stored name does not change. */
    @Test
    fun aBlankNameIsRefused() {
        val original = Api(Acceptance.standard).myName()
        openSettings(Acceptance.standard)
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextReplacement("")
        button("Save name").performScrollTo().performClick()

        compose.waitForText("Name must not be blank.")
        assertEquals(original, Api(Acceptance.standard).myName())
    }

    /** I.76 and N.105: no linked Person means disabled controls with a reason, not a save that fails. */
    @Test
    fun anAccountWithNoPersonCannotChangeAnything() {
        openSettings(Acceptance.noPerson)

        compose.waitForText("Your account has no linked person yet, so your name can't be changed here.")
        compose.waitForText("Your account has no linked person yet, so these can't be changed here.")
        button("Save name").performScrollTo().assertIsNotEnabled()
        button("Save formats").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Name") and isNotEnabled()).assertExists()
    }

    /** N.100, N.101 and N.102: both formats save in one action, persist, and are what the agenda then shows. */
    @Test
    fun theFormatsSaveTogetherPersistAndChangeTheAgenda() {
        val api = Api(Acceptance.admin)
        val original = api.preferences().split("/")
        val run = UUID.randomUUID().toString().take(6)
        val tomorrow = LocalDate.now().plusDays(1)
        api.createMeeting(api.createRoom("Settings $run"), api.myPersonId(), "Formats $run", "${tomorrow}T15:00:00", "${tomorrow}T16:00:00")
        try {
            openSettings(Acceptance.admin)
            choose("Date format", "24/08/2026")
            choose("Time format", "02:30 PM")
            button("Save formats").performScrollTo().performClick()

            compose.waitForText("Your date and time formats were updated.")
            assertEquals("British/AmPm/${original[2]}", api.preferences())
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForTextContaining("03:00 PM–04:00 PM")
        } finally {
            api.setPreferences(original[0], original[1], original[2])
        }
    }

    /** Q.143: someone with an avatar is shown with it; removing it falls back to initials. */
    @Test
    fun anAvatarIsShownAndCanBeRemoved() {
        val api = Api(Acceptance.admin)
        val me = api.myPersonId()
        val name = api.myName()
        api.setAvatar(me, solidPng())
        try {
            openSettings(Acceptance.admin)
            compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Photo of $name")).fetchSemanticsNodes().isNotEmpty() }

            button("Remove photo").performClick()

            compose.waitForText("Your photo was removed.")
            assertNull(api.avatarUrl())
            compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Initials of $name")).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            api.removeAvatar(me)
        }
    }

    /** A valid avatar: the API refuses anything under 64 by 64 pixels. */
    private fun solidPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(30, 120, 200)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

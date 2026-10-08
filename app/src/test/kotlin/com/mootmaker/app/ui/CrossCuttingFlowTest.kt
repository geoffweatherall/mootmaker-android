package com.mootmaker.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
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
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch

/**
 * The cross-cutting use cases M.92 to M.96 through the whole app against [FakeBackend]: they need a
 * slow, unreachable or refusing backend on demand, which a real environment cannot be made into.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class CrossCuttingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend().apply {
        meetings = listOf(FakeBackend.meeting("m1", "Stand-up", LocalDate.now(), if (java.time.LocalTime.now().hour == 9) 15 else 9))
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        backend.holdGraphql?.countDown()
        scenario?.close()
    }

    /** Home holds today and the next two days; the third day after today is the first it doesn't. */
    private fun toAnUnheldDay() = repeat(3) { compose.onNodeWithContentDescription("Next day").performClick() }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun spinnerShown() = compose.onAllNodes(hasContentDescription("Loading")).fetchSemanticsNodes().isNotEmpty()

    /** Any progress indicator, the slim bar included: they all carry a progress range. */
    private fun progressShown() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty()

    private lateinit var container: com.mootmaker.app.AppContainer

    private fun signIn() {
        container = useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")
    }

    /**
     * M.92 over the store: a day already held draws at once; one never seen shows the centred spinner,
     * never another day's rooms or "no meetings"; and a refetch keeps the content up under a slim bar.
     */
    @Test
    fun aFirstLoadSpinsAndAReloadKeepsTheOldContentUnderABar() {
        signIn()

        // Today is already held from Home, so Rooms today draws at once, with no request (#22).
        val before = backend.requests.size
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        assertFalse(spinnerShown())
        assertEquals(before, backend.requests.size)

        // A day never seen: the spinner until it lands (#25).
        backend.holdGraphql = CountDownLatch(1)
        toAnUnheldDay()
        compose.waitUntil(5_000) { spinnerShown() }
        assertFalse(shown("Boardroom"))
        backend.holdGraphql!!.countDown()
        waitForText("Boardroom")
        assertFalse(spinnerShown())

        // A change to the shown day refetches it under the slim bar, with its rooms kept up.
        backend.holdGraphql = CountDownLatch(1)
        container.workspace.invalidateDays(listOf(LocalDate.now().plusDays(3)))
        compose.waitUntil(5_000) { progressShown() }
        assertTrue(shown("Boardroom"))
        assertFalse(spinnerShown())
        backend.holdGraphql!!.countDown()
        compose.waitUntil(5_000) { !progressShown() }
    }

    /** M.93: an unreachable API says so in words, and the screen around the message still works. */
    @Test
    fun anUnreachableApiShowsAReadableMessage() {
        signIn()
        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")

        backend.networkDown = true
        toAnUnheldDay()

        waitForText("Couldn't reach Mootmaker. Check your connection and try again.")
        assertTrue(shown("Try again"))
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Stand-up")
    }

    /** M.94: once the session can't be refreshed, the next call returns to sign-in, which says why. */
    @Test
    fun anExpiredSessionReturnsToSignInSayingSo() {
        // Tokens that expire within the refresh margin, so the next API call has to refresh first.
        backend.idTokenExpiresAt = Instant.now().plusSeconds(60)
        signIn()

        compose.onNodeWithText("Rooms today").performClick()
        waitForText("Boardroom")
        backend.refreshRefused = true
        // A day not held, so this is the next API call.
        toAnUnheldDay()

        waitForText("Your session has expired. Sign in again.")
        assertTrue(shown("Create an account"))
        assertTrue(backend.requests.contains("cognito InitiateAuth"))

        // Signing in again clears the notice.
        backend.refreshRefused = false
        backend.idTokenExpiresAt = Instant.parse("2030-01-01T00:00:00Z")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")
        assertFalse(shown("Your session has expired. Sign in again."))
    }

    /** M.96, light half: with the system light, sign-in, home and settings use the light background. */
    @Test
    fun theLightThemeFollowsTheSystem() = assertBackgrounds(LIGHT_BACKGROUND)

    /** M.96, dark half: the same screens follow a dark system setting, with nothing to switch in the app. */
    @Test
    @Config(qualifiers = "+night")
    fun theDarkThemeFollowsTheSystem() = assertBackgrounds(DARK_BACKGROUND)

    private fun assertBackgrounds(expected: Color) {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        assertEquals("sign-in", expected.toArgb(), backgroundPixel())

        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Stand-up")
        assertEquals("home", expected.toArgb(), backgroundPixel())

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        waitForText("Date format")
        assertEquals("settings", expected.toArgb(), backgroundPixel())
    }

    /**
     * A pixel in the left margin, three quarters down, where every screen shows only its background.
     * Drawn from the window rather than captured through Compose, which waits for an idleness that a
     * focused text field's blinking cursor never gives it.
     */
    private fun backgroundPixel(): Int {
        var pixel = 0
        scenario!!.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            pixel = bitmap.getPixel(2, view.height * 3 / 4)
        }
        return pixel
    }

    private companion object {
        // Theme.kt's background tokens.
        val LIGHT_BACKGROUND = Color(0xFFFAF9F6)
        val DARK_BACKGROUND = Color(0xFF17152A)
    }
}

package com.mootmaker.app.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.FakeLiveUpdates
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.data.live.LiveEvent
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakePerson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Live updates through the real app wiring: a broadcast makes an open screen refetch, with no
 * action from the user. The channel is [FakeLiveUpdates]; the AppSync protocol itself is covered
 * by AppSyncRealtimeTest and, against the real service, by the acceptance suite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class LiveUpdatesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val today = LocalDate.now()
    private val live = FakeLiveUpdates()
    private val backend = FakeBackend().apply {
        otherPeople = listOf(FakePerson("person-2", "Sam Other"))
        meetings = listOf(FakeBackend.meeting("first", "First meeting", today, 9))
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun signInToHome() {
        useFakeBackend(backend, live)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("First meeting")
    }

    private fun broadcast(vararg dates: String) = runBlocking { live.push(LiveEvent.DaysChanged(dates.toList())) }

    // M6: a meeting someone else books appears on the open home screen without a refresh.
    @Test
    fun aMeetingBookedByAnotherClientAppearsOnHome() {
        signInToHome()
        val loads = backend.requests.count { it == "graphql Home" }

        backend.meetings = backend.meetings + FakeBackend.meeting("second", "Booked elsewhere", today, 11)
        broadcast(today.toString())

        waitForText("Booked elsewhere")
        assertTrue(backend.requests.count { it == "graphql Home" } > loads)
    }

    // Reconnecting says nothing about what was missed, so it refetches too.
    @Test
    fun aFreshSubscriptionRefetchesWhatIsOnScreen() {
        signInToHome()

        backend.meetings = backend.meetings + FakeBackend.meeting("second", "Missed while away", today, 11)
        runBlocking { live.push(LiveEvent.Subscribed) }

        waitForText("Missed while away")
    }

    // Use cases M.122 and M.123: an edit or cancellation by another client reaches an open details screen.
    @Test
    fun anOpenMeetingFollowsAnEditAndThenACancellation() {
        signInToHome()
        compose.onAllNodes(hasText("First meeting")).onFirst().performClick()
        waitForText("Attendees · 0")

        backend.meetings = backend.meetings.map { it.copy(subject = "First meeting, moved", version = it.version + 1) }
        broadcast(today.toString())
        waitForText("First meeting, moved")

        backend.meetings = emptyList()
        broadcast(today.toString())
        waitForText("Meeting not found.")
    }
}

package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakeRoom
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

/** Use cases F.38 to F.56 through the real app wiring, against [FakeBackend]'s booking rules. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class AddMeetingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val backend = FakeBackend().apply {
        rooms = listOf(FakeRoom("room-1", "Boardroom", capacity = 8), FakeRoom("room-2", "Atrium", capacity = 2))
        otherPeople = listOf(FakePersonSam, FakePersonRobin)
    }
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun openForm() {
        useFakeBackend(backend)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("demo@mootmaker.com")
        compose.onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitForText("Add meeting")
        compose.onNodeWithText("Add meeting").performClick()
        waitForText("Organiser")
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun pick(label: String, option: String) {
        field(label).performClick()
        compose.onNode(hasText(option) and hasClickAction()).performClick()
    }

    private fun typeSubject(subject: String) = compose.onNode(hasText("Subject") and hasSetTextAction()).performTextInput(subject)

    private fun save() = compose.onNode(hasText("Save") and hasClickAction()).performClick()

    // Use cases F.38 and F.39: booking a meeting lands on its details, and the organiser was you from the start.
    @Test
    fun aBookedMeetingOpensItsDetails() {
        openForm()
        compose.onNodeWithText("Pat Example").assertIsDisplayed()

        typeSubject("Planning day")
        pick("Room", "Boardroom (capacity 8)")
        save()

        waitForText("Planning day")
        waitForText("Attendees · 0")
        val booked = backend.meetings.single()
        assertEquals("Planning day", booked.subject)
        assertEquals("room-1", booked.roomId)
        assertEquals("person-1", booked.organiserId)
        assertEquals(LocalDate.now().toString(), booked.startTime.take(10))
        assertTrue("graphql CreateMeeting" in backend.requests)

        // Back returns to where Add meeting was opened, not to the form.
        compose.onNodeWithContentDescription("Back").performClick()
        waitForText("Rooms today")
    }

    // Use case F.44 and the attendees on the saved meeting.
    @Test
    fun attendeesAreChosenFromEveryoneExceptTheOrganiser() {
        openForm()
        field("Attendees").performClick()
        // The organiser is you, so you can't also be an attendee.
        assertTrue(compose.onAllNodes(hasText("Pat Example") and hasClickAction()).fetchSemanticsNodes().size == 1) // the Organiser field itself
        compose.onNodeWithText("Sam Other").performClick()
        compose.onNodeWithText("Done").performClick()
        waitForText("Sam Other")

        // Sam is attending, so Sam is no longer offered as the organiser (the one clickable "Sam Other" is the Attendees field).
        field("Organiser").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Robin Guest") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, compose.onAllNodes(hasText("Sam Other") and hasClickAction()).fetchSemanticsNodes().size)
    }

    // Use cases F.46, F.47 and F.51: every broken rule in one banner, and the form stays put.
    @Test
    fun aBookingWithNothingFilledInListsEveryProblemTogether() {
        openForm()
        save()

        waitForText("Please enter a subject.")
        assertTrue(shown("Please select a room."))
        assertTrue(backend.meetings.isEmpty())
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertTrue(!shown("Please enter a subject."))
    }

    // Use case F.49: a room too small for the people.
    @Test
    fun aRoomThatIsTooSmallIsRejectedByTheServer() {
        openForm()
        typeSubject("Crowded")
        field("Attendees").performClick()
        compose.onNodeWithText("Sam Other").performClick()
        compose.onNodeWithText("Robin Guest").performClick()
        compose.onNodeWithText("Done").performClick()
        pick("Room", "Atrium (capacity 2)")
        save()

        waitForText("The room does not have enough capacity for all attendees.")
    }

    // Use case F.50: the room is already taken for that time.
    @Test
    fun aRoomAlreadyBookedForTheTimeIsRejected() {
        val today = LocalDate.now()
        backend.meetings = listOf(
            FakeBackend.meeting("busy", "Busy all day", today, 0).copy(endTime = "${today}T23:45:00", roomId = "room-1"),
        )
        openForm()
        typeSubject("Clash")
        pick("Room", "Boardroom (capacity 8)")
        save()

        waitForText("The room already has a meeting scheduled during that time range.")
    }

    // Use cases F.52 and F.53: Suggest a room fills the best fit, and says so when nothing is free.
    @Test
    fun suggestARoomFillsTheBestFitThenCyclesAndSaysWhenNothingIsFree() {
        openForm()
        compose.onNodeWithText("Suggest a room").performClick()
        // Smallest room that holds one person is the Atrium (capacity 2).
        waitForText("Atrium (capacity 2)")
        compose.onNodeWithText("Suggest a room").performClick()
        waitForText("Boardroom (capacity 8)")
        compose.onNodeWithText("Suggest a room").performClick()
        waitForText("Atrium (capacity 2)")
        assertEquals(1, backend.requests.count { it == "graphql SuggestRoom" })

        // Three people fit nowhere once every room holds two: the message shows and the field keeps its room.
        field("Attendees").performClick()
        compose.onNodeWithText("Sam Other").performClick()
        compose.onNodeWithText("Robin Guest").performClick()
        compose.onNodeWithText("Done").performClick()
        backend.rooms = backend.rooms.map { it.copy(capacity = 2) }
        compose.onNodeWithText("Suggest a room").performClick()
        waitForText("No suitable room is available for that time - try adjusting the attendees or time.")
        assertTrue(shown("Atrium (capacity 2)"))
        assertEquals(2, backend.requests.count { it == "graphql SuggestRoom" })
    }

    // Use case F.55: Back discards the form.
    @Test
    fun backDiscardsTheFormAndReturnsHome() {
        openForm()
        typeSubject("Never saved")
        compose.onNodeWithContentDescription("Back").performClick()

        waitForText("Rooms today")
        assertTrue(backend.meetings.isEmpty())
    }
}

private val FakePersonSam = com.mootmaker.testing.FakePerson("person-2", "Sam Other")
private val FakePersonRobin = com.mootmaker.testing.FakePerson("person-3", "Robin Guest")

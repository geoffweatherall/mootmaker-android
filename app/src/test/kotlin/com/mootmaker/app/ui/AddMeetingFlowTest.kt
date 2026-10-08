package com.mootmaker.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.mootmaker.app.MainActivity
import com.mootmaker.app.useFakeBackend
import com.mootmaker.testing.FakeBackend
import com.mootmaker.testing.FakeRoom
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
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

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
        compose.onNodeWithText("Dismiss").performClick()
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

    // --- The time dial (issue #28, option 2) ------------------------------------------------------

    /** The text a form field shows, read from its (disabled) text field. */
    private fun fieldValue(label: String): String =
        field(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun openTimeDialog(label: String) {
        field(label).performClick()
        waitForText("Select time")
    }

    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction())

    /** The dial's minute label for [minute] (the header's value text carries the same description, higher up). */
    private fun dialLabel(description: String): SemanticsNode =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().maxBy { it.boundsInRoot.center.y }

    /** Touches go to the dialog's window through any node in it; positions are relative to that node. */
    private val dialog get() = compose.onNode(hasText("Select time"))
    private fun dialogOrigin() = compose.onAllNodes(hasText("Select time")).fetchSemanticsNodes().single().boundsInRoot.topLeft

    /** A real tap on the dial, at the label described so ("9 hours", "30 minutes"). */
    private fun tapDial(description: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
        val at = dialLabel(description).boundsInRoot.center - dialogOrigin()
        dialog.performTouchInput { click(at) }
        compose.waitForIdle()
    }

    /** The minute the header shows, as text ("00", "15"...). */
    private fun headerMinute(): String =
        compose.onNode(hasContentDescription("Select minutes")).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** The minute label the dial highlights, by its description ("45 minutes"). */
    private fun highlightedMinute(): String =
        compose.onAllNodes(hasContentDescription(" minutes", substring = true)).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsProperties.ContentDescription)?.size == 1 }
            .joinToString { it.config[SemanticsProperties.ContentDescription].single() }

    // Use case F.41 through the dial: pick an hour and a minute, OK, and the form shows it.
    @Test
    fun aTimePickedOnTheDialFillsTheField() {
        openForm()
        val end = fieldValue("End time")
        openTimeDialog("Start time")
        tapDial("3 hours")
        tapDial("15 minutes")
        assertEquals("15", headerMinute())
        dialogButton("OK").performClick()

        waitForText("03:15")
        assertEquals("03:15", fieldValue("Start time"))
        assertEquals(end, fieldValue("End time"))
    }

    @Test
    fun cancelLeavesTheTimeAsItWas() {
        openForm()
        val before = fieldValue("Start time")
        openTimeDialog("Start time")
        tapDial(if (before.startsWith("03")) "4 hours" else "3 hours")
        tapDial("45 minutes")
        dialogButton("Cancel").performClick()

        compose.waitUntil(5_000) { !shown("Select time") }
        assertEquals(before, fieldValue("Start time"))
    }

    // A tap on the dial rounds to 5 minutes; the form's dial goes on to the nearest quarter.
    @Test
    fun aTapBetweenQuartersGoesToTheNearestQuarter() {
        openForm()
        openTimeDialog("Start time")
        tapDial("3 hours")
        tapDial("10 minutes")
        assertEquals("15", headerMinute())
        assertEquals("15 minutes", highlightedMinute())
        tapDial("50 minutes")
        assertEquals("45", headerMinute())
        dialogButton("OK").performClick()
        waitForText("03:45")
    }

    /**
     * The experiment issue #28 asks for: a drag gives any minute, and the form holds it to quarter
     * hours as it moves. The header and highlight only ever show a quarter, with no fight or jitter;
     * the hand follows the finger, and on release lands on the quarter the header shows (the PNG shows
     * the hand). OK books that quarter.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun aDragOffTheQuarterLandsOnTheNearestQuarter() {
        openForm()
        openTimeDialog("Start time")
        tapDial("3 hours")
        // The minute dial is up once its labels are.
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("55 minutes")).fetchSemanticsNodes().isNotEmpty() }

        val top = dialLabel("0 minutes").boundsInRoot.center
        val bottom = dialLabel("30 minutes").boundsInRoot.center
        val centre = Offset((top.x + bottom.x) / 2, (top.y + bottom.y) / 2)
        val radius = (bottom.y - top.y) / 2
        val origin = dialogOrigin()
        fun at(minute: Int): Offset {
            val angle = Math.toRadians(minute * 6.0)
            return Offset(centre.x + radius * sin(angle).toFloat(), centre.y - radius * cos(angle).toFloat()) - origin
        }

        val seen = mutableListOf<String>()
        dialog.performTouchInput { down(at(2)) }
        for (minute in 3..40) {
            dialog.performTouchInput { moveTo(at(minute)) }
            compose.waitForIdle()
            seen += headerMinute()
        }
        // Every reading on the way was a quarter, and once the drag was under way (past the touch slop)
        // the header stepped through each quarter in turn, never back.
        assertTrue("header showed $seen", seen.all { it in listOf("00", "15", "30", "45") })
        assertTrue("header showed $seen", "00" in seen)
        assertEquals("header showed $seen", listOf("15", "30", "45"), seen.drop(7).distinct()) // from minute 10 on
        dialog.performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("45", headerMinute())
        assertEquals("45 minutes", highlightedMinute())
        captureScreenRoboImage("src/test/screenshots/time-dial-after-drag.png")
        dialogButton("OK").performClick()
        waitForText("03:45")
        assertEquals("03:45", fieldValue("Start time"))
    }

    // Typed input: an off-quarter minute is booked as the nearest quarter, the hour as typed.
    @Test
    fun aTypedTimeOffTheQuarterIsBookedOnTheNearestQuarter() {
        openForm()
        openTimeDialog("End time")
        // A 24-hour account: no AM or PM.
        assertFalse(compose.onAllNodes(hasContentDescription("Select AM or PM")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasContentDescription("for hour") and hasSetTextAction()).performTextReplacement("14")
        compose.onNode(hasContentDescription("for minutes") and hasSetTextAction()).performTextReplacement("37")
        dialogButton("OK").performClick()

        waitForText("14:30")
        assertEquals("14:30", fieldValue("End time"))
    }

    // Use case N.103: on a 12-hour clock the dial has AM and PM, and a typed afternoon time books as such.
    @Test
    fun aTwelveHourAccountTypesTheTimeWithAmOrPm() {
        backend.timeFormat = "AmPm"
        openForm()
        openTimeDialog("Start time")
        // Twelve hours with AM and PM, not two rings of 24.
        assertTrue(compose.onAllNodes(hasContentDescription("Select AM or PM")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasContentDescription("for hour") and hasSetTextAction()).performTextReplacement("2")
        compose.onNode(hasContentDescription("for minutes") and hasSetTextAction()).performTextReplacement("50")
        dialogButton("PM").performClick()
        dialogButton("OK").performClick()

        waitForText("02:45 PM")
        assertEquals("02:45 PM", fieldValue("Start time"))
    }
}

private val FakePersonSam = com.mootmaker.testing.FakePerson("person-2", "Sam Other")
private val FakePersonRobin = com.mootmaker.testing.FakePerson("person-3", "Robin Guest")

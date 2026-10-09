package com.mootmaker.app.ui

import androidx.compose.ui.geometry.Offset
import com.mootmaker.data.meeting.labelAngle
import com.mootmaker.data.meeting.DialRing
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onFirst
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

    // --- Filtering people (issue #27) ------------------------------------------------------------

    private fun filterBox() = compose.onNode(hasText("Filter by name") and hasSetTextAction())

    private fun inPicker(name: String) = compose.onAllNodes(hasText(name) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun listed(name: String) = inPicker(name).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theAttendeesFilterNarrowsTheListByPartOfAName() {
        openForm()
        field("Attendees").performClick()
        waitForText("Filter by name")
        assertTrue(listed("Sam Other") && listed("Robin Guest"))

        filterBox().performTextInput("OBI")
        compose.waitForIdle()
        assertTrue(listed("Robin Guest"))
        assertFalse(listed("Sam Other"))

        filterBox().performTextReplacement("zzz")
        compose.waitForIdle()
        assertTrue(shown("No one matches"))
        assertFalse(listed("Robin Guest"))

        filterBox().performTextReplacement("")
        compose.waitForIdle()
        assertTrue(listed("Sam Other") && listed("Robin Guest"))
        assertFalse(shown("No one matches"))
    }

    @Test
    fun peopleTickedStayTickedWhileTheFilterHidesThemAndTheCountFollows() {
        openForm()
        field("Attendees").performClick()
        waitForText("Filter by name")
        assertFalse(shown("Attendees (1)"))

        filterBox().performTextInput("sam")
        compose.waitForIdle()
        inPicker("Sam Other").onFirst().performClick()
        waitForText("Attendees (1)")

        filterBox().performTextReplacement("robin")
        compose.waitForIdle()
        assertFalse(listed("Sam Other"))
        assertTrue(shown("Attendees (1)"))
        inPicker("Robin Guest").onFirst().performClick()
        waitForText("Attendees (2)")

        filterBox().performTextReplacement("")
        compose.waitForIdle()
        inPicker("Sam Other").onFirst().performClick()
        waitForText("Attendees (1)")
        compose.onNodeWithText("Done").performClick()

        field("Attendees").performClick()
        waitForText("Attendees (1)")
        compose.onNodeWithText("Done").performClick()
        typeSubject("Filtered")
        pick("Room", "Boardroom (capacity 8)")
        save()
        waitForText("Attendees · 1")
        assertEquals(listOf("person-3"), backend.meetings.single().attendeeIds)
    }

    @Test
    fun theOrganiserPickerFiltersAndChoosingClosesIt() {
        openForm()
        field("Organiser").performClick()
        waitForText("Filter by name")
        assertTrue(listed("Pat Example") && listed("Sam Other") && listed("Robin Guest"))

        filterBox().performTextInput("sam")
        compose.waitForIdle()
        assertFalse(listed("Robin Guest"))
        assertFalse(listed("Pat Example"))
        inPicker("Sam Other").onFirst().performClick()

        compose.waitUntil(5_000) { !shown("Filter by name") }
        assertEquals("Sam Other", fieldValue("Organiser"))
        typeSubject("Sam's meeting")
        pick("Room", "Boardroom (capacity 8)")
        save()
        waitForText("Sam's meeting")
        assertEquals("person-2", backend.meetings.single().organiserId)
    }

    @Test
    fun theOrganiserPickerSaysWhenNoOneMatches() {
        openForm()
        field("Organiser").performClick()
        waitForText("Filter by name")
        filterBox().performTextInput("nobody")
        compose.waitForIdle()
        assertTrue(shown("No one matches"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { !shown("Filter by name") }
        assertEquals("Pat Example", fieldValue("Organiser"))
    }

    // Screenshots of the pickers with a filter typed, through the real app so the dialogs' windows are captured.
    @OptIn(ExperimentalRoborazziApi::class)
    private fun capturePicker(name: String, label: String, typed: String) {
        openForm()
        field(label).performClick()
        waitForText("Filter by name")
        filterBox().performTextInput(typed)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun attendeesFilteredScreenshot() = capturePicker("attendees-filtered", "Attendees", "ob")

    @Test
    @Config(qualifiers = "+night")
    fun attendeesFilteredDarkScreenshot() = capturePicker("attendees-filtered-dark", "Attendees", "ob")

    @Test
    fun attendeesNoMatchScreenshot() = capturePicker("attendees-no-match", "Attendees", "zzz")

    @Test
    fun organiserPickerScreenshot() = capturePicker("organiser-picker", "Organiser", "a")

    @Test
    @Config(qualifiers = "+night")
    fun organiserPickerDarkScreenshot() = capturePicker("organiser-picker-dark", "Organiser", "a")

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

    // --- The time dial (issue #28, option 3) ------------------------------------------------------

    /** The text a form field shows, read from its (disabled) text field. */
    private fun fieldValue(label: String): String =
        field(label).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun openTimeDialog(label: String) {
        field(label).performClick()
        waitForText("Select time")
    }

    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun dialLabel(description: String) = compose.onNode(hasContentDescription(description))

    private fun isDialLabel(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()?.matches(DialLabelDescription) == true

    /** The clock face: the selectable group whose children are the dial's labels. */
    private fun face() = compose.onNode(
        SemanticsMatcher("is the clock face") { node ->
            SemanticsProperties.SelectableGroup in node.config && node.children.any(::isDialLabel)
        },
    )

    /** The number the header's hour or minute box shows ("09", "45"). */
    private fun header(description: String): String =
        compose.onNode(hasContentDescription(description)).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun headerHour() = header("Select hour")
    private fun headerMinute() = header("Select minutes")

    /** Every dial label marked selected, by its description: there should only ever be one. */
    private fun selectedLabels(): List<String> =
        compose.onAllNodes(SemanticsMatcher("a dial label") { isDialLabel(it) }).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .map { it.config[SemanticsProperties.ContentDescription].single() }

    /** Where [value]'s label on [ring] sits, in the face's own coordinates, worked out from the geometry. */
    private fun TouchInjectionScope.at(value: Int, ring: DialRing): Offset = onRing(labelAngle(value, ring), ring.radiusFraction)

    /** The point at [angle] degrees clockwise from twelve and [fraction] of the radius out, in the face's coordinates. */
    private fun TouchInjectionScope.onRing(angle: Double, fraction: Double): Offset {
        val radius = width / 2f
        val radians = Math.toRadians(angle)
        return Offset(centerX + (radius * fraction * sin(radians)).toFloat(), centerY - (radius * fraction * cos(radians)).toFloat())
    }

    /** A real tap on the face at [value]'s label, found from the geometry rather than from the label's node. */
    private fun tapDial(value: Int, ring: DialRing) {
        face().performTouchInput { click(at(value, ring)) }
        compose.waitForIdle()
    }

    /** The header and the dial agree: the one selected label is the value the header shows. */
    private fun assertHeaderAgreesWithDial(hourDescription: String? = null) {
        val selected = selectedLabels()
        assertEquals("selected labels $selected", 1, selected.size)
        if (selected.single().endsWith(" minutes")) {
            assertEquals("${headerMinute().toInt()} minutes", selected.single())
        } else {
            assertEquals(hourDescription ?: "${headerHour().toInt()} hours", selected.single())
        }
    }

    // Use case F.41 by touch: an hour and a minute tapped where the geometry puts their labels.
    @Test
    fun aTimeTappedOnTheDialFillsTheField() {
        openForm()
        val end = fieldValue("End time")
        openTimeDialog("Start time")
        // A 24-hour account: two rings of hours and no AM or PM.
        assertFalse(shown("AM"))
        dialLabel("0 hours").assertExists()
        dialLabel("23 hours").assertExists()
        tapDial(3, DialRing.OuterHour)
        assertEquals("03", headerHour())
        tapDial(15, DialRing.Minute)
        assertEquals("15", headerMinute())
        assertHeaderAgreesWithDial()
        dialogButton("OK").performClick()

        waitForText("03:15")
        assertEquals("03:15", fieldValue("Start time"))
        assertEquals(end, fieldValue("End time"))
    }

    // The inner ring is the afternoon and evening, as in the Clock app.
    @Test
    fun theInnerRingGivesTheAfternoon() {
        openForm()
        openTimeDialog("End time")
        tapDial(21, DialRing.InnerHour)
        assertEquals("21", headerHour())
        tapDial(45, DialRing.Minute)
        assertHeaderAgreesWithDial()
        dialogButton("OK").performClick()
        waitForText("21:45")
        assertEquals("21:45", fieldValue("End time"))
    }

    // As the Clock app: choosing an hour moves on to the minutes, and the header's hour box goes back.
    @Test
    fun choosingAnHourMovesOnToTheMinutes() {
        openForm()
        openTimeDialog("Start time")
        dialLabel("9 hours").assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription("45 minutes")).fetchSemanticsNodes().isEmpty())
        tapDial(9, DialRing.OuterHour)

        dialLabel("45 minutes").assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription("9 hours")).fetchSemanticsNodes().isEmpty())
        compose.onNode(hasContentDescription("Select minutes")).assertIsSelected()
        // Only the four quarters are on the minute dial.
        assertEquals(
            listOf("0 minutes", "15 minutes", "30 minutes", "45 minutes"),
            compose.onAllNodes(SemanticsMatcher("a dial label") { isDialLabel(it) }).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.ContentDescription].single() },
        )

        compose.onNode(hasContentDescription("Select hour")).performClick()
        dialLabel("9 hours").assertIsSelected()
        assertEquals("09", headerHour())
    }

    /**
     * A drag: the selection jumps from value to value as the finger goes, so the header and the
     * selected label agree at every step, and only ever show allowed values. Released between two
     * quarters, it stays on the nearer, and OK books it.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun aDragSelectsTheNearestValueAllTheWay() {
        openForm()
        openTimeDialog("Start time")

        // Hours: from 9 round to between 10 and 11, then in to the inner ring; letting go moves on.
        face().performTouchInput { down(at(9, DialRing.OuterHour)) }
        assertEquals("09", headerHour())
        val hoursSeen = mutableListOf<String>()
        for (angle in 270..310 step 5) {
            face().performTouchInput { moveTo(onRing(angle.toDouble(), DialRing.OuterHour.radiusFraction)) }
            compose.waitForIdle()
            assertHeaderAgreesWithDial()
            hoursSeen += headerHour()
        }
        assertEquals(listOf("09", "10"), hoursSeen.distinct())
        face().performTouchInput { moveTo(onRing(310.0, DialRing.InnerHour.radiusFraction)) }
        compose.waitForIdle()
        assertEquals("22", headerHour())
        assertHeaderAgreesWithDial()
        face().performTouchInput { up() }
        compose.waitForIdle()
        dialLabel("30 minutes").assertExists()

        // Minutes: from 00 round past 15 to 160 degrees, between 15 and 30 but nearer 30.
        face().performTouchInput { down(at(0, DialRing.Minute)) }
        val minutesSeen = mutableListOf<String>()
        for (angle in 0..160 step 4) {
            face().performTouchInput { moveTo(onRing(angle.toDouble(), DialRing.Minute.radiusFraction)) }
            compose.waitForIdle()
            assertHeaderAgreesWithDial()
            minutesSeen += headerMinute()
        }
        assertEquals(listOf("00", "15", "30"), minutesSeen.distinct())
        face().performTouchInput { up() }
        compose.waitForIdle()

        assertEquals("30", headerMinute())
        assertEquals(listOf("30 minutes"), selectedLabels())
        captureScreenRoboImage("src/test/screenshots/time-dial-after-drag.png")
        dialogButton("OK").performClick()
        waitForText("22:30")
        assertEquals("22:30", fieldValue("Start time"))
    }

    // TalkBack's path: each label's own click picks exactly that value (Material's dial didn't, #33).
    @Test
    fun everyLabelPicksItsOwnValueThroughItsClick() {
        openForm()
        openTimeDialog("Start time")
        dialLabel("14 hours").performClick()
        assertEquals("14", headerHour())
        for (minute in listOf(45, 0, 30, 15, 45)) {
            dialLabel("$minute minutes").performClick()
            assertEquals("%02d".format(minute), headerMinute())
            assertEquals(listOf("$minute minutes"), selectedLabels())
        }
        dialogButton("OK").performClick()
        waitForText("14:45")
        assertEquals("14:45", fieldValue("Start time"))
    }

    @Test
    fun cancelLeavesTheTimeAsItWas() {
        openForm()
        val before = fieldValue("Start time")
        openTimeDialog("Start time")
        tapDial(if (before.startsWith("03")) 4 else 3, DialRing.OuterHour)
        tapDial(45, DialRing.Minute)
        dialogButton("Cancel").performClick()

        compose.waitUntil(5_000) { !shown("Select time") }
        assertEquals(before, fieldValue("Start time"))
    }

    // Use case N.103: on a 12-hour clock the dial is one ring of 1 to 12, with AM and PM.
    @Test
    fun aTwelveHourAccountPicksWithAmAndPm() {
        backend.timeFormat = "AmPm"
        openForm()
        openTimeDialog("Start time")
        compose.onNode(hasContentDescription("Select AM or PM")).assertExists()
        dialLabel("12 hours").assertExists()
        assertTrue(compose.onAllNodes(hasContentDescription("0 hours")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription("13 hours")).fetchSemanticsNodes().isEmpty())
        tapDial(2, DialRing.TwelveHour)
        assertEquals("02", headerHour())
        tapDial(45, DialRing.Minute)
        dialogButton("PM").performClick()
        assertEquals("02", headerHour())
        dialogButton("OK").performClick()
        waitForText("02:45 PM")
        assertEquals("02:45 PM", fieldValue("Start time"))

        // AM moves the same clock hour to the morning.
        openTimeDialog("Start time")
        compose.onNode(hasText("PM") and hasClickAction()).assertIsSelected()
        dialogButton("AM").performClick()
        compose.onNode(hasText("AM") and hasClickAction()).assertIsSelected()
        dialogButton("OK").performClick()
        waitForText("02:45 AM")
        assertEquals("02:45 AM", fieldValue("Start time"))
    }

    // Typed input: an off-quarter minute is booked as the nearest quarter, the hour as typed.
    @Test
    fun aTypedTimeOffTheQuarterIsBookedOnTheNearestQuarter() {
        openForm()
        openTimeDialog("End time")
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasContentDescription("for hour") and hasSetTextAction()).performTextReplacement("14")
        compose.onNode(hasContentDescription("for minutes") and hasSetTextAction()).performTextReplacement("37")
        dialogButton("OK").performClick()

        waitForText("14:30")
        assertEquals("14:30", fieldValue("End time"))
    }

    // Back from typing to the dial, the dial shows what was typed, on the nearest quarter.
    @Test
    fun aTypedTimeCarriesBackToTheDial() {
        openForm()
        openTimeDialog("End time")
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasContentDescription("for hour") and hasSetTextAction()).performTextReplacement("16")
        compose.onNode(hasContentDescription("for minutes") and hasSetTextAction()).performTextReplacement("52")
        compose.onNodeWithContentDescription("Switch to clock input mode").performClick()

        assertEquals("16", headerHour())
        assertEquals("45", headerMinute())
        assertHeaderAgreesWithDial()
        dialogButton("OK").performClick()
        waitForText("16:45")
    }

    // Use case N.103: a typed afternoon time on a 12-hour clock books as such.
    @Test
    fun aTwelveHourAccountTypesTheTimeWithAmOrPm() {
        backend.timeFormat = "AmPm"
        openForm()
        openTimeDialog("Start time")
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasContentDescription("for hour") and hasSetTextAction()).performTextReplacement("2")
        compose.onNode(hasContentDescription("for minutes") and hasSetTextAction()).performTextReplacement("50")
        dialogButton("PM").performClick()
        dialogButton("OK").performClick()

        waitForText("02:45 PM")
        assertEquals("02:45 PM", fieldValue("Start time"))
    }
}

private val DialLabelDescription = Regex("\\d+ (hours|minutes)")

private val FakePersonSam = com.mootmaker.testing.FakePerson("person-2", "Sam Other")
private val FakePersonRobin = com.mootmaker.testing.FakePerson("person-3", "Robin Guest")

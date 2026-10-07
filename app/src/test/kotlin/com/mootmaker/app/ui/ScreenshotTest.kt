package com.mootmaker.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mootmaker.app.ui.addmeeting.AddMeetingActions
import com.mootmaker.app.ui.addmeeting.AddMeetingScreen
import com.mootmaker.app.ui.addmeeting.AddMeetingState
import com.mootmaker.app.ui.calendar.CalendarScreen
import com.mootmaker.app.ui.meeting.MeetingDetailsScreen
import com.mootmaker.app.ui.availability.AvailabilityState
import com.mootmaker.app.ui.availability.AvailabilityScreen
import com.mootmaker.app.ui.home.HomeScreen
import com.mootmaker.app.ui.home.HomeState
import com.mootmaker.app.ui.signin.SignInConfig
import com.mootmaker.app.ui.signin.SignInScreen
import com.mootmaker.app.ui.signin.SignInState
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.api.HomeData
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.PersonOption
import com.mootmaker.data.meeting.RoomOption
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Key screens as PNGs under src/test/screenshots, in light and dark. CI verifies them against the
 * committed files; `./gradlew :app:recordRoborazziDebug` re-records after an intended change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { MootmakerTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun signIn() = capture("sign-in") {
        SignInScreen(
            state = SignInState(email = "demo@mootmaker.com", password = "demo-password"),
            config = SignInConfig.Ready(hasDemoUser = true),
            onEmailChange = {}, onPasswordChange = {}, onSubmit = {}, onRetryConfig = {},
            onCreateAccount = {}, onForgotPassword = {}, onAbout = {},
        )
    }

    @Test
    fun signInWithError() = capture("sign-in-error") {
        SignInScreen(
            state = SignInState(email = "pat@example.com", password = "wrong", error = "Incorrect username or password."),
            config = SignInConfig.Ready(hasDemoUser = false),
            onEmailChange = {}, onPasswordChange = {}, onSubmit = {}, onRetryConfig = {},
            onCreateAccount = {}, onForgotPassword = {}, onAbout = {},
        )
    }

    @Test
    fun homeWithMeetings() = capture("home") {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeWithMeetingsDark() = capture("home-dark", dark = true) {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeEmpty() = capture("home-empty") {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, EMPTY_AGENDA), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeNoLinkedPerson() = capture("home-no-person") {
        HomeScreen(HomeState(TODAY, HomeData(null, TimeFormat.TwentyFourHour, null), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun availability() = capture("availability") {
        AvailabilityScreen(SAMPLE_AVAILABILITY, NO_AVAILABILITY_ACTIONS)
    }

    @Test
    fun availabilityDark() = capture("availability-dark", dark = true) {
        AvailabilityScreen(SAMPLE_AVAILABILITY, NO_AVAILABILITY_ACTIONS)
    }

    @Test
    fun meetingDetails() = capture("meeting-details") {
        MeetingDetailsScreen(meetingState(SAMPLE_MEETING), NO_MEETING_ACTIONS)
    }

    @Test
    fun meetingDetailsDark() = capture("meeting-details-dark", dark = true) {
        MeetingDetailsScreen(meetingState(SAMPLE_MEETING), NO_MEETING_ACTIONS)
    }

    @Test
    fun calendar() = capture("calendar") {
        CalendarScreen(SAMPLE_CALENDAR, NO_CALENDAR_ACTIONS)
    }

    @Test
    fun calendarDark() = capture("calendar-dark", dark = true) {
        CalendarScreen(SAMPLE_CALENDAR, NO_CALENDAR_ACTIONS)
    }

    private val formReference = MeetingFormReference(
        myPersonId = "p1",
        people = listOf(PersonOption("p1", "Pat Example"), PersonOption("p2", "Sam Other"), PersonOption("p3", "Robin Guest")),
        rooms = listOf(RoomOption("r1", "Atrium", 4), RoomOption("r2", "Boardroom", 8)),
        timeFormat = TimeFormat.TwentyFourHour,
        dateFormat = DateFormat.Iso,
    )

    private val filledForm = AddMeetingState(
        reference = formReference,
        loading = false,
        subject = "Design review",
        organiserId = "p1",
        attendeeIds = listOf("p2", "p3"),
        date = LocalDate.of(2026, 10, 8),
        start = LocalTime.of(14, 30),
        end = LocalTime.of(15, 30),
        roomId = "r2",
    )

    private val noFormActions = AddMeetingActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun addMeeting() = capture("add-meeting") {
        AddMeetingScreen(filledForm, noFormActions)
    }

    @Test
    fun addMeetingDark() = capture("add-meeting-dark", dark = true) {
        AddMeetingScreen(filledForm, noFormActions)
    }

    @Test
    fun addMeetingRejected() = capture("add-meeting-rejected") {
        AddMeetingScreen(
            filledForm.copy(roomId = "", errors = listOf("Please select a room.", "The room does not have enough capacity for all attendees.")),
            noFormActions,
        )
    }
}

package com.mootmaker.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.mootmaker.app.ui.about.AboutScreen
import com.mootmaker.data.config.Environment
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.mootmaker.app.ui.admin.PersonEditor
import com.mootmaker.app.ui.admin.PersonsActions
import com.mootmaker.app.ui.admin.PersonsScreen
import com.mootmaker.app.ui.admin.PersonsState
import com.mootmaker.app.ui.admin.RoomEditor
import com.mootmaker.app.ui.admin.RoomsActions
import com.mootmaker.app.ui.admin.RoomsScreen
import com.mootmaker.app.ui.admin.RoomsState
import com.mootmaker.data.admin.AdminPerson
import com.mootmaker.data.admin.AdminRoom
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.app.ui.account.ForgotPasswordActions
import com.mootmaker.app.ui.account.ForgotPasswordScreen
import com.mootmaker.app.ui.account.ForgotPasswordState
import com.mootmaker.app.ui.account.SignUpActions
import com.mootmaker.app.ui.account.SignUpScreen
import com.mootmaker.app.ui.account.SignUpState
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
import com.mootmaker.app.ui.settings.SectionStatus
import com.mootmaker.app.ui.settings.SettingsActions
import com.mootmaker.app.ui.settings.SettingsScreen
import com.mootmaker.app.ui.settings.SettingsState
import com.mootmaker.data.settings.Profile
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

    /**
     * [capture] at Android's largest font size (200%), for the accessibility pass: text wraps and
     * scrolls rather than clipping or overlapping.
     */
    private fun captureLargeFont(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MootmakerTheme { content() }
            }
        }
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
    fun signInDark() = capture("sign-in-dark", dark = true) {
        SignInScreen(
            state = SignInState(email = "demo@mootmaker.com", password = "demo-password"),
            config = SignInConfig.Ready(hasDemoUser = true),
            onEmailChange = {}, onPasswordChange = {}, onSubmit = {}, onRetryConfig = {},
            onCreateAccount = {}, onForgotPassword = {}, onAbout = {},
        )
    }

    @Test
    fun signInAfterExpiry() = capture("sign-in-expired") {
        SignInScreen(
            state = SignInState(),
            config = SignInConfig.Ready(hasDemoUser = false),
            onEmailChange = {}, onPasswordChange = {}, onSubmit = {}, onRetryConfig = {},
            onCreateAccount = {}, onForgotPassword = {}, onAbout = {},
            sessionExpired = true,
        )
    }

    @Test
    fun signInLargeFont() = captureLargeFont("sign-in-large-font") {
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
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, SAMPLE_NEEDS_RESPONSE, windowEnd = TODAY.plusDays(2)), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeWithMeetingsDark() = capture("home-dark", dark = true) {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, SAMPLE_NEEDS_RESPONSE, windowEnd = TODAY.plusDays(2)), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeLargeFont() = captureLargeFont("home-large-font") {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, SAMPLE_NEEDS_RESPONSE, windowEnd = TODAY.plusDays(2)), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeFirstLoad() = capture("home-first-load") {
        HomeScreen(HomeState(TODAY, null, loading = true), null, NO_ACTIONS)
    }

    @Test
    fun homeEmpty() = capture("home-empty") {
        HomeScreen(HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, EMPTY_AGENDA, windowEnd = TODAY.plusDays(2)), loading = false), null, NO_ACTIONS)
    }

    @Test
    fun homeNoLinkedPerson() = capture("home-no-person") {
        HomeScreen(HomeState(TODAY, HomeData(null, TimeFormat.TwentyFourHour, null, windowEnd = TODAY.plusDays(2)), loading = false), null, NO_ACTIONS)
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
    fun meetingDetailsLargeFont() = captureLargeFont("meeting-details-large-font") {
        MeetingDetailsScreen(meetingState(SAMPLE_MEETING), NO_MEETING_ACTIONS)
    }

    @Test
    fun availabilityLargeFont() = captureLargeFont("availability-large-font") {
        AvailabilityScreen(SAMPLE_AVAILABILITY, NO_AVAILABILITY_ACTIONS)
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
    fun addMeetingLargeFont() = captureLargeFont("add-meeting-large-font") {
        AddMeetingScreen(filledForm, noFormActions)
    }

    @Test
    fun addMeetingRejected() = capture("add-meeting-rejected") {
        AddMeetingScreen(
            filledForm.copy(roomId = "", errors = listOf("Please select a room.", "The room does not have enough capacity for all attendees.")),
            noFormActions,
        )
    }

    private val settingsProfile = Profile("p1", "Pat Example", DateFormat.British, TimeFormat.AmPm, "Monday", avatarUrl = null)
    private val settingsState = SettingsState(profile = settingsProfile, loaded = true, name = "Pat Example", dateFormat = DateFormat.British, timeFormat = TimeFormat.AmPm)
    private val noSettingsActions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun settings() = capture("settings") {
        SettingsScreen(settingsState, noSettingsActions)
    }

    @Test
    fun settingsDark() = capture("settings-dark", dark = true) {
        SettingsScreen(settingsState, noSettingsActions)
    }

    @Test
    fun settingsRejected() = capture("settings-rejected") {
        SettingsScreen(settingsState.copy(name = "", nameStatus = SectionStatus(errors = listOf("Name must not be blank.")), formatStatus = SectionStatus(success = "Your date and time formats were updated.")), noSettingsActions)
    }

    private val noSignUpActions = SignUpActions({}, {}, {}, {}, {}, {}, {})
    private val noForgotPasswordActions = ForgotPasswordActions({}, {}, {}, {}, {}, {})

    @Test
    fun signUp() = capture("sign-up") {
        SignUpScreen(SignUpState(name = "Pat Example", email = "pat@example.com", password = "a-good-pw-123"), noSignUpActions)
    }

    @Test
    fun signUpDark() = capture("sign-up-dark", dark = true) {
        SignUpScreen(SignUpState(name = "Pat Example", email = "pat@example.com", password = "a-good-pw-123"), noSignUpActions)
    }

    @Test
    fun signUpRefused() = capture("sign-up-refused") {
        SignUpScreen(
            SignUpState(email = "pat@example.com", password = "short1", missing = setOf("Name"), error = "Password did not conform with policy: Password not long enough"),
            noSignUpActions,
        )
    }

    @Test
    fun signUpCode() = capture("sign-up-code") {
        SignUpScreen(SignUpState(confirming = true, email = "pat@example.com", code = "123"), noSignUpActions)
    }

    @Test
    fun forgotPassword() = capture("forgot-password") {
        ForgotPasswordScreen(ForgotPasswordState(email = "pat@example.com"), noForgotPasswordActions)
    }

    @Test
    fun forgotPasswordReset() = capture("forgot-password-reset") {
        ForgotPasswordScreen(
            ForgotPasswordState(resetting = true, email = "pat@example.com", code = "000000", newPassword = "a-new-pw-456", error = "Invalid verification code provided, please try again."),
            noForgotPasswordActions,
        )
    }

    @Test
    fun forgotPasswordResetDark() = capture("forgot-password-reset-dark", dark = true) {
        ForgotPasswordScreen(ForgotPasswordState(resetting = true, email = "pat@example.com"), noForgotPasswordActions)
    }

    private val adminRooms = listOf(
        AdminRoom("r1", "Atrium", 16, RoomColor.Green, colorSlot = 5),
        AdminRoom("r2", "Boardroom", 6, null, colorSlot = 1),
        AdminRoom("r3", "The Hub", 4, RoomColor.Violet),
    )
    private val adminPeople = listOf(
        AdminPerson("p1", "Pat Example", isAdmin = true, linkedEmails = listOf("pat@example.com"), avatarUrl = null, isSelf = true),
        AdminPerson("p2", "Guest Gale", isAdmin = false, linkedEmails = emptyList(), avatarUrl = null, isSelf = false),
        AdminPerson("p3", "Sam Other", isAdmin = false, linkedEmails = listOf("sam@example.com", "sam.other@example.org"), avatarUrl = null, isSelf = false),
    )
    private val noRoomsActions = RoomsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    private val noPersonsActions = PersonsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    @Test
    fun rooms() = capture("admin-rooms") {
        RoomsScreen(RoomsState(rooms = adminRooms, loaded = true), noRoomsActions)
    }

    @Test
    fun roomsDark() = capture("admin-rooms-dark", dark = true) {
        RoomsScreen(RoomsState(rooms = adminRooms, loaded = true), noRoomsActions)
    }

    @Test
    fun roomEditorRefused() = capture("admin-room-editor-refused") {
        RoomsScreen(
            RoomsState(rooms = adminRooms, loaded = true, editor = RoomEditor(name = "", capacity = "1", color = RoomColor.Orange, errors = listOf("Name must not be blank.", "Room capacity must be at least 2."))),
            noRoomsActions,
        )
    }

    @Test
    fun roomEditorDark() = capture("admin-room-editor-dark", dark = true) {
        RoomsScreen(RoomsState(rooms = adminRooms, loaded = true, editor = RoomEditor("r1", "Atrium", "16", RoomColor.Green)), noRoomsActions)
    }

    @Test
    fun persons() = capture("admin-persons") {
        PersonsScreen(PersonsState(people = adminPeople, loaded = true), noPersonsActions)
    }

    @Test
    fun personsDark() = capture("admin-persons-dark", dark = true) {
        PersonsScreen(PersonsState(people = adminPeople, loaded = true), noPersonsActions)
    }

    @Test
    fun personEditor() = capture("admin-person-editor") {
        PersonsScreen(PersonsState(people = adminPeople, loaded = true, editor = PersonEditor(adminPeople[2], "Sam Other", isAdmin = true)), noPersonsActions)
    }

    @Test
    fun personEditorDark() = capture("admin-person-editor-dark", dark = true) {
        PersonsScreen(PersonsState(people = adminPeople, loaded = true, editor = PersonEditor(adminPeople[2], "Sam Other", isAdmin = true)), noPersonsActions)
    }

    @Test
    fun personEditorGuest() = capture("admin-person-editor-guest") {
        PersonsScreen(PersonsState(people = adminPeople, loaded = true, editor = PersonEditor(adminPeople[1], "Guest Gale", isAdmin = false)), noPersonsActions)
    }

    @Test
    fun about() = capture("about") {
        AboutScreen("5.10.8", 51008, Environment.PRODUCTION, onSwitchEnvironment = {}, onBack = {})
    }

    @Test
    fun aboutDark() = capture("about-dark", dark = true) {
        AboutScreen("5.10.8", 51008, Environment("test"), onSwitchEnvironment = {}, onBack = {})
    }
}

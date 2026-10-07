package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import com.mootmaker.app.ui.home.HomeActions
import com.mootmaker.app.ui.home.HomeScreen
import com.mootmaker.app.ui.home.HomeState
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.agenda.Agenda
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.AgendaRow
import com.mootmaker.data.agenda.NeedsResponseItem
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.api.HomeData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
val NO_ACTIONS = HomeActions({}, {}, {}, {}, {}, {}, {})

fun row(id: String, subject: String, start: String, end: String, room: String = "Boardroom", slot: Int = 0) =
    AgendaRow(id, subject, "${TODAY}T$start:00", "${TODAY}T$end:00", room, slot)

val SAMPLE_AGENDA = Agenda(
    today = AgendaDay(
        TODAY,
        listOf(
            row("m1", "Stand-up", "09:00", "09:15"),
            row("m2", "Design review", "14:30", "15:30", room = "Atrium", slot = 5),
        ),
    ),
    tomorrow = AgendaDay(
        TODAY.plusDays(1),
        listOf(row("m3", "Planning", "10:00", "11:00").copy(startTime = "${TODAY.plusDays(1)}T10:00:00", endTime = "${TODAY.plusDays(1)}T11:00:00")),
    ),
)

val SAMPLE_NEEDS_RESPONSE = listOf(
    NeedsResponseItem(
        meetingId = "m4",
        subject = "Budget review",
        startTime = "${TODAY.plusDays(1)}T13:00:00",
        endTime = "${TODAY.plusDays(1)}T14:00:00",
        roomName = "Atrium",
        roomColorSlot = 5,
        organiserName = "Sam Other",
    ),
)

val EMPTY_AGENDA = Agenda(AgendaDay(TODAY, emptyList()), AgendaDay(TODAY.plusDays(1), emptyList()))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(data: HomeData?, error: String? = null) {
        compose.setContent {
            MootmakerTheme {
                HomeScreen(HomeState(TODAY, data, loading = false, error = error), fallbackName = "pat@example.com", actions = NO_ACTIONS)
            }
        }
    }

    // Use case D.22.
    @Test
    fun showsYourNameAndTodaysAndTomorrowsMeetings() {
        show(HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, windowEnd = TODAY.plusDays(2)))

        compose.onNodeWithText("Pat Example").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow").assertIsDisplayed()
        compose.onNodeWithText("Stand-up").assertIsDisplayed()
        compose.onNodeWithText("14:30–15:30 · Atrium").assertIsDisplayed()
        compose.onNodeWithText("Planning").assertIsDisplayed()
        compose.onNodeWithText("Calendar").assertIsDisplayed()
        compose.onNodeWithText("Rooms today").assertIsDisplayed()
        compose.onNodeWithText("Add meeting").assertIsDisplayed()
    }

    @Test
    fun timesFollowThePersonsFormat() {
        show(HomeData("Pat Example", TimeFormat.AmPm, SAMPLE_AGENDA, windowEnd = TODAY.plusDays(2)))
        compose.onNodeWithText("02:30 PM–03:30 PM · Atrium").assertIsDisplayed()
    }

    // Use case D.23.
    @Test
    fun noMeetingsShowsAnEmptyStateNotAnEmptyList() {
        show(HomeData("Pat Example", TimeFormat.TwentyFourHour, EMPTY_AGENDA, windowEnd = TODAY.plusDays(2)))

        compose.onNodeWithText("No meetings today or tomorrow.").assertIsDisplayed()
        compose.onAllNodesWithText("Today").assertCountEquals(0)
    }

    // Use case D.24.
    @Test
    fun anAccountWithNoLinkedPersonIsToldSoAndHasNoCalendar() {
        show(HomeData(name = null, timeFormat = TimeFormat.TwentyFourHour, agenda = null, windowEnd = TODAY.plusDays(2)))

        compose.onNodeWithText("Your account hasn't been set up properly — no profile could be found for your sign-in.").assertIsDisplayed()
        compose.onNodeWithText("Rooms today").assertIsDisplayed()
        compose.onNodeWithText("Add meeting").assertIsDisplayed()
        compose.onAllNodesWithText("Calendar").assertCountEquals(0)
        compose.onAllNodesWithText("Today").assertCountEquals(0)
    }

    @Test
    fun aFailedFirstLoadOffersARetry() {
        show(data = null, error = "Couldn't reach Mootmaker. Check your connection and try again.")

        compose.onNodeWithText("Couldn't reach Mootmaker. Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    // Use case D.107: the section names the range it covers and shows each invitation with its answers.
    @Test
    fun needsYourResponseNamesTheRangeAndOffersTheThreeAnswers() {
        var answered: Pair<String, AttendeeStatus>? = null
        compose.setContent {
            MootmakerTheme {
                HomeScreen(
                    HomeState(TODAY, HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, SAMPLE_NEEDS_RESPONSE, windowEnd = TODAY.plusDays(2)), loading = false),
                    null,
                    NO_ACTIONS.copy(onRespond = { id, status -> answered = id to status }),
                )
            }
        }

        compose.onNodeWithText("Needs your response").assertIsDisplayed()
        compose.onNodeWithText("Budget review").assertIsDisplayed()
        compose.onNodeWithText("From Sam Other").assertIsDisplayed()
        compose.onNodeWithContentDescription("Maybe for Budget review").assertIsDisplayed().performClick()
        assertEquals("m4" to AttendeeStatus.Maybe, answered)
        compose.onNodeWithContentDescription("Going for Budget review").assertIsDisplayed()
        compose.onNodeWithContentDescription("Not going for Budget review").assertIsDisplayed()
    }

    @Test
    fun anEmptyNeedsYourResponseSaysWhatRangeItChecked() {
        show(HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, windowEnd = TODAY.plusDays(2)))
        compose.onNodeWithText("Needs your response").assertIsDisplayed()
        compose.onNode(hasText("Nothing waiting on a response between", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Search further ahead").assertIsDisplayed()
    }
}

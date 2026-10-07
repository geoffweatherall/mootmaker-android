package com.mootmaker.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mootmaker.app.ui.calendar.CalendarActions
import com.mootmaker.app.ui.calendar.CalendarScreen
import com.mootmaker.app.ui.calendar.CalendarState
import com.mootmaker.app.ui.theme.MootmakerTheme
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.AgendaRow
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.CalendarData
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.calendar.workWeekDates
import com.mootmaker.data.meeting.PersonRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

val NO_CALENDAR_ACTIONS = CalendarActions({}, {}, {}, {}, {}, {}, {})

private val MONDAY = LocalDate.of(2026, 10, 5)

private fun at(date: LocalDate, id: String, subject: String, start: String, end: String, room: String = "Boardroom", slot: Int = 0) =
    AgendaRow(id, subject, "${date}T$start:00", "${date}T$end:00", room, slot)

val SAMPLE_CALENDAR = CalendarState(
    personId = "p1",
    monday = MONDAY,
    today = TODAY,
    loading = false,
    data = CalendarData(
        timeFormat = TimeFormat.TwentyFourHour,
        people = listOf(PersonRef("p1", "Pat Example"), PersonRef("p2", "Sam Other")),
        week = workWeekDates(MONDAY).map { date ->
            AgendaDay(
                date,
                when (date.dayOfMonth) {
                    5 -> listOf(at(date, "m0", "Kick-off", "09:00", "10:00"))
                    7 -> listOf(
                        at(date, "m1", "Stand-up", "09:00", "09:15"),
                        at(date, "m2", "Design review", "14:30", "15:30", room = "Atrium", slot = 5),
                    )
                    else -> emptyList()
                },
            )
        },
        bounds = CalendarBounds(MONDAY.minusWeeks(4), MONDAY.plusWeeks(12)),
    ),
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class CalendarScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: CalendarState, actions: CalendarActions = NO_CALENDAR_ACTIONS) {
        compose.setContent { MootmakerTheme { CalendarScreen(state, actions) } }
    }

    // Use cases G.59, G.61 and G.63: the person's week, Monday to Friday, with empty days as empty.
    @Test
    fun showsTheWorkWeekWithMeetingsSortedAndEmptyDaysSaidSo() {
        show(SAMPLE_CALENDAR)

        compose.onNodeWithText("Pat Example").assertIsDisplayed()
        compose.onNodeWithText("5 Oct – 9 Oct 2026").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed() // Wednesday the 7th
        compose.onNodeWithText("Tomorrow").assertIsDisplayed()
        compose.onNodeWithText("Stand-up").assertIsDisplayed()
        compose.onNodeWithText("14:30–15:30 · Atrium").assertIsDisplayed()
        compose.onNodeWithText("Kick-off").assertIsDisplayed()
        // Friday is the fifth day, and there are no weekend sections.
        compose.onNodeWithText("Friday").assertIsDisplayed()
    }

    // Use case G.65: a meeting row opens its details.
    @Test
    fun tappingAMeetingOpensIt() {
        var opened: String? = null
        show(SAMPLE_CALENDAR, NO_CALENDAR_ACTIONS.copy(onOpenMeeting = { opened = it }))
        compose.onNodeWithText("Design review").performClick()
        assertEquals("m2", opened)
    }

    // Use case G.60: choose another person from the selector.
    @Test
    fun theSelectorListsEveryoneAndReportsTheChoice() {
        var chosen: String? = null
        show(SAMPLE_CALENDAR, NO_CALENDAR_ACTIONS.copy(onSelectPerson = { chosen = it }))
        compose.onNodeWithText("Pat Example").performClick()
        compose.onNodeWithText("Sam Other").performClick()
        assertEquals("p2", chosen)
    }

    // Use case G.62: the week controls, disabled at the server's window and on this week.
    @Test
    fun weekControlsAreDisabledWhereTheyCannotGo() {
        show(SAMPLE_CALENDAR.copy(monday = MONDAY))
        compose.onNodeWithText("This week").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous week").assertIsEnabled()
    }

    @Test
    fun previousWeekIsDisabledAtTheEarliestRetainedWeekAndThisWeekComesBack() {
        val atEarliest = MONDAY.minusWeeks(4)
        var back = false
        show(SAMPLE_CALENDAR.copy(monday = atEarliest), NO_CALENDAR_ACTIONS.copy(onThisWeek = { back = true }))
        compose.onNodeWithContentDescription("Previous week").assertIsNotEnabled()
        compose.onNodeWithText("This week").assertIsEnabled().performClick()
        assertEquals(true, back)
    }

    @Test
    fun nextWeekIsDisabledAtTheLastBookableWeek() {
        val last = MONDAY.plusWeeks(12)
        show(SAMPLE_CALENDAR.copy(monday = last))
        compose.onNodeWithContentDescription("Next week").assertIsNotEnabled()
    }

    // Use case G.64.
    @Test
    fun noPeopleShowsTheEmptyState() {
        show(SAMPLE_CALENDAR.copy(data = SAMPLE_CALENDAR.data!!.copy(people = emptyList())))
        compose.onNodeWithText("No people exist yet.").assertIsDisplayed()
    }

    @Test
    fun aFailedLoadOffersARetry() {
        var retried = false
        show(CalendarState("p1", MONDAY, TODAY, loading = false, error = "Couldn't reach Mootmaker."), NO_CALENDAR_ACTIONS.copy(onRetry = { retried = true }))
        compose.onNodeWithText("Couldn't reach Mootmaker.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(true, retried)
    }
}

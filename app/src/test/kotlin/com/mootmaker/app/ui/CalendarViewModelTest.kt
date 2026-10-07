package com.mootmaker.app.ui

import com.mootmaker.app.ui.calendar.CalendarViewModel
import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.CalendarData
import com.mootmaker.data.api.CalendarSource
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.calendar.workWeekDates
import com.mootmaker.data.meeting.PersonRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val today = LocalDate.of(2026, 10, 7) // a Wednesday
    private val thisMonday = LocalDate.of(2026, 10, 5)
    private val bounds = CalendarBounds(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 19))
    private val loaded = mutableListOf<Pair<String, LocalDate>>()
    private var failure: ApiException? = null

    private val source = object : CalendarSource {
        override suspend fun load(personId: String, monday: LocalDate): CalendarData {
            loaded += personId to monday
            failure?.let { throw it }
            return CalendarData(
                TimeFormat.TwentyFourHour,
                listOf(PersonRef("p1", "Pat Example"), PersonRef("p2", "Sam Other")),
                workWeekDates(monday).map { AgendaDay(it, emptyList()) },
                bounds,
            )
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CalendarViewModel(source, "p1") { today }

    @Test
    fun startsOnThisWeekForThePersonAndNamesThem() {
        val viewModel = viewModel().also { it.refresh() }
        assertEquals(listOf("p1" to thisMonday), loaded)
        assertTrue(viewModel.state.value.isThisWeek)
        assertEquals("Pat Example", viewModel.state.value.personName)
    }

    @Test
    fun movingWeekLoadsThatWeekAndThisWeekComesBack() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.nextWeek()
        assertEquals(thisMonday.plusWeeks(1), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.isThisWeek)

        viewModel.thisWeek()
        assertEquals(thisMonday, viewModel.state.value.monday)
        assertEquals(listOf("p1" to thisMonday, "p1" to thisMonday.plusWeeks(1), "p1" to thisMonday), loaded)
    }

    // Use case G.62.
    @Test
    fun navigationStopsAtTheServersWindow() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.previousWeek()
        assertEquals(LocalDate.of(2026, 9, 28), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.canGoBack)
        viewModel.previousWeek()
        assertEquals(LocalDate.of(2026, 9, 28), viewModel.state.value.monday)

        viewModel.thisWeek()
        viewModel.nextWeek()
        viewModel.nextWeek()
        assertEquals(LocalDate.of(2026, 10, 19), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.canGoForward)
        viewModel.nextWeek()
        assertEquals(LocalDate.of(2026, 10, 19), viewModel.state.value.monday)
    }

    // Use case G.60.
    @Test
    fun selectingAnotherPersonLoadsTheirWeekInTheSameWeek() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.selectPerson("p2")
        assertEquals("Sam Other", viewModel.state.value.personName)
        assertEquals("p2" to thisMonday, loaded.last())
        viewModel.selectPerson("p2")
        assertEquals(2, loaded.size)
    }

    @Test
    fun aFailedLoadShowsTheMessageAndARefreshClearsIt() {
        failure = ApiException("Couldn't reach Mootmaker.")
        val viewModel = viewModel().also { it.refresh() }
        assertEquals("Couldn't reach Mootmaker.", viewModel.state.value.error)
        assertNull(viewModel.state.value.data)

        failure = null
        viewModel.refresh()
        assertNull(viewModel.state.value.error)
        assertTrue(viewModel.state.value.data != null)
    }
}

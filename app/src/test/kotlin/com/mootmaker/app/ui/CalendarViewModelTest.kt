package com.mootmaker.app.ui

import com.mootmaker.app.ui.calendar.CalendarViewModel
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.CalendarRepository
import com.mootmaker.data.cache.CachedAttendee
import com.mootmaker.data.cache.CachedMeeting
import com.mootmaker.data.cache.CachedPerson
import com.mootmaker.data.cache.CachedRoom
import com.mootmaker.data.cache.Me
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.calendar.workWeekDates
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.PersonRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private val reference = Reference(
        me = Me("p1", "Pat Example", null, TimeFormat.TwentyFourHour, DateFormat.Iso),
        people = listOf(CachedPerson("p1", "Pat Example"), CachedPerson("p2", "Sam Other")),
        rooms = listOf(CachedRoom("r1", "Boardroom", 8, null)),
        bounds = bounds,
    )
    private val api = FakeWorkspaceApi(reference)
    private val dispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(personId: String = "p1"): CalendarViewModel {
        val store = WorkspaceStore(api, storeScope, today = { today })
        return CalendarViewModel(CalendarRepository(store), personId) { today }
    }

    private fun meeting(id: String, date: LocalDate, subject: String, organiserId: String, vararg attendees: String) = CachedMeeting(
        id = id,
        subject = subject,
        startTime = "${date}T09:00:00",
        endTime = "${date}T09:30:00",
        roomId = "r1",
        organiserId = organiserId,
        attendees = attendees.map { CachedAttendee(it, AttendeeStatus.NoResponse) },
    )

    private fun CalendarViewModel.subjects() = state.value.data!!.week.flatMap { day -> day.rows.map { it.subject } }

    @Test
    fun startsOnThisWeekForThePersonAndNamesThem() {
        val viewModel = viewModel().also { it.refresh() }
        assertEquals(listOf(workWeekDates(thisMonday)), api.dayCalls)
        assertTrue(viewModel.state.value.isThisWeek)
        assertEquals("Pat Example", viewModel.state.value.personName)
        assertNotNull(viewModel.state.value.data)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun showsTheWeeksMeetingsForThePerson() {
        api.meetings[today] = listOf(
            meeting("m1", today, "Pat's stand-up", "p1"),
            meeting("m2", today, "Sam's one-to-one", "p2"),
            meeting("m3", today, "Joint review", "p2", "p1"),
        )
        val viewModel = viewModel()
        assertEquals(listOf("Pat's stand-up", "Joint review"), viewModel.subjects())
    }

    @Test
    fun movingWeekLoadsThatWeekAndThisWeekComesBack() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.nextWeek()
        assertEquals(thisMonday.plusWeeks(1), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.isThisWeek)
        assertEquals(workWeekDates(thisMonday.plusWeeks(1)), viewModel.state.value.data!!.week.map { it.date })

        viewModel.thisWeek()
        assertEquals(thisMonday, viewModel.state.value.monday)
        assertTrue(viewModel.state.value.isThisWeek)
        assertEquals(workWeekDates(thisMonday), viewModel.state.value.data!!.week.map { it.date })
        assertEquals(listOf(workWeekDates(thisMonday), workWeekDates(thisMonday.plusWeeks(1))), api.dayCalls)
    }

    @Test
    fun movingToNextWeekShowsNothingOfThePreviousWeekUntilTheNewOneIsAnswered() {
        api.meetings[today] = listOf(meeting("m1", today, "This week only", "p1"))
        api.gated = true
        val viewModel = viewModel()
        api.release()
        assertEquals(listOf("This week only"), viewModel.subjects())

        viewModel.nextWeek()
        assertNull(viewModel.state.value.data)
        assertTrue(viewModel.state.value.loading)
        assertEquals(workWeekDates(thisMonday.plusWeeks(1)), api.dayCalls.last())

        api.release()
        assertEquals(emptyList<String>(), viewModel.subjects())
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun goingBackToAHeldWeekShowsItAtOnceWithoutARequest() {
        api.meetings[today] = listOf(meeting("m1", today, "Held meeting", "p1"))
        val viewModel = viewModel()
        viewModel.nextWeek()
        val requests = api.dayCalls.size

        api.gated = true
        viewModel.previousWeek()

        assertNotNull(viewModel.state.value.data)
        assertEquals(listOf("Held meeting"), viewModel.subjects())
        assertFalse(viewModel.state.value.loading)
        assertEquals(requests, api.dayCalls.size)
    }

    @Test
    fun peopleBoundsAndNameStayAvailableWhileANewWeekLoads() {
        val viewModel = viewModel()
        api.gated = true
        viewModel.nextWeek()

        val state = viewModel.state.value
        assertNull(state.data)
        assertTrue(state.loading)
        assertEquals(listOf(PersonRef("p1", "Pat Example"), PersonRef("p2", "Sam Other")), state.people)
        assertEquals(bounds, state.bounds)
        assertEquals("Pat Example", state.personName)
        assertTrue(state.canGoBack)
        assertTrue(state.canGoForward)
    }

    // Use case G.62.
    @Test
    fun navigationStopsAtTheServersWindow() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.previousWeek()
        assertEquals(LocalDate.of(2026, 9, 28), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.canGoBack)
        val requests = api.dayCalls.size
        viewModel.previousWeek()
        assertEquals(LocalDate.of(2026, 9, 28), viewModel.state.value.monday)
        assertEquals(requests, api.dayCalls.size)

        viewModel.thisWeek()
        viewModel.nextWeek()
        viewModel.nextWeek()
        assertEquals(LocalDate.of(2026, 10, 19), viewModel.state.value.monday)
        assertFalse(viewModel.state.value.canGoForward)
        val atLimit = api.dayCalls.size
        viewModel.nextWeek()
        assertEquals(LocalDate.of(2026, 10, 19), viewModel.state.value.monday)
        assertEquals(atLimit, api.dayCalls.size)
    }

    // Use case G.60.
    @Test
    fun selectingAnotherPersonShowsTheirWeekAtOnceWithoutARequest() {
        api.meetings[today] = listOf(
            meeting("m1", today, "Pat's stand-up", "p1"),
            meeting("m2", today, "Sam's one-to-one", "p2"),
        )
        val viewModel = viewModel().also { it.refresh() }
        assertEquals(listOf("Pat's stand-up"), viewModel.subjects())
        val requests = api.dayCalls.size

        api.gated = true
        viewModel.selectPerson("p2")

        assertEquals("Sam Other", viewModel.state.value.personName)
        assertEquals("p2", viewModel.state.value.personId)
        assertEquals(listOf("Sam's one-to-one"), viewModel.subjects())
        assertFalse(viewModel.state.value.loading)
        assertEquals(requests, api.dayCalls.size)

        viewModel.selectPerson("p2")
        assertEquals(requests, api.dayCalls.size)
    }

    @Test
    fun selectingAnotherPersonBeforeTheWeekIsHeldShowsNothingUntilItIsAnswered() {
        api.gated = true
        val viewModel = viewModel()
        assertNull(viewModel.state.value.data)

        viewModel.selectPerson("p2")
        assertNull(viewModel.state.value.data)
        assertTrue(viewModel.state.value.loading)
        assertEquals(1, api.dayCalls.size)

        api.release()
        assertNotNull(viewModel.state.value.data)
        assertEquals("Sam Other", viewModel.state.value.personName)
        assertEquals(1, api.dayCalls.size)
    }

    @Test
    fun aFailedLoadShowsTheMessageAndARefreshRetries() {
        api.failure = ApiException("Couldn't reach Mootmaker.")
        val viewModel = viewModel().also { it.refresh() }
        assertEquals("Couldn't reach Mootmaker.", viewModel.state.value.error)
        assertNull(viewModel.state.value.data)
        assertFalse(viewModel.state.value.loading)
        val requests = api.referenceCalls.size

        api.failure = null
        viewModel.refresh()
        assertTrue(api.referenceCalls.size > requests)
        assertNull(viewModel.state.value.error)
        assertNotNull(viewModel.state.value.data)
    }

    @Test
    fun refreshMovesTodayOn() {
        var now = today
        val store = WorkspaceStore(api, storeScope, today = { today })
        val viewModel = CalendarViewModel(CalendarRepository(store), "p1") { now }
        assertEquals(today, viewModel.state.value.today)

        now = today.plusDays(1)
        viewModel.refresh()
        assertEquals(today.plusDays(1), viewModel.state.value.today)
    }
}

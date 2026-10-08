package com.mootmaker.app.ui

import com.mootmaker.app.ui.availability.AvailabilityViewModel
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.AvailabilityRepository
import com.mootmaker.data.api.DateBounds
import com.mootmaker.data.cache.CachedAttendee
import com.mootmaker.data.cache.CachedMeeting
import com.mootmaker.data.cache.CachedPerson
import com.mootmaker.data.cache.CachedRoom
import com.mootmaker.data.cache.Me
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.meeting.AttendeeStatus
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
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityViewModelTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val bounds = DateBounds(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12))
    private var now = LocalDateTime.of(2026, 10, 7, 10, 15)

    private val reference = Reference(
        me = Me("p1", "Pat Example", null, TimeFormat.TwentyFourHour, DateFormat.Iso),
        people = listOf(CachedPerson("p1", "Pat Example"), CachedPerson("p2", "Sam Other")),
        rooms = listOf(CachedRoom("r1", "Boardroom", 8, null), CachedRoom("r2", "Studio", 4, null)),
        bounds = CalendarBounds(bounds.earliest, bounds.latest),
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

    private fun viewModel(): AvailabilityViewModel {
        val store = WorkspaceStore(api, storeScope, today = { today })
        return AvailabilityViewModel(AvailabilityRepository(store), today) { now }
    }

    private fun meeting(id: String, date: LocalDate, subject: String, roomId: String = "r1") = CachedMeeting(
        id = id,
        subject = subject,
        startTime = "${date}T09:00:00",
        endTime = "${date}T09:30:00",
        roomId = roomId,
        organiserId = "p1",
        attendees = listOf(CachedAttendee("p2", AttendeeStatus.NoResponse)),
    )

    private fun AvailabilityViewModel.bookings() = state.value.data!!.rooms.associate { it.name to it.bookings.map { b -> b.subject } }

    @Test
    fun loadsTheStartDateAndKnowsWhatNowIs() {
        val viewModel = viewModel().also { it.refresh() }
        assertEquals(listOf(listOf(today)), api.dayCalls)
        assertEquals(10 * 60 + 15, viewModel.state.value.nowMinutes)
        assertTrue(viewModel.state.value.isToday)
        assertFalse(viewModel.state.value.loading)
        assertNotNull(viewModel.state.value.data)
    }

    @Test
    fun refreshMovesNowOn() {
        val viewModel = viewModel()
        now = LocalDateTime.of(2026, 10, 8, 13, 5)
        viewModel.refresh()
        assertEquals(13 * 60 + 5, viewModel.state.value.nowMinutes)
        assertEquals(LocalDate.of(2026, 10, 8), viewModel.state.value.today)
        assertFalse(viewModel.state.value.isToday)
    }

    @Test
    fun showsEachRoomWithItsOwnBookings() {
        api.meetings[today] = listOf(meeting("m1", today, "Stand-up"), meeting("m2", today, "Workshop", "r2"))
        val viewModel = viewModel()
        assertEquals(mapOf("Boardroom" to listOf("Stand-up"), "Studio" to listOf("Workshop")), viewModel.bookings())
    }

    @Test
    fun movingDayLoadsThatDayAndCollapsesRooms() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.toggleExpanded("r1")
        assertEquals(setOf("r1"), viewModel.state.value.expanded)

        viewModel.nextDay()

        assertEquals(today.plusDays(1), viewModel.state.value.date)
        assertEquals(emptySet<String>(), viewModel.state.value.expanded)
        assertEquals(listOf(listOf(today), listOf(today.plusDays(1))), api.dayCalls)
        assertFalse(viewModel.state.value.isToday)
    }

    @Test
    fun movingDayShowsNothingOfThePreviousDayUntilTheNewOneIsAnswered() {
        api.meetings[today] = listOf(meeting("m1", today, "Today only"))
        api.gated = true
        val viewModel = viewModel()
        api.release()
        assertEquals(listOf("Today only"), viewModel.bookings().getValue("Boardroom"))

        viewModel.nextDay()
        assertNull(viewModel.state.value.data)
        assertTrue(viewModel.state.value.loading)

        api.release()
        assertNotNull(viewModel.state.value.data)
        assertFalse(viewModel.state.value.loading)
        assertEquals(emptyList<String>(), viewModel.bookings().getValue("Boardroom"))
    }

    @Test
    fun aDateSeenBeforeShowsAtOnceWithoutARequest() {
        api.meetings[today] = listOf(meeting("m1", today, "Stand-up"))
        val viewModel = viewModel()
        viewModel.nextDay()
        val requests = api.dayCalls.size

        api.gated = true
        viewModel.previousDay()

        assertEquals(today, viewModel.state.value.date)
        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf("Stand-up"), viewModel.bookings().getValue("Boardroom"))
        assertEquals(requests, api.dayCalls.size)
    }

    @Test
    fun anEmptyDayIsRoomsWithNoBookingsOnlyAfterItIsAnswered() {
        api.gated = true
        val viewModel = viewModel()
        assertNull(viewModel.state.value.data)
        assertTrue(viewModel.state.value.loading)

        api.release()
        val rooms = viewModel.state.value.data!!.rooms
        assertEquals(listOf("Boardroom", "Studio"), rooms.map { it.name })
        assertTrue(rooms.all { it.bookings.isEmpty() })
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun boundsAreKeptWhileAnotherDateLoads() {
        val viewModel = viewModel()
        assertEquals(bounds, viewModel.state.value.bounds)

        api.gated = true
        viewModel.goTo(bounds.latest)

        assertNull(viewModel.state.value.data)
        assertEquals(bounds, viewModel.state.value.bounds)
        assertFalse(viewModel.state.value.canGoForward)
        assertTrue(viewModel.state.value.canGoBack)
    }

    @Test
    fun navigationStopsAtTheServersWindow() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.goTo(LocalDate.of(2026, 12, 25))
        assertEquals(bounds.latest, viewModel.state.value.date)
        assertFalse(viewModel.state.value.canGoForward)
        val requests = api.dayCalls.size
        viewModel.nextDay()
        assertEquals(bounds.latest, viewModel.state.value.date)
        assertEquals(requests, api.dayCalls.size)

        viewModel.goTo(LocalDate.of(2026, 1, 1))
        assertEquals(bounds.earliest, viewModel.state.value.date)
        assertFalse(viewModel.state.value.canGoBack)
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
    fun refreshAfterAFailedDayFetchAsksForTheDayAgain() {
        val viewModel = viewModel()
        api.failure = ApiException("Couldn't reach Mootmaker.")
        viewModel.goTo(today.plusDays(1))
        assertEquals("Couldn't reach Mootmaker.", viewModel.state.value.error)
        assertEquals(2, api.dayCalls.size)

        api.failure = null
        viewModel.refresh()
        assertEquals(3, api.dayCalls.size)
        assertEquals(listOf(today.plusDays(1)), api.dayCalls.last())
        assertNull(viewModel.state.value.error)
        assertNotNull(viewModel.state.value.data)
    }
}

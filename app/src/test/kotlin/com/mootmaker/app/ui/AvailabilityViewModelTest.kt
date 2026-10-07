package com.mootmaker.app.ui

import com.mootmaker.app.ui.availability.AvailabilityViewModel
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.AvailabilityData
import com.mootmaker.data.api.AvailabilitySource
import com.mootmaker.data.api.DateBounds
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
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityViewModelTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val bounds = DateBounds(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12))
    private val loaded = mutableListOf<LocalDate>()
    private var failure: ApiException? = null

    private val source = object : AvailabilitySource {
        override suspend fun load(date: LocalDate): AvailabilityData {
            loaded += date
            failure?.let { throw it }
            return AvailabilityData(TimeFormat.TwentyFourHour, emptyList(), bounds)
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AvailabilityViewModel(source, today) { LocalDateTime.of(2026, 10, 7, 10, 15) }

    @Test
    fun loadsTheStartDateAndKnowsWhatNowIs() {
        val viewModel = viewModel().also { it.refresh() }
        assertEquals(listOf(today), loaded)
        assertEquals(10 * 60 + 15, viewModel.state.value.nowMinutes)
        assertTrue(viewModel.state.value.isToday)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun movingDayLoadsThatDayAndCollapsesRooms() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.toggleExpanded("r1")
        assertEquals(setOf("r1"), viewModel.state.value.expanded)

        viewModel.nextDay()

        assertEquals(today.plusDays(1), viewModel.state.value.date)
        assertEquals(emptySet<String>(), viewModel.state.value.expanded)
        assertEquals(listOf(today, today.plusDays(1)), loaded)
        assertFalse(viewModel.state.value.isToday)
    }

    @Test
    fun navigationStopsAtTheServersWindow() {
        val viewModel = viewModel().also { it.refresh() }
        viewModel.goTo(LocalDate.of(2026, 12, 25))
        assertEquals(bounds.latest, viewModel.state.value.date)
        assertFalse(viewModel.state.value.canGoForward)
        viewModel.nextDay()
        assertEquals(bounds.latest, viewModel.state.value.date)

        viewModel.goTo(LocalDate.of(2026, 1, 1))
        assertEquals(bounds.earliest, viewModel.state.value.date)
        assertFalse(viewModel.state.value.canGoBack)
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

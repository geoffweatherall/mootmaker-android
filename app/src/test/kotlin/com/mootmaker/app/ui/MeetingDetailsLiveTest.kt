package com.mootmaker.app.ui

import com.mootmaker.app.ui.meeting.MeetingDetailsViewModel
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.api.WriteResult
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.meeting.MeetingDetailsData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The webapp's in-flight race, on Android: a load that began before a broadcast may hold stale data. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingDetailsLiveTest {
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var loads = 0

    private val source = object : MeetingSource {
        override suspend fun load(meetingId: String): MeetingDetailsData {
            loads++
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gate.await()
            return MeetingDetailsData(null, "p1", TimeFormat.TwentyFourHour, DateFormat.Iso)
        }

        override suspend fun respond(meetingId: String, status: AttendeeStatus) = WriteResult.Done

        override suspend fun cancel(meetingId: String) = WriteResult.Done
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aBroadcastWhileLoadingIsFollowedByOneMoreLoad() {
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.refresh()
        assertEquals(1, loads)

        // Two broadcasts land while the first load is still in flight: that load is not trusted, but
        // one more is enough to cover both.
        viewModel.refreshForLiveChange()
        viewModel.refreshForLiveChange()
        assertEquals(1, loads)

        gates[0].complete(Unit)
        assertEquals(2, loads)

        gates[1].complete(Unit)
        assertEquals(2, loads)
    }

    @Test
    fun aBroadcastWhileIdleLoadsStraightAway() {
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.refresh()
        gates[0].complete(Unit)

        viewModel.refreshForLiveChange()

        assertEquals(2, loads)
    }
}

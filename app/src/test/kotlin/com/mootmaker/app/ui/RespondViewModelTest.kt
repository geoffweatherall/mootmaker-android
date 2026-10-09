package com.mootmaker.app.ui

import com.mootmaker.app.ui.home.HomeViewModel
import com.mootmaker.app.ui.meeting.MeetingDetailsViewModel
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.HomeData
import com.mootmaker.data.api.HomeSource
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.api.WriteResult
import com.mootmaker.data.cache.Loaded
import com.mootmaker.data.meeting.AttendeeRow
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.meeting.MeetingDetailsData
import com.mootmaker.data.agenda.DateFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Responding shows the chosen answer at once (issue #26): pending is set when tapped, rolled back on
 * a refusal or exception, and, on success, kept until the refetch confirms it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RespondViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A source whose respond waits for the test to say how it ends. */
    private inner class FakeMeetingSource(initial: AttendeeStatus) : MeetingSource {
        val loaded = MutableStateFlow(Loaded(details(initial), fetching = false, error = null))
        var outcome = CompletableDeferred<WriteResult>()
        var calls = 0

        override fun observe(meetingId: String): Flow<Loaded<MeetingDetailsData>> = loaded
        override fun retry() = Unit
        override suspend fun respond(meetingId: String, status: AttendeeStatus): WriteResult {
            calls++
            return outcome.await()
        }
        override suspend fun cancel(meetingId: String): WriteResult = WriteResult.Done

        fun show(status: AttendeeStatus, fetching: Boolean = false) {
            loaded.value = Loaded(details(status), fetching, null)
        }
    }

    private fun details(status: AttendeeStatus) = MeetingDetailsData(
        meeting = SAMPLE_MEETING.copy(attendees = listOf(AttendeeRow(SAMPLE_MEETING.attendees[0].person, status))),
        myPersonId = "p1",
        timeFormat = TimeFormat.TwentyFourHour,
        dateFormat = DateFormat.Iso,
    )

    @Test
    fun detailsShowsThePendingAnswerAtOnce() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")

        viewModel.respond(AttendeeStatus.Maybe)

        assertEquals(AttendeeStatus.Maybe, viewModel.state.value.pendingResponse)
    }

    @Test
    fun detailsClearsThePendingAnswerOnceTheRefetchConfirmsIt() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.respond(AttendeeStatus.Going)

        source.outcome.complete(WriteResult.Done)
        // The save has returned but the store still holds the old answer while it refetches: no flick back.
        source.show(AttendeeStatus.NoResponse, fetching = true)
        assertEquals(AttendeeStatus.Going, viewModel.state.value.pendingResponse)

        source.show(AttendeeStatus.Going, fetching = false)
        assertNull(viewModel.state.value.pendingResponse)
        assertTrue(viewModel.state.value.actionErrors.isEmpty())
    }

    @Test
    fun detailsClearsThePendingAnswerWhenTheRefetchFinishesWithSomethingElse() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.respond(AttendeeStatus.Going)
        source.outcome.complete(WriteResult.Done)
        source.show(AttendeeStatus.NoResponse, fetching = true)

        source.show(AttendeeStatus.NotGoing, fetching = false)

        assertNull(viewModel.state.value.pendingResponse)
    }

    @Test
    fun detailsRollsBackARefusedAnswerAndShowsWhy() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.respond(AttendeeStatus.Going)

        source.outcome.complete(WriteResult.Rejected(listOf("This meeting has already started.")))

        assertNull(viewModel.state.value.pendingResponse)
        assertEquals(listOf("This meeting has already started."), viewModel.state.value.actionErrors)
    }

    @Test
    fun detailsRollsBackAnAnswerThatThrew() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.respond(AttendeeStatus.Going)

        source.outcome.completeExceptionally(ApiException("Couldn't reach Mootmaker."))

        assertNull(viewModel.state.value.pendingResponse)
        assertEquals(listOf("Couldn't reach Mootmaker."), viewModel.state.value.actionErrors)
    }

    @Test
    fun detailsIgnoresASecondTapWhilePending() {
        val source = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = MeetingDetailsViewModel(source, "m1")
        viewModel.respond(AttendeeStatus.Going)

        viewModel.respond(AttendeeStatus.NotGoing)

        assertEquals(1, source.calls)
        assertEquals(AttendeeStatus.Going, viewModel.state.value.pendingResponse)
    }

    private inner class FakeHomeSource(var needing: Boolean = true) : HomeSource {
        val loaded = MutableStateFlow(Loaded(homeData(needing), fetching = false, error = null))
        override fun observe(today: LocalDate, searchLevel: Int): Flow<Loaded<HomeData>> = loaded
        override fun retry() = Unit

        fun show(needing: Boolean, fetching: Boolean) {
            loaded.value = Loaded(homeData(needing), fetching, null)
        }
    }

    private fun homeData(needing: Boolean) =
        HomeData("Pat Example", TimeFormat.TwentyFourHour, SAMPLE_AGENDA, if (needing) SAMPLE_NEEDS_RESPONSE else emptyList(), windowEnd = TODAY.plusDays(2))

    private fun homeViewModel(home: FakeHomeSource, respond: FakeMeetingSource) = HomeViewModel(home, respond, clock = { TODAY })

    @Test
    fun homeShowsThePendingAnswerAtOnce() {
        val viewModel = homeViewModel(FakeHomeSource(), FakeMeetingSource(AttendeeStatus.NoResponse))

        viewModel.respond("m4", AttendeeStatus.Maybe)

        assertEquals(mapOf("m4" to AttendeeStatus.Maybe), viewModel.state.value.pendingResponses)
    }

    @Test
    fun homeKeepsTheAnsweredCardUntilTheRefetchRemovesIt() {
        val home = FakeHomeSource()
        val respond = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = homeViewModel(home, respond)
        viewModel.respond("m4", AttendeeStatus.Going)

        respond.outcome.complete(WriteResult.Done)
        home.show(needing = true, fetching = true)
        assertEquals(mapOf("m4" to AttendeeStatus.Going), viewModel.state.value.pendingResponses)
        // The card is not removed optimistically.
        assertEquals(1, viewModel.state.value.data!!.needsResponse.size)

        home.show(needing = false, fetching = false)
        assertTrue(viewModel.state.value.pendingResponses.isEmpty())
    }

    @Test
    fun homeRollsBackARefusedAnswer() {
        val respond = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = homeViewModel(FakeHomeSource(), respond)
        viewModel.respond("m4", AttendeeStatus.Going)

        respond.outcome.complete(WriteResult.Rejected(listOf("Nope.")))

        assertTrue(viewModel.state.value.pendingResponses.isEmpty())
        assertEquals("Nope.", viewModel.state.value.respondError)
    }

    @Test
    fun homeRollsBackAnAnswerThatThrew() {
        val respond = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = homeViewModel(FakeHomeSource(), respond)
        viewModel.respond("m4", AttendeeStatus.Going)

        respond.outcome.completeExceptionally(ApiException("Couldn't reach Mootmaker."))

        assertTrue(viewModel.state.value.pendingResponses.isEmpty())
        assertEquals("Couldn't reach Mootmaker.", viewModel.state.value.respondError)
    }

    @Test
    fun homeIgnoresASecondTapOnTheSameMeetingWhilePending() {
        val respond = FakeMeetingSource(AttendeeStatus.NoResponse)
        val viewModel = homeViewModel(FakeHomeSource(), respond)
        viewModel.respond("m4", AttendeeStatus.Going)

        viewModel.respond("m4", AttendeeStatus.NotGoing)

        assertEquals(1, respond.calls)
        assertEquals(mapOf("m4" to AttendeeStatus.Going), viewModel.state.value.pendingResponses)
    }
}

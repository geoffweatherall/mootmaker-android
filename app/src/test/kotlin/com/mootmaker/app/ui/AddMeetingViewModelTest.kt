package com.mootmaker.app.ui

import com.mootmaker.app.ui.addmeeting.AddMeetingViewModel
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.MeetingFormSource
import com.mootmaker.data.meeting.CreateResult
import com.mootmaker.data.meeting.MeetingDraft
import com.mootmaker.data.meeting.MeetingFormReference
import com.mootmaker.data.meeting.NO_ROOM_AVAILABLE_MESSAGE
import com.mootmaker.data.meeting.PersonOption
import com.mootmaker.data.meeting.RoomOption
import kotlinx.coroutines.CompletableDeferred
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
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class AddMeetingViewModelTest {
    private val me = PersonOption("p1", "Pat")
    private val sam = PersonOption("p2", "Sam")
    private val atrium = RoomOption("r1", "Atrium", 4)
    private val boardroom = RoomOption("r2", "Boardroom", 8)

    private var reference = MeetingFormReference("p1", listOf(me, sam), listOf(atrium, boardroom), TimeFormat.TwentyFourHour, DateFormat.Iso)
    private var loadFailure: ApiException? = null
    private var suggestions = listOf(atrium, boardroom)
    private val suggestCalls = mutableListOf<Triple<String, String, Int>>()
    private val drafts = mutableListOf<MeetingDraft>()
    private var result: CreateResult = CreateResult.Created("m-new")
    private var gate: CompletableDeferred<Unit>? = null

    private val source = object : MeetingFormSource {
        override suspend fun loadReference(): MeetingFormReference {
            loadFailure?.let { throw it }
            return reference
        }

        override suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int): List<RoomOption> {
            suggestCalls += Triple(startTime, endTime, requiredCapacity)
            return suggestions
        }

        override suspend fun create(draft: MeetingDraft): CreateResult {
            drafts += draft
            gate?.await()
            return result
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 10, 8)

    private fun viewModel() = AddMeetingViewModel(source, day, LocalDateTime.of(2026, 10, 7, 10, 1)).also { it.load() }

    // Use cases F.39 and F.40.
    @Test
    fun startsOnTheGivenDateWithTheDefaultTimesAndYouAsOrganiser() {
        val state = viewModel().state.value
        assertFalse(state.loading)
        assertEquals("p1", state.organiserId)
        assertEquals(day, state.date)
        assertEquals("2026-10-08T10:15:00", state.startTime)
        assertEquals("2026-10-08T11:15:00", state.endTime)
    }

    @Test
    fun anAccountWithNoPersonLeavesTheOrganiserBlank() {
        reference = reference.copy(myPersonId = null)
        assertEquals("", viewModel().state.value.organiserId)
    }

    @Test
    fun aChosenOrganiserIsNeverOverriddenByTheDefault() {
        val viewModel = viewModel()
        viewModel.setOrganiser("p2")
        viewModel.setAttendees(listOf("p1"))
        viewModel.setAttendees(emptyList())
        assertEquals("p2", viewModel.state.value.organiserId)
    }

    @Test
    fun aFailedLoadShowsTheMessageAndRetryLoadsTheForm() {
        loadFailure = ApiException("Could not reach mootmaker.")
        val viewModel = viewModel()
        assertEquals("Could not reach mootmaker.", viewModel.state.value.loadError)
        assertNull(viewModel.state.value.reference)

        loadFailure = null
        viewModel.load()
        assertNull(viewModel.state.value.loadError)
        assertEquals(reference, viewModel.state.value.reference)
    }

    // Use case F.53: the headcount sent is the organiser plus the attendees.
    @Test
    fun suggestingFetchesOnceThenStepsThroughTheListAndWraps() {
        val viewModel = viewModel()
        viewModel.setAttendees(listOf("p2"))

        viewModel.suggestRoom()
        assertEquals("r1", viewModel.state.value.roomId)
        viewModel.suggestRoom()
        assertEquals("r2", viewModel.state.value.roomId)
        viewModel.suggestRoom()
        assertEquals("r1", viewModel.state.value.roomId)
        assertEquals(listOf(Triple("2026-10-08T10:15:00", "2026-10-08T11:15:00", 2)), suggestCalls)
    }

    // Use case F.54.
    @Test
    fun changingTheTimeOrHeadcountMakesTheNextPressFetchAgain() {
        val viewModel = viewModel()
        viewModel.suggestRoom()
        viewModel.setStart(LocalTime.of(14, 0))
        viewModel.suggestRoom()
        viewModel.setAttendees(listOf("p2"))
        viewModel.suggestRoom()
        viewModel.setDate(LocalDate.of(2026, 10, 9))
        viewModel.suggestRoom()
        assertEquals(4, suggestCalls.size)
    }

    // Use case F.52.
    @Test
    fun noFreeRoomSaysSoAndLeavesTheSelectionAlone() {
        val viewModel = viewModel()
        viewModel.setRoom("r2")
        suggestions = emptyList()
        viewModel.suggestRoom()
        assertEquals(listOf(NO_ROOM_AVAILABLE_MESSAGE), viewModel.state.value.errors)
        assertEquals("r2", viewModel.state.value.roomId)
        assertFalse(viewModel.state.value.suggesting)
    }

    @Test
    fun aFailedSuggestionShowsTheFailureAndStopsSpinning() {
        val viewModel = viewModel()
        val failing = object : MeetingFormSource by source {
            override suspend fun suggestRooms(startTime: String, endTime: String, requiredCapacity: Int): List<RoomOption> =
                throw ApiException("Could not reach mootmaker.")
        }
        val offline = AddMeetingViewModel(failing, day, LocalDateTime.of(2026, 10, 7, 10, 1)).also { it.load() }
        offline.suggestRoom()
        assertEquals(listOf("Could not reach mootmaker."), offline.state.value.errors)
        assertFalse(offline.state.value.suggesting)
        assertTrue(viewModel.state.value.errors.isEmpty())
    }

    // Use case F.38.
    @Test
    fun savingSendsTheFormAndReportsTheNewMeeting() {
        val viewModel = viewModel()
        viewModel.setSubject("Kick-off")
        viewModel.setAttendees(listOf("p2"))
        viewModel.setRoom("r2")
        viewModel.save()

        assertEquals(
            MeetingDraft("Kick-off", "r2", "p1", listOf("p2"), "2026-10-08T10:15:00", "2026-10-08T11:15:00"),
            drafts.single(),
        )
        assertEquals("m-new", viewModel.state.value.savedMeetingId)
        assertFalse(viewModel.state.value.saving)
    }

    // Use case F.51: every broken rule is listed together.
    @Test
    fun aRejectedBookingListsEveryMessageAndStaysOnTheForm() {
        result = CreateResult.Rejected(listOf("Please enter a subject.", "Please select a room."))
        val viewModel = viewModel()
        viewModel.save()
        assertEquals(listOf("Please enter a subject.", "Please select a room."), viewModel.state.value.errors)
        assertNull(viewModel.state.value.savedMeetingId)

        viewModel.dismissErrors()
        assertTrue(viewModel.state.value.errors.isEmpty())
    }

    // Use case F.56.
    @Test
    fun aSecondSaveWhileTheFirstIsInFlightDoesNothing() {
        gate = CompletableDeferred()
        val viewModel = viewModel()
        viewModel.save()
        assertTrue(viewModel.state.value.saving)
        viewModel.save()
        gate!!.complete(Unit)
        assertEquals(1, drafts.size)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun aFailedRequestShowsItsMessageAndAllowsAnotherTry() {
        val failing = object : MeetingFormSource by source {
            override suspend fun create(draft: MeetingDraft): CreateResult = throw ApiException("Could not reach mootmaker.")
        }
        val viewModel = AddMeetingViewModel(failing, day, LocalDateTime.of(2026, 10, 7, 10, 1)).also { it.load() }
        viewModel.save()
        assertEquals(listOf("Could not reach mootmaker."), viewModel.state.value.errors)
        assertFalse(viewModel.state.value.saving)
    }
}

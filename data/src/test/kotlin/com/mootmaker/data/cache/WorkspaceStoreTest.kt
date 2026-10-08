package com.mootmaker.data.cache

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.live.LiveEvent
import com.mootmaker.data.meeting.AttendeeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * The store's rules, each pinned with a fake API whose every answer the test releases by hand, so
 * the order of requests, invalidations and answers is exactly the one written. See
 * mootmaker/designs/android-cache.md. The cases marked "webapp:" are the webapp's own
 * daysInvalidated/evictAndRefetch/reconcileLink scenarios, translated, so both clients keep the same
 * rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceStoreTest {

    // ---- Harness --------------------------------------------------------------------------------

    private class FakeApi : WorkspaceApi {
        val dayCalls = mutableListOf<Pair<List<LocalDate>, CompletableDeferred<List<CachedDay>>>>()
        val refCalls = mutableListOf<CompletableDeferred<Reference>>()
        val lookupCalls = mutableListOf<Pair<String, CompletableDeferred<CachedMeeting?>>>()

        override suspend fun days(dates: List<LocalDate>): List<CachedDay> =
            CompletableDeferred<List<CachedDay>>().also { dayCalls += dates to it }.await()

        override suspend fun reference(): Reference = CompletableDeferred<Reference>().also { refCalls += it }.await()

        override suspend fun meeting(id: String): CachedMeeting? =
            CompletableDeferred<CachedMeeting?>().also { lookupCalls += id to it }.await()
    }

    private val api = FakeApi()
    private var clock = 1_000_000L

    private fun TestScope.store() = WorkspaceStore(api, backgroundScope, now = { clock }, today = { TODAY })

    /** Collects [flow] in the background; the returned holder always has its latest value. */
    private class Latest<T>(var value: T? = null, var job: Job? = null) {
        fun stop() = job!!.cancel()
    }

    private fun <T> TestScope.watch(flow: Flow<T>): Latest<T> {
        val latest = Latest<T>()
        latest.job = backgroundScope.launch { flow.collect { latest.value = it } }
        runCurrent()
        return latest
    }

    /** Answers the [index]th days request with [days]; dates asked for and not in [days] come back empty. */
    private fun TestScope.answerDays(index: Int, vararg days: CachedDay) {
        val (dates, reply) = api.dayCalls[index]
        reply.complete(dates.map { date -> days.firstOrNull { it.date == date } ?: CachedDay(date, emptyList()) })
        runCurrent()
    }

    private fun TestScope.failDays(index: Int) {
        api.dayCalls[index].second.completeExceptionally(IOException("offline"))
        runCurrent()
    }

    private fun TestScope.answerLookup(index: Int, meeting: CachedMeeting?) {
        api.lookupCalls[index].second.complete(meeting)
        runCurrent()
    }

    private fun meeting(id: String, date: LocalDate, subject: String = "Stand-up") = CachedMeeting(
        id = id,
        subject = subject,
        startTime = "${date}T09:00:00",
        endTime = "${date}T09:30:00",
        roomId = "r1",
        organiserId = "p1",
        attendees = listOf(CachedAttendee("p2", AttendeeStatus.NoResponse)),
        version = "1",
    )

    private fun day(date: LocalDate, vararg meetings: CachedMeeting) = CachedDay(date, meetings.toList())

    private val reference = Reference(
        me = Me("p1", "Ada", null, TimeFormat.TwentyFourHour, DateFormat.Iso),
        people = listOf(CachedPerson("p1", "Ada"), CachedPerson("p2", "Grace")),
        rooms = listOf(CachedRoom("r1", "Boardroom", 8, null)),
        bounds = null,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 12)
        val D1: LocalDate = TODAY
        val D2: LocalDate = TODAY.plusDays(1)
        val D3: LocalDate = TODAY.plusDays(2)
    }

    // ---- Loading and serving from the store -----------------------------------------------------

    @Test
    fun `a first watch fetches, and is unknown until the answer lands`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1, D2)))

        assertEquals(listOf(listOf(D1, D2)), api.dayCalls.map { it.first })
        assertFalse(view.value!!.allKnown)
        assertTrue(view.value!!.fetching)

        answerDays(0, day(D1, meeting("m1", D1)))
        assertTrue(view.value!!.allKnown)
        assertFalse(view.value!!.fetching)
        assertEquals(listOf("m1"), view.value!!.days.first().meetings.map { it.id })
    }

    @Test
    fun `a day the answer holds nothing for is known to be empty, which is not the same as unknown`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))
        assertFalse(view.value!!.slots.getValue(D1).known)

        answerDays(0)
        val slot = view.value!!.slots.getValue(D1)
        assertTrue(slot.known)
        assertEquals(emptyList<CachedMeeting>(), slot.value!!.meetings)
    }

    @Test
    fun `a second watch of what is held is served at once, with no request`() = runTest {
        val store = store()
        val first = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1)))
        first.stop()
        runCurrent()

        val second = watch(store.days(listOf(D1)))
        assertEquals(1, api.dayCalls.size)
        assertTrue(second.value!!.allKnown)
        assertFalse(second.value!!.fetching)
    }

    @Test
    fun `only the missing dates are fetched, in one request`() = runTest {
        val store = store()
        watch(store.days(listOf(D1, D2)))
        answerDays(0)

        val week = watch(store.days(listOf(D1, D2, D3, D3.plusDays(1), D3.plusDays(2))))
        assertEquals(listOf(D3, D3.plusDays(1), D3.plusDays(2)), api.dayCalls[1].first)
        assertFalse("a partly held range waits for the rest", week.value!!.allKnown)
    }

    @Test
    fun `more than 42 missing dates go as more than one request`() = runTest {
        val store = store()
        watch(store.days((0L until 50L).map { TODAY.plusDays(it) }))
        assertEquals(listOf(42, 8), api.dayCalls.map { it.first.size })
    }

    @Test
    fun `the same dates watched twice while in flight make one request`() = runTest {
        val store = store()
        val a = watch(store.days(listOf(D1)))
        val b = watch(store.days(listOf(D1)))
        assertEquals(1, api.dayCalls.size)

        answerDays(0)
        assertTrue(a.value!!.allKnown)
        assertTrue(b.value!!.allKnown)
    }

    @Test
    fun `the reference data is fetched once and shared`() = runTest {
        val store = store()
        val a = watch(store.reference())
        val b = watch(store.reference())
        assertEquals(1, api.refCalls.size)

        api.refCalls[0].complete(reference)
        runCurrent()
        assertSame(reference, a.value!!.value)
        assertSame(reference, b.value!!.value)
    }

    // ---- Invalidation ---------------------------------------------------------------------------

    @Test
    fun `webapp - invalidating a watched date refetches it, and leaves the other dates alone`() = runTest {
        val store = store()
        watch(store.days(listOf(D1, D2)))
        answerDays(0, day(D1, meeting("m1", D1)))

        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D1.toString())))
        runCurrent()
        assertEquals(listOf(D1), api.dayCalls[1].first)
        assertEquals(2, api.dayCalls.size)
    }

    @Test
    fun `the old content stays readable, marked fetching, while a watched date is refetched`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1, "Old name")))

        store.invalidateDays(listOf(D1))
        runCurrent()
        val during = view.value!!
        assertTrue(during.allKnown)
        assertTrue(during.fetching)
        assertEquals("Old name", during.days.single().meetings.single().subject)

        answerDays(1, day(D1, meeting("m1", D1, "New name")))
        assertFalse(view.value!!.fetching)
        assertEquals("New name", view.value!!.days.single().meetings.single().subject)
    }

    @Test
    fun `webapp - an invalidation for a day nobody has fetched does nothing`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0)

        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D3.toString())))
        runCurrent()
        assertEquals(1, api.dayCalls.size)
    }

    @Test
    fun `an unwatched held date is not refetched until it is watched again, and then shows its old content meanwhile`() = runTest {
        val store = store()
        val first = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1, "Old name")))
        first.stop()
        runCurrent()

        store.invalidateDays(listOf(D1))
        runCurrent()
        assertEquals("nobody is watching, so nothing is fetched", 1, api.dayCalls.size)

        val again = watch(store.days(listOf(D1)))
        assertEquals(2, api.dayCalls.size)
        assertTrue("held content is shown at once", again.value!!.allKnown)
        assertTrue(again.value!!.fetching)
        assertEquals("Old name", again.value!!.days.single().meetings.single().subject)
    }

    @Test
    fun `this device's own write is obeyed like anyone's, without hiding what is held`() = runTest {
        // Decision 4: no own-write grace window. The day keeps its content on screen while refetched.
        val store = store()
        val view = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1)))

        store.invalidateDays(listOf(D1))
        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D1.toString())))
        runCurrent()
        assertEquals("one refetch, however many invalidations", 2, api.dayCalls.size)
        assertTrue(view.value!!.allKnown)
    }

    @Test
    fun `an unparsable date in a broadcast is ignored`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0)

        store.onLiveEvent(LiveEvent.DaysChanged(listOf("not-a-date", D1.toString())))
        runCurrent()
        assertEquals(listOf(D1), api.dayCalls[1].first)
    }

    // ---- The in-flight race ---------------------------------------------------------------------

    @Test
    fun `webapp - a date invalidated while its fetch is in flight is fetched again once that lands`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))

        store.invalidateDays(listOf(D1))
        runCurrent()
        assertEquals("not requested twice at once", 1, api.dayCalls.size)

        answerDays(0, day(D1, meeting("m1", D1, "Before the change")))
        assertEquals("the answer may predate the change, so it is fetched again", 2, api.dayCalls.size)
        assertTrue(view.value!!.allKnown)
        assertTrue(view.value!!.fetching)

        answerDays(1, day(D1, meeting("m1", D1, "After the change")))
        assertEquals(2, api.dayCalls.size)
        assertFalse(view.value!!.fetching)
        assertEquals("After the change", view.value!!.days.single().meetings.single().subject)
    }

    @Test
    fun `webapp - a fetch issued after the invalidation is trusted`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0)

        store.invalidateDays(listOf(D1))
        runCurrent()
        answerDays(1)
        assertEquals(2, api.dayCalls.size)
    }

    @Test
    fun `two invalidations during one fetch cost one more fetch, not two`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        store.invalidateDays(listOf(D1))
        store.invalidateDays(listOf(D1))
        runCurrent()

        answerDays(0)
        answerDays(1)
        assertEquals(2, api.dayCalls.size)
    }

    @Test
    fun `an invalidation of the reference data while it is in flight fetches it again`() = runTest {
        val store = store()
        watch(store.reference())
        store.invalidateReference()
        runCurrent()

        api.refCalls[0].complete(reference)
        runCurrent()
        assertEquals(2, api.refCalls.size)
    }

    // ---- After a gap in the connection ----------------------------------------------------------

    @Test
    fun `webapp - Subscribed makes everything held stale, and refetches only what is watched`() = runTest {
        val store = store()
        val ref = watch(store.reference())
        api.refCalls[0].complete(reference)
        val watched = watch(store.days(listOf(D1)))
        answerDays(0)
        val other = watch(store.days(listOf(D2)))
        answerDays(1)
        other.stop()
        runCurrent()

        store.onLiveEvent(LiveEvent.Subscribed)
        runCurrent()
        assertEquals("rooms and people are never broadcast, so the gap refetches them", 2, api.refCalls.size)
        assertEquals(listOf(D1), api.dayCalls[2].first)
        assertEquals(3, api.dayCalls.size)
        assertTrue(ref.value!!.known)
        assertTrue(watched.value!!.allKnown)

        val back = watch(store.days(listOf(D2)))
        assertEquals("the unwatched day was made stale too", listOf(D2), api.dayCalls[3].first)
        assertTrue(back.value!!.allKnown)
    }

    // ---- Failures -------------------------------------------------------------------------------

    @Test
    fun `a failed refetch keeps what was held and reports the error`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1)))

        store.invalidateDays(listOf(D1))
        runCurrent()
        failDays(1)
        assertTrue(view.value!!.allKnown)
        assertFalse(view.value!!.fetching)
        assertEquals("offline", view.value!!.error!!.message)
        assertEquals("m1", view.value!!.days.single().meetings.single().id)
    }

    @Test
    fun `a failed first fetch reports the error with nothing known, and is not retried by itself`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))
        failDays(0)
        assertFalse(view.value!!.allKnown)
        assertFalse(view.value!!.fetching)
        assertEquals("offline", view.value!!.error!!.message)

        store.invalidateDays(listOf(D1))
        runCurrent()
        assertEquals("no retry loop against a failing API", 1, api.dayCalls.size)
    }

    @Test
    fun `retry fetches again what is watched and failed`() = runTest {
        val store = store()
        val view = watch(store.days(listOf(D1)))
        failDays(0)

        store.retry()
        runCurrent()
        assertEquals(2, api.dayCalls.size)
        assertNull(view.value!!.error)
        assertTrue(view.value!!.fetching)

        answerDays(1)
        assertTrue(view.value!!.allKnown)
    }

    // ---- Identity changes -----------------------------------------------------------------------

    @Test
    fun `clear forgets everything, and drops an answer that lands after it`() = runTest {
        val store = store()
        val first = watch(store.days(listOf(D1)))
        first.stop()
        runCurrent()

        store.clear()
        answerDays(0, day(D1, meeting("m1", D1, "The previous account's meeting")))

        val next = watch(store.days(listOf(D1)))
        assertEquals("nothing of the old account was kept", 2, api.dayCalls.size)
        assertFalse(next.value!!.allKnown)
    }

    // ---- The resume safety net ------------------------------------------------------------------

    @Test
    fun `a resume refetches only what is watched and older than the limit`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0)
        val unwatched = watch(store.days(listOf(D2)))
        answerDays(1)
        unwatched.stop()
        runCurrent()

        clock += WorkspaceStore.RESUME_MAX_AGE_MILLIS - 1
        store.refreshOlderThan(WorkspaceStore.RESUME_MAX_AGE_MILLIS)
        runCurrent()
        assertEquals("young enough", 2, api.dayCalls.size)

        clock += 2
        store.refreshOlderThan(WorkspaceStore.RESUME_MAX_AGE_MILLIS)
        runCurrent()
        assertEquals(listOf(D1), api.dayCalls[2].first)
        assertEquals(3, api.dayCalls.size)
    }

    // ---- Bounds ---------------------------------------------------------------------------------

    @Test
    fun `unwatched days far from today are dropped, watched ones never`() = runTest {
        val store = store()
        val far = TODAY.plusDays(WorkspaceStore.DAYS_AROUND_TODAY + 1)
        val farWatch = watch(store.days(listOf(far)))
        answerDays(0)
        assertTrue("watched, so kept however far", farWatch.value!!.allKnown)

        farWatch.stop()
        runCurrent()
        watch(store.days(listOf(far)))
        assertEquals("dropped once unwatched, so fetched again", 2, api.dayCalls.size)
    }

    @Test
    fun `unwatched days within reach of today are all kept`() = runTest {
        val store = store()
        val near = (-WorkspaceStore.DAYS_AROUND_TODAY..WorkspaceStore.DAYS_AROUND_TODAY).map { TODAY.plusDays(it) }
        near.chunked(40).forEach { chunk ->
            clock += 1
            val view = watch(store.days(chunk))
            answerDays(api.dayCalls.size - 1)
            view.stop()
            runCurrent()
        }
        val before = api.dayCalls.size
        val again = watch(store.days(near))
        assertEquals("all 121 still held", before, api.dayCalls.size)
        assertTrue(again.value!!.allKnown)
    }

    // ---- Meetings -------------------------------------------------------------------------------

    @Test
    fun `a meeting in a loaded day is shown with no request`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1)))

        val view = watch(store.meeting("m1"))
        assertTrue(view.value!!.known)
        assertEquals("m1", view.value!!.meeting!!.id)
        assertEquals(0, api.lookupCalls.size)
    }

    @Test
    fun `a meeting no loaded day holds is looked up by id`() = runTest {
        val store = store()
        val view = watch(store.meeting("m9"))
        assertEquals(listOf("m9"), api.lookupCalls.map { it.first })
        assertFalse(view.value!!.known)

        answerLookup(0, meeting("m9", D3))
        assertTrue(view.value!!.known)
        assertEquals("m9", view.value!!.meeting!!.id)
    }

    @Test
    fun `a lookup that finds nothing reads as known and gone (H_73)`() = runTest {
        val store = store()
        val view = watch(store.meeting("m9"))
        answerLookup(0, null)
        assertTrue(view.value!!.known)
        assertNull(view.value!!.meeting)
    }

    @Test
    fun `while a meeting is watched, a change to its day refetches that day`() = runTest {
        val store = store()
        val dayView = watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1, "Old name")))
        dayView.stop()
        runCurrent()

        val view = watch(store.meeting("m1"))
        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D1.toString())))
        runCurrent()
        assertEquals("the day the meeting came from is watched through it", listOf(D1), api.dayCalls[1].first)
        assertTrue(view.value!!.fetching)

        answerDays(1, day(D1, meeting("m1", D1, "New name")))
        assertEquals("New name", view.value!!.meeting!!.subject)
        assertEquals(0, api.lookupCalls.size)
    }

    @Test
    fun `a cancelled meeting leaves its day, and the lookup that follows says it is gone (O_123)`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1, "Doomed")))
        val view = watch(store.meeting("m1"))

        store.invalidateDays(listOf(D1))
        runCurrent()
        answerDays(1)
        assertEquals("gone from its day: moved or cancelled, so ask", listOf("m1"), api.lookupCalls.map { it.first })
        assertTrue("the last known meeting stays up meanwhile, no spinner", view.value!!.known)
        assertEquals("Doomed", view.value!!.meeting!!.subject)
        assertTrue(view.value!!.fetching)

        answerLookup(0, null)
        assertTrue(view.value!!.known)
        assertNull(view.value!!.meeting)
        assertFalse(view.value!!.fetching)
    }

    @Test
    fun `a meeting moved to another day is followed there (O_121)`() = runTest {
        val store = store()
        watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1)))
        val view = watch(store.meeting("m1"))

        store.invalidateDays(listOf(D1, D3))
        runCurrent()
        answerDays(1)
        answerLookup(0, meeting("m1", D3))
        assertEquals(D3, view.value!!.meeting!!.date)
        assertEquals(1, api.lookupCalls.size)
    }

    @Test
    fun `webapp - a meeting held without its day is refetched when its date changes`() = runTest {
        val store = store()
        val view = watch(store.meeting("m9"))
        answerLookup(0, meeting("m9", D3, "Old name"))

        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D3.toString())))
        runCurrent()
        assertEquals(2, api.lookupCalls.size)
        assertEquals("Old name", view.value!!.meeting!!.subject)

        answerLookup(1, meeting("m9", D3, "New name"))
        assertEquals("New name", view.value!!.meeting!!.subject)
    }

    @Test
    fun `a lookup in flight when any date changes is fetched again, since its date is not yet known`() = runTest {
        val store = store()
        watch(store.meeting("m9"))
        store.onLiveEvent(LiveEvent.DaysChanged(listOf(D2.toString())))
        runCurrent()

        answerLookup(0, meeting("m9", D2, "Maybe before the change"))
        assertEquals(2, api.lookupCalls.size)
    }

    @Test
    fun `the newest answer wins when a meeting is both looked up and in a day`() = runTest {
        val store = store()
        val view = watch(store.meeting("m1"))
        answerLookup(0, meeting("m1", D1, "From the lookup"))

        watch(store.days(listOf(D1)))
        answerDays(0, day(D1, meeting("m1", D1, "From the day, later")))
        assertEquals("From the day, later", view.value!!.meeting!!.subject)
    }

    @Test
    fun `invalidating a meeting refetches the watched day holding it, and its lookup`() = runTest {
        val store = store()
        watch(store.days(listOf(D1, D2)))
        answerDays(0, day(D1, meeting("m1", D1)))
        val lookup = watch(store.meeting("m9"))
        answerLookup(0, meeting("m9", D3))

        store.invalidateMeeting("m1")
        store.invalidateMeeting("m9")
        runCurrent()
        assertEquals(listOf(D1), api.dayCalls[1].first)
        assertEquals(2, api.lookupCalls.size)
        assertTrue(lookup.value!!.fetching)
    }
}

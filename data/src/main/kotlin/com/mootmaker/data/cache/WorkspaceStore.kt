package com.mootmaker.data.cache

import com.mootmaker.data.live.LiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a screen sees of one held thing.
 *
 * [known] is false until a fetch has landed, which is when a screen shows its full spinner; once
 * known, a refetch keeps [value] on screen with [fetching] set, which is when it shows the slim bar
 * (use case M.92). A known, empty day is the only thing that may read as "no meetings".
 */
data class Slot<out T>(
    val known: Boolean = false,
    val value: T? = null,
    val fetching: Boolean = false,
    /** Why the last fetch failed, until the next one succeeds. */
    val error: Throwable? = null,
)

/** The days a screen asked for, each as a [Slot]. */
data class DaysView(val slots: Map<LocalDate, Slot<CachedDay>>) {
    /** Every requested day has landed at least once: the screen can draw. */
    val allKnown: Boolean get() = slots.values.all { it.known }
    val fetching: Boolean get() = slots.values.any { it.fetching }
    val error: Throwable? get() = slots.values.firstNotNullOfOrNull { it.error }
    /** The known days, in date order. */
    val days: List<CachedDay> get() = slots.toSortedMap().values.mapNotNull { it.value }
}

/**
 * One meeting as its details screen sees it. Known with a null [meeting] means it no longer exists:
 * cancelled, or aged out of retention (use cases H.73, O.123).
 */
data class MeetingView(
    val known: Boolean,
    val meeting: CachedMeeting?,
    val fetching: Boolean,
    val error: Throwable?,
)

/**
 * The one in-memory store of what the app has seen: the reference data, days, and meetings looked
 * up by id. Every read screen draws from it, and the live channel keeps it honest. See
 * mootmaker/designs/android-cache.md for the decisions behind every rule here.
 *
 * Screens **watch** what they show. A watch fetches anything not yet known, and while it lasts, an
 * invalidation of what it shows is refetched at once; an invalidated thing nobody watches only
 * becomes stale, and is refetched when next watched. Stale data is never hidden while it is
 * refetched.
 *
 * Every invalidation bumps the key's generation. A fetch remembers the generations it was issued
 * under, so a response that was in flight when its key was invalidated is stored but stays stale,
 * and is fetched again (the webapp's `reconcileAfterFetch`). [clear] bumps an epoch the same way, so
 * a response issued before an identity change is dropped rather than shown to the next account.
 *
 * Thread-safe: every change happens under one lock, and fetches are launched in [scope] after it is
 * released.
 */
class WorkspaceStore(
    private val api: WorkspaceApi,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private sealed interface Key {
        data object Ref : Key
        data class Day(val date: LocalDate) : Key
        data class ById(val id: String) : Key
    }

    private data class Record<T>(
        val known: Boolean = false,
        val value: T? = null,
        val fetching: Boolean = false,
        val error: Throwable? = null,
        /** Invalidated since its value was fetched. */
        val stale: Boolean = false,
        val generation: Long = 0,
        val fetchedAt: Long = 0,
        /** Store-wide landing order: which of two sources of one meeting is newer. */
        val seq: Long = 0,
        val lastUsed: Long = 0,
    ) {
        fun slot() = Slot(known, value, fetching, error)
    }

    /** An immutable picture of the store, published after every change. */
    private data class Snapshot(
        val ref: Record<Reference> = Record(),
        val days: Map<LocalDate, Record<CachedDay>> = emptyMap(),
        val byId: Map<String, Record<CachedMeeting?>> = emptyMap(),
    )

    private val lock = Any()
    private var snap = Snapshot()
    private val published = MutableStateFlow(snap)

    private var epoch = 0L
    private var landings = 0L
    private var refWatchers = 0
    private val dayWatchers = mutableMapOf<LocalDate, Int>()
    private val meetingWatchers = mutableMapOf<String, Int>()

    // ---- Watching -------------------------------------------------------------------------------

    /** The reference data, fetched while collected. */
    fun reference(): Flow<Slot<Reference>> = watching(
        acquire = { refWatchers++ },
        release = { refWatchers-- },
    ) { it.ref.slot() }

    /** [dates], fetched together where missing or stale, while collected. */
    fun days(dates: Collection<LocalDate>): Flow<DaysView> {
        val wanted = dates.toSet()
        return watching(
            acquire = { wanted.forEach { dayWatchers.merge(it, 1, Int::plus) } },
            release = { wanted.forEach { date -> dayWatchers.compute(date) { _, n -> if (n == null || n <= 1) null else n - 1 } } },
        ) { snapshot -> DaysView(wanted.associateWith { snapshot.days[it]?.slot() ?: Slot() }) }
    }

    /**
     * One meeting, found in whichever loaded day holds it, or looked up by id when none does. While
     * collected, the day it was found in counts as watched, so a change to that day refetches it; and
     * if a refetched day no longer holds it, it is looked up by id to tell moved from cancelled.
     */
    fun meeting(id: String): Flow<MeetingView> = watching(
        acquire = { meetingWatchers.merge(id, 1, Int::plus) },
        release = { meetingWatchers.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 } },
    ) { snapshot -> meetingView(snapshot, id) }

    private fun <V> watching(acquire: () -> Unit, release: () -> Unit, view: (Snapshot) -> V): Flow<V> = flow {
        change { acquire() }
        try {
            emitAll(published.map(view).distinctUntilChanged())
        } finally {
            change { release() }
        }
    }

    // ---- Invalidation ---------------------------------------------------------------------------

    /** Applies a live event: named dates are stale; after a gap in the connection, everything is. */
    fun onLiveEvent(event: LiveEvent) = when (event) {
        is LiveEvent.DaysChanged -> invalidateDays(event.dates.mapNotNull(::parseDate))
        LiveEvent.Subscribed -> invalidateAll()
    }

    /** Marks [dates], and any meeting looked up by id on them, stale. Watched ones are refetched. */
    fun invalidateDays(dates: Collection<LocalDate>) = change {
        val set = dates.toSet()
        snap = snap.copy(
            days = snap.days.mapValues { (date, record) -> if (date in set) record.invalidated() else record },
            // A lookup still in flight has no date yet, so it may be on any of them: distrust it too.
            byId = snap.byId.mapValues { (_, record) ->
                if (record.value?.date in set || (record.fetching && !record.known)) record.invalidated() else record
            },
        )
    }

    /** Marks the reference data stale: rooms and people are never broadcast. */
    fun invalidateReference() = change { snap = snap.copy(ref = snap.ref.invalidated()) }

    /** Marks everything stale, as after a gap in the live connection. */
    fun invalidateAll() = change {
        snap = Snapshot(
            ref = snap.ref.invalidated(),
            days = snap.days.mapValues { it.value.invalidated() },
            byId = snap.byId.mapValues { it.value.invalidated() },
        )
    }

    /** Marks what is watched and was fetched more than [maxAgeMillis] ago stale: the resume safety net. */
    fun refreshOlderThan(maxAgeMillis: Long) = change {
        val cutoff = now() - maxAgeMillis
        fun <T> Record<T>.aged(watched: Boolean) = if (watched && known && !stale && fetchedAt < cutoff) invalidated() else this
        snap = Snapshot(
            ref = snap.ref.aged(refWatchers > 0),
            days = snap.days.mapValues { (date, record) -> record.aged(isWatched(Key.Day(date))) },
            byId = snap.byId.mapValues { (id, record) -> record.aged(isWatched(Key.ById(id))) },
        )
    }

    /** Fetches again whatever is watched and failed: the screen's Try again. */
    fun retry() = change {
        fun <T> Record<T>.cleared() = if (error != null) copy(error = null, stale = true) else this
        snap = Snapshot(
            ref = if (refWatchers > 0) snap.ref.cleared() else snap.ref,
            days = snap.days.mapValues { (date, record) -> if (isWatched(Key.Day(date))) record.cleared() else record },
            byId = snap.byId.mapValues { (id, record) -> if (isWatched(Key.ById(id))) record.cleared() else record },
        )
    }

    /**
     * Forgets everything, on any change of identity: sign-out, account deletion, environment switch.
     * A response still in flight lands in the old epoch and is dropped.
     */
    fun clear() = synchronized(lock) {
        epoch++
        snap = Snapshot()
        published.value = snap
    }

    private fun <T> Record<T>.invalidated(): Record<T> =
        if (known || fetching) copy(stale = true, generation = generation + 1) else this

    // ---- The engine -----------------------------------------------------------------------------

    /** Runs [mutation] under the lock, then starts every fetch the new state calls for, and publishes. */
    private fun change(mutation: () -> Unit) {
        val launches = synchronized(lock) {
            mutation()
            touchWatched()
            val launches = planFetches()
            prune()
            published.value = snap
            launches
        }
        launches.forEach { scope.launch { it() } }
    }

    /**
     * Marks every key that should be fetched now as fetching, and returns the fetches to run. A key
     * is fetched when it is watched and either unknown or stale, unless it is already in flight (it
     * is then refetched when that lands, if it was invalidated meanwhile) or its last fetch failed
     * (that waits for [retry]).
     */
    private fun planFetches(): List<suspend () -> Unit> {
        val launches = mutableListOf<suspend () -> Unit>()
        val issuedEpoch = epoch

        if (refWatchers > 0 && snap.ref.needsFetch()) {
            val generation = snap.ref.generation
            snap = snap.copy(ref = snap.ref.copy(fetching = true))
            launches += { fetch({ api.reference() }, issuedEpoch) { result -> landRef(result, generation) } }
        }

        val dueDays = snap.days.keys.union(dayWatchers.keys).sorted().filter { date ->
            isWatched(Key.Day(date)) && (snap.days[date] ?: Record()).needsFetch()
        }
        if (dueDays.isNotEmpty()) {
            val generations = dueDays.associateWith { snap.days[it]?.generation ?: 0 }
            snap = snap.copy(days = snap.days + dueDays.associateWith { (snap.days[it] ?: Record()).copy(fetching = true) })
            dueDays.chunked(MAX_DATES_PER_REQUEST).forEach { chunk ->
                launches += { fetch({ api.days(chunk) }, issuedEpoch) { result -> landDays(chunk, result, generations) } }
            }
        }

        for (id in meetingWatchers.keys) {
            val record = snap.byId[id] ?: Record()
            // Looked up when nothing held can be trusted for it, or when the lookup is the answer and is stale.
            val trusted = resolve(snap, id).trusted
            val lookUp = !record.fetching && record.error == null &&
                (trusted == null || (trusted.dayDate == null && record.stale))
            if (lookUp) {
                val generation = record.generation
                snap = snap.copy(byId = snap.byId + (id to record.copy(fetching = true)))
                launches += { fetch({ api.meeting(id) }, issuedEpoch) { result -> landMeeting(id, result, generation) } }
            }
        }
        return launches
    }

    private fun <T> Record<T>.needsFetch() = !fetching && error == null && (!known || stale)

    /** Runs one fetch and lands its result, or its failure, unless the store was cleared meanwhile. */
    private suspend fun <T> fetch(call: suspend () -> T, issuedEpoch: Long, land: (Result<T>) -> Unit) {
        val result = try {
            Result.success(call())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        change {
            if (epoch == issuedEpoch) land(result)
        }
    }

    private fun <T> Record<T>.landed(result: Result<T>, issuedGeneration: Long): Record<T> =
        result.fold(
            onSuccess = { value ->
                copy(
                    known = true,
                    value = value,
                    fetching = false,
                    error = null,
                    stale = generation != issuedGeneration,
                    fetchedAt = now(),
                    seq = ++landings,
                )
            },
            // A failure keeps what was held; it does not count as a fetch of the current generation.
            onFailure = { copy(fetching = false, error = it) },
        )

    private fun landRef(result: Result<Reference>, generation: Long) {
        snap = snap.copy(ref = snap.ref.landed(result, generation))
    }

    private fun landDays(dates: List<LocalDate>, result: Result<List<CachedDay>>, generations: Map<LocalDate, Long>) {
        val byDate = result.getOrNull()?.associateBy { it.date }
        snap = snap.copy(
            days = snap.days + dates.associateWith { date ->
                val record = snap.days[date] ?: Record()
                // A date the API left out of its answer holds nothing.
                val dayResult = result.map { byDate?.get(date) ?: CachedDay(date, emptyList()) }
                record.landed(dayResult, generations.getValue(date))
            },
        )
    }

    private fun landMeeting(id: String, result: Result<CachedMeeting?>, generation: Long) {
        snap = snap.copy(byId = snap.byId + (id to (snap.byId[id] ?: Record()).landed(result, generation)))
    }

    // ---- Meetings -------------------------------------------------------------------------------

    /** One answer for a meeting: from a loaded day ([dayDate] set) or from its by-id lookup. */
    private data class Answer(val meeting: CachedMeeting?, val seq: Long, val dayDate: LocalDate?)

    /**
     * [trusted] is the newest answer that still stands; [shown] is what to keep on screen meanwhile.
     * An answer stops standing once the day of its own date has landed since without the meeting: it
     * has moved or been cancelled, and only a fresh lookup can say which. Until that lookup lands, the
     * last answer stays on screen as refreshing rather than dropping back to the spinner.
     */
    private data class Resolution(val trusted: Answer?, val shown: Answer?)

    private fun resolve(snapshot: Snapshot, id: String): Resolution {
        val fromDays = snapshot.days.values.mapNotNull { record ->
            record.value?.meetings?.firstOrNull { it.id == id }?.let { Answer(it, record.seq, record.value.date) }
        }
        val fromLookup = snapshot.byId[id]?.takeIf { it.known }?.let { Answer(it.value, it.seq, null) }
        val answers = fromDays + listOfNotNull(fromLookup)
        val standing = answers.filterNot { answer ->
            val meeting = answer.meeting ?: return@filterNot false
            val day = snapshot.days[meeting.date] ?: return@filterNot false
            day.known && day.seq > answer.seq && day.value?.meetings?.none { it.id == id } == true
        }
        return Resolution(trusted = standing.maxByOrNull { it.seq }, shown = answers.maxByOrNull { it.seq })
    }

    private fun meetingView(snapshot: Snapshot, id: String): MeetingView {
        val (trusted, shown) = resolve(snapshot, id)
        val answer = trusted ?: shown
        val lookup = snapshot.byId[id]
        val day = answer?.dayDate?.let { snapshot.days[it] }
        return MeetingView(
            known = answer != null,
            meeting = answer?.meeting,
            fetching = lookup?.fetching == true || day?.fetching == true || (trusted == null && shown != null && lookup?.error == null),
            error = lookup?.error ?: day?.error,
        )
    }

    // ---- Bookkeeping ----------------------------------------------------------------------------

    private fun isWatched(key: Key): Boolean = when (key) {
        Key.Ref -> refWatchers > 0
        is Key.ById -> (meetingWatchers[key.id] ?: 0) > 0
        is Key.Day -> (dayWatchers[key.date] ?: 0) > 0 ||
            meetingWatchers.keys.any { id -> resolve(snap, id).let { it.trusted ?: it.shown }?.dayDate == key.date }
    }

    private fun touchWatched() {
        val at = now()
        snap = snap.copy(
            days = snap.days.mapValues { (date, record) -> if (isWatched(Key.Day(date))) record.copy(lastUsed = at) else record },
            byId = snap.byId.mapValues { (id, record) -> if (isWatched(Key.ById(id))) record.copy(lastUsed = at) else record },
        )
    }

    /**
     * Keeps memory bounded: unwatched days more than [DAYS_AROUND_TODAY] days from today go first,
     * then the least recently used unwatched days beyond [MAX_DAYS], and the least recently used
     * unwatched lookups beyond [MAX_LOOKUPS]. Nothing watched or in flight is ever dropped.
     */
    private fun prune() {
        val day0 = today()
        val droppable = snap.days.filter { (date, record) -> !record.fetching && !isWatched(Key.Day(date)) }
        val far = droppable.keys.filter { kotlin.math.abs(ChronoUnit.DAYS.between(day0, it)) > DAYS_AROUND_TODAY }.toSet()
        var days = snap.days - far
        val excess = days.size - MAX_DAYS
        if (excess > 0) {
            days = days - droppable.filterKeys { it !in far }.entries.sortedBy { it.value.lastUsed }.take(excess).map { it.key }.toSet()
        }
        var byId = snap.byId
        val excessLookups = byId.size - MAX_LOOKUPS
        if (excessLookups > 0) {
            byId = byId - byId.filter { (id, record) -> !record.fetching && !isWatched(Key.ById(id)) }
                .entries.sortedBy { it.value.lastUsed }.take(excessLookups).map { it.key }.toSet()
        }
        snap = snap.copy(days = days, byId = byId)
    }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    companion object {
        const val DAYS_AROUND_TODAY = 60L
        const val MAX_DAYS = 200
        const val MAX_LOOKUPS = 50

        /** How old what a screen shows may be before a resume refetches it: the safety net. */
        const val RESUME_MAX_AGE_MILLIS = 5 * 60 * 1000L
    }
}

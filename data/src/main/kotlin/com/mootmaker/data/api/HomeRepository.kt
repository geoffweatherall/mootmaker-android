package com.mootmaker.data.api

import com.mootmaker.data.agenda.Agenda
import com.mootmaker.data.agenda.NeedsResponseItem
import com.mootmaker.data.agenda.SEARCH_STEP_DAYS
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.buildAgenda
import com.mootmaker.data.agenda.needsResponse
import com.mootmaker.data.agenda.windowEnd
import com.mootmaker.data.cache.CachedDay
import com.mootmaker.data.cache.Loaded
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import com.mootmaker.data.cache.peopleById
import com.mootmaker.data.cache.toDayInputs
import com.mootmaker.data.cache.toRoomInputs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** What the home screen shows. [agenda] is null when the account has no linked Person (D.24). */
data class HomeData(
    val name: String?,
    val timeFormat: TimeFormat,
    val agenda: Agenda?,
    /** Invitations not yet answered in the window, soonest first. Empty when there is no linked Person. */
    val needsResponse: List<NeedsResponseItem> = emptyList(),
    /** The last day the needs-response window covers. */
    val windowEnd: LocalDate,
    /** The caller's avatar, or null when they have none (or no Person). */
    val avatarUrl: String? = null,
)

/** A failed request, with the message to show. GraphQL errors are shown as the API words them. */
class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface HomeSource {
    /**
     * Home's data for the window starting [today], widened [searchLevel] times by [SEARCH_STEP_DAYS]
     * days, as it changes: from the store at once when held, then as refetches land.
     */
    fun observe(today: LocalDate, searchLevel: Int = 0): Flow<Loaded<HomeData>>

    /** Fetches again whatever failed: the screen's Try again. */
    fun retry()
}

/**
 * Home over the [WorkspaceStore]: the reference data and the window's days. Nothing here fetches;
 * the store does, and keeps what it holds honest (mootmaker/designs/android-cache.md).
 */
class HomeRepository(private val store: WorkspaceStore) : HomeSource {
    override fun retry() = store.retry()

    override fun observe(today: LocalDate, searchLevel: Int): Flow<Loaded<HomeData>> {
        val end = windowEnd(today, searchLevel)
        val dates = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        return combine(store.reference(), store.days(dates)) { reference, days ->
            val held = reference.value
            Loaded(
                data = if (reference.known && held != null && days.allKnown) homeData(held, days.days, today, end) else null,
                fetching = reference.fetching || days.fetching,
                error = reference.error ?: days.error,
            )
        }
    }

    companion object {
        const val NETWORK_MESSAGE = "Couldn't reach Mootmaker. Check your connection and try again."
    }
}

/** What Home shows, from what the store holds. */
fun homeData(reference: Reference, days: List<CachedDay>, today: LocalDate, windowEnd: LocalDate): HomeData {
    val rooms = reference.rooms.toRoomInputs()
    val dayInputs = days.toDayInputs(reference.peopleById())
    val me = reference.me
    return HomeData(
        name = me?.name,
        avatarUrl = me?.avatarUrl,
        timeFormat = me?.timeFormat ?: TimeFormat.TwentyFourHour,
        agenda = me?.let { person -> buildAgenda(person.id, today, dayInputs, rooms) },
        needsResponse = me?.let { person -> needsResponse(person.id, dayInputs, rooms) }.orEmpty(),
        windowEnd = windowEnd,
    )
}

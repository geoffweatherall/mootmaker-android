package com.mootmaker.data.api

import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.availability.AvailabilityMeeting
import com.mootmaker.data.availability.AvailabilityRoom
import com.mootmaker.data.availability.RoomCard
import com.mootmaker.data.availability.buildRoomCards
import com.mootmaker.data.cache.CachedDay
import com.mootmaker.data.cache.Loaded
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** The window the server allows navigating in: [earliest] is always a Monday. */
data class DateBounds(val earliest: LocalDate, val latest: LocalDate)

data class AvailabilityData(
    val timeFormat: TimeFormat,
    val rooms: List<RoomCard>,
    /** Null when the server didn't say, which leaves navigation unbounded. */
    val bounds: DateBounds?,
)

interface AvailabilitySource {
    /** One day's room availability, as it changes: from the store at once when held. */
    fun observe(date: LocalDate): Flow<Loaded<AvailabilityData>>

    /** Fetches again whatever failed: the screen's Try again. */
    fun retry()
}

/** One day's room availability over the [WorkspaceStore]: the reference data and that one day. */
class AvailabilityRepository(private val store: WorkspaceStore) : AvailabilitySource {
    override fun observe(date: LocalDate): Flow<Loaded<AvailabilityData>> =
        combine(store.reference(), store.days(listOf(date))) { reference, days ->
            val held = reference.value
            val day = days.slots.getValue(date)
            Loaded(
                data = if (reference.known && held != null && day.known) availabilityData(held, day.value!!) else null,
                fetching = reference.fetching || days.fetching,
                error = reference.error ?: days.error,
            )
        }

    override fun retry() = store.retry()
}

/** What Room Availability shows for one day, from what the store holds. */
fun availabilityData(reference: Reference, day: CachedDay): AvailabilityData = AvailabilityData(
    timeFormat = reference.me?.timeFormat ?: TimeFormat.TwentyFourHour,
    rooms = buildRoomCards(
        rooms = reference.rooms.map { AvailabilityRoom(it.id, it.name, it.capacity, it.color) },
        meetings = day.meetings.map { AvailabilityMeeting(it.id, it.subject, it.startTime, it.endTime, it.roomId) },
    ),
    bounds = reference.bounds?.let { DateBounds(it.earliest, it.latest) },
)

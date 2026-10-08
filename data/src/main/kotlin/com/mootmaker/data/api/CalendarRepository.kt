package com.mootmaker.data.api

import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.cache.CachedDay
import com.mootmaker.data.cache.Loaded
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceStore
import com.mootmaker.data.cache.peopleById
import com.mootmaker.data.cache.toDayInputs
import com.mootmaker.data.cache.toRoomInputs
import com.mootmaker.data.calendar.CalendarBounds
import com.mootmaker.data.calendar.buildWeek
import com.mootmaker.data.calendar.sortedPeople
import com.mootmaker.data.calendar.workWeekDates
import com.mootmaker.data.meeting.PersonRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

data class CalendarData(
    val timeFormat: TimeFormat,
    /** Everyone, sorted by name, for the person selector. */
    val people: List<PersonRef>,
    /** Monday to Friday of the requested week, with the person's meetings. */
    val week: List<AgendaDay>,
    /** Null when the server didn't say, which leaves navigation unbounded. */
    val bounds: CalendarBounds?,
)

interface CalendarSource {
    /** One person's working week, as it changes: from the store at once when held. */
    fun observe(personId: String, monday: LocalDate): Flow<Loaded<CalendarData>>

    /** Fetches again whatever failed: the screen's Try again. */
    fun retry()
}

/**
 * One person's working week over the [WorkspaceStore]. Days hold everyone's meetings, so a week
 * fetched for one person serves every person: changing person costs no request.
 */
class CalendarRepository(private val store: WorkspaceStore) : CalendarSource {
    override fun observe(personId: String, monday: LocalDate): Flow<Loaded<CalendarData>> =
        combine(store.reference(), store.days(workWeekDates(monday))) { reference, days ->
            val held = reference.value
            Loaded(
                data = if (reference.known && held != null && days.allKnown) calendarData(held, days.days, personId, monday) else null,
                fetching = reference.fetching || days.fetching,
                error = reference.error ?: days.error,
            )
        }

    override fun retry() = store.retry()
}

/** What the calendar shows for [personId]'s week, from what the store holds. */
fun calendarData(reference: Reference, days: List<CachedDay>, personId: String, monday: LocalDate): CalendarData = CalendarData(
    timeFormat = reference.me?.timeFormat ?: TimeFormat.TwentyFourHour,
    people = sortedPeople(reference.people.map { PersonRef(it.id, it.name) }),
    week = buildWeek(personId, monday, days.toDayInputs(reference.peopleById()), reference.rooms.toRoomInputs()),
    bounds = reference.bounds,
)

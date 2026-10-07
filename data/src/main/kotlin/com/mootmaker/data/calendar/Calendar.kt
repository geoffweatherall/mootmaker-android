package com.mootmaker.data.calendar

import com.mootmaker.data.agenda.AgendaDay
import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.agendaDay
import com.mootmaker.data.meeting.PersonRef
import java.time.DayOfWeek
import java.time.LocalDate

const val WORK_DAYS_PER_WEEK = 5

/** The Monday on or before [date]: the calendar shows Monday to Friday, one week at a time. */
fun startOfWorkWeek(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** The five dates a week shows, Monday first. */
fun workWeekDates(monday: LocalDate): List<LocalDate> = List(WORK_DAYS_PER_WEEK) { monday.plusDays(it.toLong()) }

/** The window the server allows navigating in, as published in `workspace.boundaries`. */
data class CalendarBounds(val earliest: LocalDate, val latest: LocalDate)

/** One person's working week: their meetings (organised or attended) on each day, by start time. */
fun buildWeek(personId: String, monday: LocalDate, days: List<DayInput>, rooms: List<RoomInput>): List<AgendaDay> =
    workWeekDates(monday).map { agendaDay(personId, it, days, rooms) }

/** Previous week is allowed while its Monday is not before the earliest retained week (use case G.62). */
fun canGoToPreviousWeek(monday: LocalDate, bounds: CalendarBounds?): Boolean =
    bounds == null || !monday.minusWeeks(1).isBefore(startOfWorkWeek(bounds.earliest))

/** Next week is allowed while its Monday is not after the latest bookable date (use case G.62). */
fun canGoToNextWeek(monday: LocalDate, bounds: CalendarBounds?): Boolean =
    bounds == null || !monday.plusWeeks(1).isAfter(bounds.latest)

/** People in the selector, sorted by name. */
fun sortedPeople(people: List<PersonRef>): List<PersonRef> = people.sortedBy { it.name.lowercase() }

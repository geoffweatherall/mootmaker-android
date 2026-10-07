package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloNetworkException
import com.mootmaker.data.agenda.Agenda
import com.mootmaker.data.agenda.NeedsResponseItem
import com.mootmaker.data.agenda.SEARCH_STEP_DAYS
import com.mootmaker.data.agenda.needsResponse
import com.mootmaker.data.agenda.windowEnd
import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.MeetingInput
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.buildAgenda
import com.mootmaker.data.graphql.HomeQuery
import com.mootmaker.data.graphql.type.AttendeeStatus as ApiAttendeeStatus
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.meeting.AttendeeStatus
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import java.io.IOException
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
)

/** A failed request, with the message to show. GraphQL errors are shown as the API words them. */
class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface HomeSource {
    /** [searchLevel] widens the needs-response window by [SEARCH_STEP_DAYS] days per step. */
    suspend fun load(today: LocalDate, searchLevel: Int = 0): HomeData
}

/**
 * Loads the home screen through the API's composite `workspace` entry point: one request for
 * `me`, the rooms and today's and tomorrow's days.
 *
 * Refetches on every call. Apollo Kotlin's normalized cache behaves differently from the webapp's
 * Apollo Client, so the design defers caching to M6 and starts with "refetch when a screen becomes
 * visible".
 */
class HomeRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
) : HomeSource {
    override suspend fun load(today: LocalDate, searchLevel: Int): HomeData {
        val windowEnd = windowEnd(today, searchLevel)
        val dates = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(windowEnd) }.map { it.toString() }.toList()
        val token = idToken()
        val response = try {
            apollo().query(HomeQuery(Optional.present(dates)))
                .addHttpHeader("Authorization", token)
                .execute()
        } catch (network: IOException) {
            throw ApiException(NETWORK_MESSAGE, network)
        }
        val workspace = response.data?.workspace
        if (workspace == null) {
            val messages = response.errors?.map { it.message }.orEmpty()
            throw when {
                messages.isNotEmpty() -> ApiException(messages.joinToString("\n"))
                response.exception is ApolloNetworkException -> ApiException(NETWORK_MESSAGE, response.exception)
                else -> ApiException(response.exception?.message ?: "Something went wrong loading your day.", response.exception)
            }
        }
        val me = workspace.me
        val roomInputs = workspace.rooms.map { RoomInput(it.id, it.name, it.color.toRoomColor()) }
        val dayInputs = workspace.days.map { day ->
            DayInput(
                date = day.date,
                meetings = day.meetings.map { meeting ->
                    MeetingInput(
                        id = meeting.id,
                        subject = meeting.subject,
                        startTime = meeting.startTime,
                        endTime = meeting.endTime,
                        roomId = meeting.room.id,
                        organiserId = meeting.organiser.id,
                        attendeeIds = meeting.attendees.map { it.person.id },
                        organiserName = meeting.organiser.name,
                        attendeeStatuses = meeting.attendees.associate { it.person.id to it.status.toStatus() },
                    )
                },
            )
        }
        return HomeData(
            name = me?.name,
            timeFormat = when (me?.timeFormat) {
                ApiTimeFormat.AmPm -> TimeFormat.AmPm
                else -> TimeFormat.TwentyFourHour
            },
            agenda = me?.let { person -> buildAgenda(person.id, today, dayInputs, roomInputs) },
            needsResponse = me?.let { person -> needsResponse(person.id, dayInputs, roomInputs) }.orEmpty(),
            windowEnd = windowEnd,
        )
    }

    // A status from a newer API reads as "No response", the only state that asks nothing of anyone.
    private fun ApiAttendeeStatus.toStatus(): AttendeeStatus =
        AttendeeStatus.entries.firstOrNull { it.name == rawValue } ?: AttendeeStatus.NoResponse

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        // An unknown colour from a newer API (UNKNOWN__) falls back to the by-name slot.
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }

    companion object {
        const val NETWORK_MESSAGE = "Couldn't reach Mootmaker. Check your connection and try again."
    }
}

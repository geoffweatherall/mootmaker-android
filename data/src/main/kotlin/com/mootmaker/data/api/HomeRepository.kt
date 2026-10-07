package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloNetworkException
import com.mootmaker.data.agenda.Agenda
import com.mootmaker.data.agenda.DayInput
import com.mootmaker.data.agenda.MeetingInput
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.agenda.buildAgenda
import com.mootmaker.data.graphql.HomeQuery
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import java.io.IOException
import java.time.LocalDate

/** What the home screen shows. [agenda] is null when the account has no linked Person (D.24). */
data class HomeData(
    val name: String?,
    val timeFormat: TimeFormat,
    val agenda: Agenda?,
)

/** A failed request, with the message to show. GraphQL errors are shown as the API words them. */
class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface HomeSource {
    suspend fun load(today: LocalDate): HomeData
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
    override suspend fun load(today: LocalDate): HomeData {
        val dates = listOf(today, today.plusDays(1)).map { it.toString() }
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
        return HomeData(
            name = me?.name,
            timeFormat = when (me?.timeFormat) {
                ApiTimeFormat.AmPm -> TimeFormat.AmPm
                else -> TimeFormat.TwentyFourHour
            },
            agenda = me?.let { person ->
                buildAgenda(
                    personId = person.id,
                    today = today,
                    days = workspace.days.map { day ->
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
                                )
                            },
                        )
                    },
                    rooms = workspace.rooms.map { RoomInput(it.id, it.name, it.color.toRoomColor()) },
                )
            },
        )
    }

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        // An unknown colour from a newer API (UNKNOWN__) falls back to the by-name slot.
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }

    companion object {
        const val NETWORK_MESSAGE = "Couldn't reach Mootmaker. Check your connection and try again."
    }
}

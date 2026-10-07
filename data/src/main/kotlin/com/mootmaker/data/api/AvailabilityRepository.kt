package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloNetworkException
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.availability.AvailabilityMeeting
import com.mootmaker.data.availability.AvailabilityRoom
import com.mootmaker.data.availability.RoomCard
import com.mootmaker.data.availability.buildRoomCards
import com.mootmaker.data.graphql.AvailabilityQuery
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import java.io.IOException
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
    suspend fun load(date: LocalDate): AvailabilityData
}

/** Loads one day's room availability through the `workspace` entry point: one request, one day. */
class AvailabilityRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
) : AvailabilitySource {
    override suspend fun load(date: LocalDate): AvailabilityData {
        val token = idToken()
        val response = try {
            apollo().query(AvailabilityQuery(Optional.present(listOf(date.toString()))))
                .addHttpHeader("Authorization", token)
                .execute()
        } catch (network: IOException) {
            throw ApiException(HomeRepository.NETWORK_MESSAGE, network)
        }
        val workspace = response.data?.workspace
        if (workspace == null) {
            val messages = response.errors?.map { it.message }.orEmpty()
            throw when {
                messages.isNotEmpty() -> ApiException(messages.joinToString("\n"))
                response.exception is ApolloNetworkException -> ApiException(HomeRepository.NETWORK_MESSAGE, response.exception)
                else -> ApiException(response.exception?.message ?: "Something went wrong loading room availability.", response.exception)
            }
        }
        return AvailabilityData(
            timeFormat = when (workspace.me?.timeFormat) {
                ApiTimeFormat.AmPm -> TimeFormat.AmPm
                else -> TimeFormat.TwentyFourHour
            },
            rooms = buildRoomCards(
                rooms = workspace.rooms.map { AvailabilityRoom(it.id, it.name, it.capacity, it.color.toRoomColor()) },
                meetings = workspace.days.firstOrNull { it.date == date.toString() }?.meetings.orEmpty().map {
                    AvailabilityMeeting(it.id, it.subject, it.startTime, it.endTime, it.room.id)
                },
            ),
            bounds = DateBounds(
                earliest = LocalDate.parse(workspace.boundaries.earliestRetainedDate),
                latest = LocalDate.parse(workspace.boundaries.latestBookableDate),
            ),
        )
    }

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }
}

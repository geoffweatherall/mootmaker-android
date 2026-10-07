package com.mootmaker.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.time.LocalDate

data class FakeRoom(val id: String, val name: String, val color: String? = null, val capacity: Int = 6)

data class FakePerson(val id: String, val name: String)

data class FakeMeeting(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val organiserId: String,
    val attendeeIds: List<String> = emptyList(),
    /** Response by attendee id; an attendee not listed here has not responded. */
    val responses: Map<String, String> = emptyMap(),
    /** Changes whenever an edit changes the meeting, as the API's `version` does; a response leaves it alone. */
    val version: Int = 1,
)

/**
 * Plays mootmaker's backend inside OkHttp: `mobile-config.json`, Cognito's user-pool API and the
 * GraphQL API. Nothing listens on a port, so it works the same under Robolectric and on an emulator.
 *
 * It does not check the SRP proof (it doesn't know the client's secret); the outcome of a sign-in
 * is whatever the test sets in [signInError]. SRP itself is pinned by SrpTest and by the e2e run
 * against a real Cognito pool.
 */
class FakeBackend : Interceptor {
    var demoEmail: String? = "demo@mootmaker.com"
    var demoPassword: String? = "demo-password"
    var configAvailable = true
    var networkDown = false

    /** A Cognito error type to fail the password check with, e.g. "NotAuthorizedException". */
    var signInError: Pair<String, String>? = null

    var personId: String? = "person-1"
    var personName = "Pat Example"

    /** Whether the signed-in user's ID token says `custom:class` is admin. */
    var isAdmin = false
    var timeFormat = "TwentyFourHour"
    var dateFormat = "Iso"

    /** Everyone `workspace.people` lists. The signed-in person ([personId], [personName]) is added to it. */
    var otherPeople = listOf(FakePerson("person-2", "Sam Other"))
    var rooms = listOf(FakeRoom("room-1", "Boardroom"), FakeRoom("room-2", "Atrium", "Green"))
    var meetings: List<FakeMeeting> = emptyList()

    /** The navigation window the API reports (`workspace.boundaries`). */
    var earliestRetainedDate: String = "2000-01-03"
    var latestBookableDate: String = "2100-01-01"

    /** Every request's short description ("cognito InitiateAuth", "graphql Home"...), in order. */
    val requests = mutableListOf<String>()

    val httpClient: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (networkDown) throw IOException("Network is down (fake)")
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        return when {
            request.url.encodedPath.endsWith("/mobile-config.json") -> {
                requests += "config ${request.url.host}"
                if (configAvailable) respond(chain, 200, configJson()) else respond(chain, 404, "Not found")
            }
            request.url.host.startsWith("cognito-idp.") -> cognito(chain, request.header("X-Amz-Target").orEmpty().substringAfter('.'), body)
            request.url.host == GRAPHQL_HOST -> graphql(chain, body)
            else -> respond(chain, 404, "Not found")
        }
    }

    fun configJson(): String = buildJsonObject {
        put("GRAPHQL_API_URL", "https://$GRAPHQL_HOST/graphql")
        put("COGNITO_USER_POOL_ID", "ap-southeast-2_FakePool")
        put("COGNITO_CLIENT_ID", "fake-webapp-client")
        put("COGNITO_ANDROID_CLIENT_ID", "fake-android-client")
        demoEmail?.let { put("DEMO_USER_EMAIL", it) }
        demoPassword?.let { put("DEMO_USER_PASSWORD", it) }
    }.toString()

    private fun cognito(chain: Interceptor.Chain, action: String, body: String): Response {
        requests += "cognito $action"
        val json = Json.parseToJsonElement(body).jsonObject
        return when (action) {
            "InitiateAuth" -> if (json["AuthFlow"]?.jsonPrimitive?.content == "REFRESH_TOKEN_AUTH") {
                respond(chain, 200, authResult(includeRefresh = false))
            } else {
                respond(
                    chain,
                    200,
                    """{"ChallengeName":"PASSWORD_VERIFIER","ChallengeParameters":{"SALT":"abcd","SRP_B":"2","SECRET_BLOCK":"c2VjcmV0","USER_ID_FOR_SRP":"fake-user","USERNAME":"fake-user"}}""",
                )
            }
            "RespondToAuthChallenge" -> signInError?.let { (type, message) ->
                respond(chain, 400, buildJsonObject { put("__type", type); put("message", message) }.toString())
            } ?: respond(chain, 200, authResult(includeRefresh = true))
            "RevokeToken" -> respond(chain, 200, "{}")
            else -> respond(chain, 400, """{"__type":"InvalidAction","message":"Unsupported in the fake"}""")
        }
    }

    private fun authResult(includeRefresh: Boolean): String = buildJsonObject {
        putJsonObject("AuthenticationResult") {
            put("IdToken", fakeIdToken(email = demoEmail ?: "pat@example.com", name = personName, personId = personId, admin = isAdmin))
            put("AccessToken", "fake-access-token")
            if (includeRefresh) put("RefreshToken", "fake-refresh-token")
        }
    }.toString()

    private fun graphql(chain: Interceptor.Chain, body: String): Response {
        val request = Json.parseToJsonElement(body).jsonObject
        val operation = request["operationName"]?.jsonPrimitive?.content
        requests += "graphql $operation"
        if (chain.request().header("Authorization").isNullOrBlank()) {
            return respond(chain, 401, """{"errors":[{"errorType":"UnauthorizedException","message":"You are not authorized to make this call."}]}""")
        }
        val variables = request["variables"]?.jsonObject
        when (operation) {
            "SuggestRoom" -> return respond(chain, 200, suggestRoomResponse(variables!!))
            "CreateMeeting" -> return respond(chain, 200, createMeetingResponse(variables!!["meeting"]!!.jsonObject))
            "UpdateMeeting" -> return respond(chain, 200, updateMeetingResponse(variables!!["id"]!!.jsonPrimitive.content, variables["meeting"]!!.jsonObject))
            "CancelMeeting" -> return respond(chain, 200, cancelMeetingResponse(variables!!["id"]!!.jsonPrimitive.content))
            "RespondToMeeting" -> return respond(chain, 200, respondResponse(variables!!["meetingId"]!!.jsonPrimitive.content, variables["status"]!!.jsonPrimitive.content))
        }
        val dates = (request["variables"]?.jsonObject?.get("dates") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val meetingId = request["variables"]?.jsonObject?.get("id")?.jsonPrimitive?.content
        return respond(chain, 200, homeResponse(dates, meetingId, withMeeting = operation == "MeetingDetails" || operation == "EditMeeting"))
    }

    /** Rooms that hold [capacity] people and are free for the slot, smallest first then by name, as the API ranks them. */
    private fun freeRooms(start: String, end: String, capacity: Int, excludingId: String? = null) = rooms
        .filter { it.capacity >= capacity && meetings.none { m -> m.id != excludingId && m.roomId == it.id && m.startTime < end && start < m.endTime } }
        .sortedWith(compareBy({ it.capacity }, { it.name }))

    private fun suggestRoomResponse(variables: JsonObject): String {
        val free = freeRooms(
            variables["startTime"]!!.jsonPrimitive.content,
            variables["endTime"]!!.jsonPrimitive.content,
            variables["requiredCapacity"]!!.jsonPrimitive.content.toInt(),
            (variables["excludingMeetingId"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
        )
        return buildJsonObject {
            putJsonObject("data") {
                put(
                    "suggestRoom",
                    buildJsonArray {
                        free.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name); put("capacity", it.capacity) }) }
                    },
                )
            }
        }.toString()
    }

    /** Every booking rule the input breaks. [excludingId] is the meeting being edited, whose own slot doesn't clash. */
    private fun bookingErrors(input: JsonObject, excludingId: String? = null): List<String> {
        fun text(name: String) = input[name]?.jsonPrimitive?.content.orEmpty()
        val attendeeIds = (input["attendeeIds"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val start = text("startTime")
        val end = text("endTime")
        val room = rooms.firstOrNull { it.id == text("roomId") }
        return buildList {
            if (text("subject").isBlank()) add("SubjectRequired")
            if (text("roomId").isBlank()) add("RoomRequired") else if (room == null) add("RoomNotFound")
            if (text("organiserId").isBlank()) add("OrganiserRequired")
            if (text("organiserId").isNotBlank() && text("organiserId") in attendeeIds) add("OrganiserIsAttendee")
            if (end <= start) add("EndBeforeStart")
            if (room != null) {
                if (room.capacity < attendeeIds.size + 1) add("InsufficientCapacity")
                if (meetings.any { it.id != excludingId && it.roomId == room.id && it.startTime < end && start < it.endTime }) add("TimeRangeUnavailable")
            }
        }
    }

    private fun mutationResult(field: String, meeting: FakeMeeting?, errors: List<String>): String = buildJsonObject {
        putJsonObject("data") {
            putJsonObject(field) {
                if (meeting == null) put("meeting", JsonNull) else putJsonObject("meeting") { put("id", meeting.id); put("startTime", meeting.startTime) }
                put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }.toString()

    /** Applies the API's main booking rules, reporting every one broken, and books the meeting if none is. */
    private fun createMeetingResponse(input: JsonObject): String {
        fun text(name: String) = input[name]?.jsonPrimitive?.content.orEmpty()
        val attendeeIds = (input["attendeeIds"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val errors = bookingErrors(input)
        val created = if (errors.isEmpty()) {
            FakeMeeting("new-${meetings.size + 1}", text("subject"), text("startTime"), text("endTime"), text("roomId"), text("organiserId"), attendeeIds)
                .also { meetings = meetings + it }
        } else {
            null
        }
        return mutationResult("createMeeting", created, errors)
    }

    /**
     * Edits a meeting as the API does: a stale `expectedVersion` is
     * refused with MeetingChanged, and the version moves on once anything changes. Attendees keep their responses.
     */
    private fun updateMeetingResponse(id: String, input: JsonObject): String {
        fun text(name: String) = input[name]?.jsonPrimitive?.content.orEmpty()
        val existing = meetings.firstOrNull { it.id == id } ?: return mutationResult("updateMeeting", null, listOf("MeetingNotFound"))
        val expected = (input["expectedVersion"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        if (expected != null && expected != existing.version.toString()) return mutationResult("updateMeeting", null, listOf("MeetingChanged"))
        val errors = bookingErrors(input, excludingId = id)
        if (errors.isNotEmpty()) return mutationResult("updateMeeting", null, errors)
        val attendeeIds = (input["attendeeIds"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val updated = existing.copy(
            subject = text("subject"),
            startTime = text("startTime"),
            endTime = text("endTime"),
            roomId = text("roomId"),
            organiserId = text("organiserId"),
            attendeeIds = attendeeIds,
            responses = existing.responses.filterKeys { it in attendeeIds },
            version = existing.version + 1,
        )
        meetings = meetings.map { if (it.id == id) updated else it }
        return mutationResult("updateMeeting", updated, emptyList())
    }

    private fun cancelMeetingResponse(id: String): String {
        val found = meetings.any { it.id == id }
        if (found) meetings = meetings.filter { it.id != id }
        return buildJsonObject {
            putJsonObject("data") {
                putJsonObject("cancelMeeting") {
                    put("errors", buildJsonArray { if (!found) add(JsonPrimitive("MeetingNotFound")) })
                }
            }
        }.toString()
    }

    /** The caller's own answer; the organiser and anyone not invited have none to give. */
    private fun respondResponse(meetingId: String, status: String): String {
        val meeting = meetings.firstOrNull { it.id == meetingId }
        val me = personId
        val error = when {
            me == null -> "NoLinkedPerson"
            meeting == null -> "MeetingNotFound"
            me !in meeting.attendeeIds -> "NotAnAttendee"
            else -> null
        }
        if (error == null && meeting != null && me != null) {
            meetings = meetings.map { if (it.id == meetingId) it.copy(responses = it.responses + (me to status)) else it }
        }
        return buildJsonObject {
            putJsonObject("data") {
                putJsonObject("respondToMeeting") {
                    if (error != null) put("meeting", JsonNull) else putJsonObject("meeting") { put("id", meetingId) }
                    put("errors", buildJsonArray { error?.let { add(JsonPrimitive(it)) } })
                }
            }
        }.toString()
    }

    private val people: List<FakePerson>
        get() = listOfNotNull(personId?.let { FakePerson(it, personName) }) + otherPeople

    /** One answer shaped for every operation: Apollo reads only the fields each one selected. */
    private fun homeResponse(dates: List<String>, meetingId: String?, withMeeting: Boolean): String = buildJsonObject {
        putJsonObject("data") {
            if (withMeeting) {
                val meeting = meetings.firstOrNull { it.id == meetingId }
                put("meeting", meeting?.toJson() ?: JsonNull)
            }
            putJsonObject("workspace") {
                val id = personId
                if (id == null) {
                    put("me", JsonNull)
                } else {
                    putJsonObject("me") {
                        put("id", id)
                        put("name", personName)
                        put("timeFormat", timeFormat)
                        put("dateFormat", dateFormat)
                    }
                }
                put("people", buildJsonArray { people.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) } })
                put(
                    "rooms",
                    buildJsonArray {
                        rooms.forEach { room ->
                            add(
                                buildJsonObject {
                                    put("id", room.id)
                                    put("name", room.name)
                                    put("color", room.color)
                                    put("capacity", room.capacity)
                                },
                            )
                        }
                    },
                )
                put(
                    "days",
                    buildJsonArray {
                        dates.forEach { date ->
                            add(
                                buildJsonObject {
                                    put("date", date)
                                    put("meetings", buildJsonArray { meetings.filter { it.startTime.startsWith(date) }.forEach { add(it.toJson()) } })
                                },
                            )
                        }
                    },
                )
                putJsonObject("boundaries") {
                    put("earliestRetainedDate", earliestRetainedDate)
                    put("latestBookableDate", latestBookableDate)
                }
            }
        }
    }.toString()

    private fun FakeMeeting.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("subject", subject)
        put("startTime", startTime)
        put("endTime", endTime)
        put("version", version.toString())
        putJsonObject("room") {
            put("id", roomId)
            put("name", rooms.firstOrNull { it.id == roomId }?.name ?: "Unknown room")
        }
        putJsonObject("organiser") { put("id", organiserId); put("name", nameOf(organiserId)) }
        put(
            "attendees",
            buildJsonArray {
                attendeeIds.forEach { id ->
                    add(
                        buildJsonObject {
                            putJsonObject("person") { put("id", id); put("name", nameOf(id)) }
                            put("status", responses[id] ?: "NoResponse")
                        },
                    )
                }
            },
        )
    }

    private fun nameOf(id: String) = people.firstOrNull { it.id == id }?.name ?: "Person $id"

    private fun respond(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    companion object {
        const val GRAPHQL_HOST = "api.fake.mootmaker.test"

        /** A meeting on [date] at [hour]:00 for an hour, organised by the default person. */
        fun meeting(id: String, subject: String, date: LocalDate, hour: Int, roomId: String = "room-1", organiserId: String = "person-1") =
            FakeMeeting(
                id = id,
                subject = subject,
                startTime = "${date}T%02d:00:00".format(hour),
                endTime = "${date}T%02d:00:00".format(hour + 1),
                roomId = roomId,
                organiserId = organiserId,
            )
    }
}

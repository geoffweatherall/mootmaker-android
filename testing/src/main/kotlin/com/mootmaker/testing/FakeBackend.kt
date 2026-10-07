package com.mootmaker.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

data class FakeMeeting(
    val id: String,
    val subject: String,
    val startTime: String,
    val endTime: String,
    val roomId: String,
    val organiserId: String,
    val attendeeIds: List<String> = emptyList(),
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
    var timeFormat = "TwentyFourHour"
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
            put("IdToken", fakeIdToken(email = demoEmail ?: "pat@example.com", name = personName, personId = personId))
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
        val dates = (request["variables"]?.jsonObject?.get("dates") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        return respond(chain, 200, homeResponse(dates))
    }

    private fun homeResponse(dates: List<String>): String = buildJsonObject {
        putJsonObject("data") {
            putJsonObject("workspace") {
                val id = personId
                if (id == null) {
                    put("me", JsonNull)
                } else {
                    putJsonObject("me") {
                        put("id", id)
                        put("name", personName)
                        put("timeFormat", timeFormat)
                    }
                }
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
        putJsonObject("room") { put("id", roomId) }
        putJsonObject("organiser") { put("id", organiserId) }
        put(
            "attendees",
            buildJsonArray { attendeeIds.forEach { add(buildJsonObject { putJsonObject("person") { put("id", it) } }) } },
        )
    }

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

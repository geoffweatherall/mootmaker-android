package com.mootmaker.app.acceptance

import android.util.Base64
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mootmaker.app.AppContainer
import com.mootmaker.app.MainActivity
import com.mootmaker.app.MootmakerApplication
import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.auth.CognitoClient
import com.mootmaker.data.config.ConfigRepository
import com.mootmaker.data.config.Environment
import com.mootmaker.data.config.MobileConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Acceptance tests run against a real ephemeral environment, created for the run by
 * `.github/workflows/acceptance.yml`. They are excluded from pr-checks' emulator job.
 *
 * The workflow passes the environment's name and the API's test fixture users (admin, standard
 * and no-person; see mootmaker-api's README, "Test fixture users") as instrumentation arguments,
 * base64-encoded so that no password character can upset the shell that starts Gradle. Database
 * reset creates those users through the real sign-up path, so their Persons are real.
 */
object Acceptance {
    data class Account(val email: String, val password: String)

    private val args get() = InstrumentationRegistry.getArguments()

    private fun arg(name: String): String {
        val encoded = args.getString(name)
        require(!encoded.isNullOrBlank()) { "Instrumentation argument $name is missing: see acceptance.yml" }
        return String(Base64.decode(encoded, Base64.DEFAULT))
    }

    val environment: Environment by lazy {
        requireNotNull(Environment.fromInput(arg("accEnvironment"))) { "Invalid environment name" }
    }

    val admin by lazy { Account(arg("accAdminEmail"), arg("accAdminPassword")) }
    val standard by lazy { Account(arg("accStandardEmail"), arg("accStandardPassword")) }
    val noPerson by lazy { Account(arg("accNoPersonEmail"), arg("accNoPersonPassword")) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    }

    /** The environment's real mobile-config.json. */
    val config: MobileConfig by lazy {
        runBlocking { ConfigRepository(http, InMemoryKeyValueStore()).fetch(environment) }
    }

    /**
     * Starts the app pointed at the environment the way a person would get there: the
     * environment choice is stored, and the app fetches that environment's mobile-config.json
     * itself. Storage starts empty, so the app starts signed out.
     */
    fun launchApp(): ActivityScenario<MainActivity> {
        val application = ApplicationProvider.getApplicationContext<MootmakerApplication>()
        val container = AppContainer(application, store = InMemoryKeyValueStore())
        runBlocking { container.configRepository.setEnvironment(environment) }
        application.replaceContainer(container)
        return ActivityScenario.launch(MainActivity::class.java)
    }
}

/** Sets up data through the real GraphQL API, as a real signed-in user. */
class Api(private val account: Acceptance.Account) {
    private val config = Acceptance.config
    private val idToken: String by lazy {
        runBlocking {
            CognitoClient(Acceptance.http, config.userPoolId, config.androidClientId)
                .signIn(account.email, account.password)
                .idToken
        }
    }

    fun query(document: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("query", document)
            put("variables", variables)
        }.toString()
        val request = Request.Builder()
            .url(config.graphqlApiUrl)
            .header("Authorization", idToken)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val response = Acceptance.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GraphQL returned HTTP ${response.code}" }
            Json.parseToJsonElement(response.body!!.string()).jsonObject
        }
        val errors = response["errors"] as? JsonArray
        check(errors.isNullOrEmpty()) { "GraphQL errors: $errors" }
        return response["data"]!!.jsonObject
    }

    fun myPersonId(): String =
        query("query { workspace { me { id } } }")["workspace"]!!.jsonObject["me"]!!.jsonObject.string("id")

    fun myName(): String =
        query("query { workspace { me { name } } }")["workspace"]!!.jsonObject["me"]!!.jsonObject.string("name")

    /** A guest Person (admin only): someone to organise or attend meetings without signing in. */
    fun createPerson(name: String): String {
        val result = query(
            "mutation(\$name: String!) { createPerson(name: \$name) { person { id } errors } }",
            buildJsonObject { put("name", name) },
        )["createPerson"]!!.jsonObject
        checkNoErrors(result)
        return result["person"]!!.jsonObject.string("id")
    }

    fun createRoom(name: String, capacity: Int = 6): String {
        val result = query(
            "mutation(\$room: RoomInput!) { createRoom(room: \$room) { room { id } errors } }",
            buildJsonObject { put("room", buildJsonObject { put("name", name); put("capacity", capacity) }) },
        )["createRoom"]!!.jsonObject
        checkNoErrors(result)
        return result["room"]!!.jsonObject.string("id")
    }

    fun createMeeting(
        roomId: String,
        organiserId: String,
        subject: String,
        start: String,
        end: String,
        attendeeIds: List<String> = emptyList(),
    ): String {
        val meeting = buildJsonObject {
            put("roomId", roomId)
            put("organiserId", organiserId)
            put("attendeeIds", JsonArray(attendeeIds.map { JsonPrimitive(it) }))
            put("subject", subject)
            put("startTime", start)
            put("endTime", end)
        }
        val result = query(
            "mutation(\$meeting: MeetingInput!) { createMeeting(meeting: \$meeting) { meeting { id } errors } }",
            buildJsonObject { put("meeting", meeting) },
        )["createMeeting"]!!.jsonObject
        checkNoErrors(result)
        return result["meeting"]!!.jsonObject.string("id")
    }

    fun updateMeeting(
        id: String,
        roomId: String,
        organiserId: String,
        subject: String,
        start: String,
        end: String,
        attendeeIds: List<String> = emptyList(),
    ) {
        val meeting = buildJsonObject {
            put("roomId", roomId)
            put("organiserId", organiserId)
            put("attendeeIds", JsonArray(attendeeIds.map { JsonPrimitive(it) }))
            put("subject", subject)
            put("startTime", start)
            put("endTime", end)
        }
        val result = query(
            "mutation(\$id: ID!, \$meeting: MeetingInput!) { updateMeeting(id: \$id, meeting: \$meeting) { meeting { id } errors } }",
            buildJsonObject { put("id", id); put("meeting", meeting) },
        )["updateMeeting"]!!.jsonObject
        checkNoErrors(result)
    }

    fun cancelMeeting(id: String) {
        val result = query(
            "mutation(\$id: ID!) { cancelMeeting(id: \$id) { errors } }",
            buildJsonObject { put("id", id) },
        )["cancelMeeting"]!!.jsonObject
        checkNoErrors(result)
    }

    /** The caller's own response to a meeting they attend. */
    fun respond(id: String, status: String) {
        val result = query(
            "mutation(\$id: ID!, \$status: AttendeeStatus!) { respondToMeeting(meetingId: \$id, status: \$status) { errors } }",
            buildJsonObject { put("id", id); put("status", status) },
        )["respondToMeeting"]!!.jsonObject
        checkNoErrors(result)
    }

    /** The meeting as the API holds it, or null once it no longer exists. */
    fun meeting(id: String): JsonObject? = query(
        "query(\$id: ID!) { meeting(id: \$id) { subject startTime endTime attendees { person { id } status } } }",
        buildJsonObject { put("id", id) },
    )["meeting"] as? JsonObject

    fun subjectOf(id: String): String? = meeting(id)?.string("subject")

    /** [personId]'s response to the meeting, as the API holds it. */
    fun responseOf(id: String, personId: String): String? =
        meeting(id)?.get("attendees")?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["person"]!!.jsonObject.string("id") == personId }?.string("status")

    private fun checkNoErrors(result: JsonObject) {
        val errors = result["errors"]!!.jsonArray
        check(errors.isEmpty()) { "Mutation rejected: $errors" }
    }

    private fun JsonObject.string(name: String) = (this[name] as JsonElement).jsonPrimitive.content
}

private const val TIMEOUT_MS = 30_000L

fun ComposeTestRule.shown(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.onAllNodesWithTextFirst(text: String): SemanticsNodeInteraction = onAllNodes(hasText(text)).onFirst()

fun ComposeTestRule.waitForText(text: String) = waitUntil(TIMEOUT_MS) { shown(text) }

/**
 * Scrolls the home screen's list until a node matching [matcher] is composed. Home is a lazy list:
 * rows below the fold (after every invitation in "Needs your response") are not composed at all.
 */
fun ComposeTestRule.scrollHomeTo(matcher: SemanticsMatcher) {
    waitForText("Needs your response")
    onNode(hasScrollAction()).performScrollToNode(matcher)
}

fun ComposeTestRule.field(label: String): SemanticsNodeInteraction = onNode(hasText(label) and hasSetTextAction())

/** Signs in through the real sign-in form, replacing the pre-filled demo credentials. */
fun ComposeTestRule.signIn(account: Acceptance.Account) {
    waitUntil(TIMEOUT_MS) {
        onAllNodes(hasText("Sign in") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    field("Email").performTextReplacement(account.email)
    field("Password").performTextReplacement(account.password)
    onNode(hasText("Sign in") and hasClickAction()).performClick()
}

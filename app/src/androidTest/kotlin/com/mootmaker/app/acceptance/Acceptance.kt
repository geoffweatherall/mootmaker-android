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

    /** The caller's own name, set directly. Settings tests use it to put a shared fixture user back as they found it. */
    fun updateMyName(name: String) {
        val result = query(
            "mutation(\$name: String!) { updateMyName(name: \$name) { errors } }",
            buildJsonObject { put("name", name) },
        )["updateMyName"]!!.jsonObject
        checkNoErrors(result)
    }

    /** The caller's date format, time format and week start, as `Iso/TwentyFourHour/Monday`. */
    fun preferences(): String {
        val me = query("query { workspace { me { dateFormat timeFormat weekStart } } }")["workspace"]!!.jsonObject["me"]!!.jsonObject
        return listOf("dateFormat", "timeFormat", "weekStart").joinToString("/") { me.string(it) }
    }

    fun setPreferences(dateFormat: String, timeFormat: String, weekStart: String) {
        val preferences = buildJsonObject { put("dateFormat", dateFormat); put("timeFormat", timeFormat); put("weekStart", weekStart) }
        val result = query(
            "mutation(\$preferences: PreferencesInput!) { updateMyPreferences(preferences: \$preferences) { errors } }",
            buildJsonObject { put("preferences", preferences) },
        )["updateMyPreferences"]!!.jsonObject
        checkNoErrors(result)
    }

    /** The caller's own avatar URL, or null for none. */
    fun avatarUrl(): String? =
        (query("query { workspace { me { avatarUrl } } }")["workspace"]!!.jsonObject["me"]!!.jsonObject["avatarUrl"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Sets the caller's own avatar through the API's two-step upload, the way the app does. */
    fun setAvatar(personId: String, png: ByteArray) {
        val requested = query(
            "mutation(\$p: ID!, \$t: String!, \$n: Int!) { requestAvatarUpload(personId: \$p, contentType: \$t, contentLength: \$n) { upload { uploadId url } errors } }",
            buildJsonObject { put("p", personId); put("t", "image/png"); put("n", png.size) },
        )["requestAvatarUpload"]!!.jsonObject
        checkNoErrors(requested)
        val upload = requested["upload"]!!.jsonObject
        val put = Request.Builder().url(upload.string("url")).put(png.toRequestBody("image/png".toMediaType())).build()
        Acceptance.http.newCall(put).execute().use { check(it.isSuccessful) { "Avatar PUT returned HTTP ${it.code}" } }
        val confirmed = query(
            "mutation(\$p: ID!, \$u: ID!) { confirmAvatarUpload(personId: \$p, uploadId: \$u) { errors } }",
            buildJsonObject { put("p", personId); put("u", upload.string("uploadId")) },
        )["confirmAvatarUpload"]!!.jsonObject
        checkNoErrors(confirmed)
    }

    fun removeAvatar(personId: String) {
        val result = query(
            "mutation(\$p: ID!) { removeAvatar(personId: \$p) { errors } }",
            buildJsonObject { put("p", personId) },
        )["removeAvatar"]!!.jsonObject
        checkNoErrors(result)
    }

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

    /** The subjects of every meeting on [date] (`2026-10-07`), as the caller sees them. */
    fun subjectsOn(date: String): List<String> = query(
        "query(\$d: [String!]) { workspace(dates: \$d) { days { meetings { subject } } } }",
        buildJsonObject { put("d", JsonArray(listOf(JsonPrimitive(date)))) },
    )["workspace"]!!.jsonObject["days"]!!.jsonArray.flatMap { day ->
        day.jsonObject["meetings"]!!.jsonArray.map { it.jsonObject.string("subject") }
    }

    fun roomNames(): List<String> =
        query("query { workspace { rooms { name } } }")["workspace"]!!.jsonObject["rooms"]!!.jsonArray.map { it.jsonObject.string("name") }

    fun roomIdNamed(name: String): String =
        query("query { workspace { rooms { id name } } }")["workspace"]!!.jsonObject["rooms"]!!.jsonArray
            .map { it.jsonObject }.first { it.string("name") == name }.string("id")

    fun personNames(): List<String> =
        query("query { workspace { people { name } } }")["workspace"]!!.jsonObject["people"]!!.jsonArray.map { it.jsonObject.string("name") }

    /** The room name the meeting called [subject] on [date] shows, as every reader sees it after a room edit. */
    fun subjectsRoomOn(date: String, subject: String): String? = query(
        "query(\$d: [String!]) { workspace(dates: \$d) { days { meetings { subject room { name } } } } }",
        buildJsonObject { put("d", JsonArray(listOf(JsonPrimitive(date)))) },
    )["workspace"]!!.jsonObject["days"]!!.jsonArray.flatMap { it.jsonObject["meetings"]!!.jsonArray }
        .map { it.jsonObject }.firstOrNull { it.string("subject") == subject }?.get("room")?.jsonObject?.string("name")

    /** Deletes the caller's own account. Tests that create an account use it to leave nothing behind. */
    fun deleteMyAccount() {
        check(query("mutation { deleteMyAccount }")["deleteMyAccount"]!!.jsonPrimitive.content == "true") { "deleteMyAccount returned false" }
    }

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

/** For text that is part of a longer line, such as a time range inside an agenda row. */
fun ComposeTestRule.waitForTextContaining(text: String) =
    waitUntil(TIMEOUT_MS) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

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

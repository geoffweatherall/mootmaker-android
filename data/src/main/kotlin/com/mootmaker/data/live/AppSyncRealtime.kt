package com.mootmaker.data.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A minimal client for AppSync's realtime protocol, the Kotlin twin of the webapp's
 * `appsyncSocket.ts`.
 *
 * Hand-rolled because **AppSync does not speak `graphql-transport-ws`**, which rules out Apollo's
 * subscription transports. Its protocol is negotiated as `graphql-ws` but is AWS's own: auth in a
 * base64 query parameter, then `connection_init` → `connection_ack` → `start` → `start_ack` →
 * `data`. It is not routed through Apollo for the same reason as on the web: the only thing it
 * carries is a list of dates, and the only response is to refetch.
 *
 * [events] ends only when its collector is cancelled. A dropped socket is the normal case, since
 * phones suspend sockets, networks change and AppSync closes idle ones, so it reconnects with
 * exponential backoff and a fresh token each time.
 */
class AppSyncRealtime(
    http: OkHttpClient,
    private val httpEndpoint: suspend () -> String,
    private val idToken: suspend () -> String,
    private val realtimeUrl: (httpEndpoint: String, header: String) -> String = ::realtimeUrlFor,
    private val backoff: Backoff = Backoff(),
) : LiveUpdates {
    /** Milliseconds. */
    data class Backoff(val baseMs: Long = 1_000, val maxMs: Long = 30_000)

    // AppSync sends a keep-alive (`ka`) about once a minute and closes a socket that stays quiet for
    // connectionTimeoutMs (300 000), so a read timeout of that length is also the dead-socket watchdog.
    private val http = http.newBuilder().readTimeout(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    override fun events(): Flow<LiveEvent> = flow {
        var delayMs = backoff.baseMs
        while (true) {
            try {
                val endpoint = httpEndpoint()
                val token = idToken()
                connection(endpoint, token).collect { event ->
                    if (event == LiveEvent.Subscribed) delayMs = backoff.baseMs
                    emit(event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Signed out, no network, socket refused: all just "try again shortly".
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(backoff.maxMs)
        }
    }

    /** One socket's life. Completes when the socket closes and fails when it errors. */
    private fun connection(endpoint: String, token: String): Flow<LiveEvent> = callbackFlow {
        val authorization = buildJsonObject {
            put("host", URI(endpoint).host)
            put("Authorization", token)
        }
        val header = base64(authorization.toString())
        val request = Request.Builder()
            .url(realtimeUrl(endpoint, header))
            .header("Sec-WebSocket-Protocol", "graphql-ws")
            .build()

        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"connection_init"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (message["type"]?.jsonPrimitive?.content) {
                    "connection_ack" -> webSocket.send(startMessage(authorization))
                    "start_ack" -> trySend(LiveEvent.Subscribed)
                    "data" -> datesOf(message)?.let { trySend(LiveEvent.DaysChanged(it)) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })
        awaitClose { socket.cancel() }
    }

    private fun startMessage(authorization: JsonObject): String = buildJsonObject {
        put("id", UUID.randomUUID().toString())
        put("type", "start")
        put(
            "payload",
            buildJsonObject {
                // The query travels as a JSON STRING nested inside payload.data, not as an object.
                put("data", buildJsonObject { put("query", SUBSCRIPTION); put("variables", JsonObject(emptyMap())) }.toString())
                put("extensions", buildJsonObject { put("authorization", authorization) })
            },
        )
    }.toString()

    private fun datesOf(message: JsonObject): List<String>? = runCatching {
        message["payload"]!!.jsonObject["data"]!!.jsonObject["daysInvalidated"]!!.jsonObject["dates"]!!.jsonArray
            .map { it.jsonPrimitive.content }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SUBSCRIPTION = "subscription DaysInvalidated { daysInvalidated { dates } }"
        const val CONNECTION_TIMEOUT_MS = 300_000L
    }
}

/**
 * Derives the realtime URL from the HTTP one. The path differs by host, and the wrong one does not
 * fail gracefully, the socket just never connects: a custom domain serves realtime at
 * `/graphql/realtime`, the raw AppSync host at `/graphql` on the `appsync-realtime-api` host.
 */
fun realtimeUrlFor(httpEndpoint: String, header: String): String {
    val url = URI(httpEndpoint)
    val rawAppSyncHost = url.host.endsWith(".amazonaws.com")
    val host = if (rawAppSyncHost) url.host.replace("appsync-api", "appsync-realtime-api") else url.host
    val path = if (rawAppSyncHost) url.path else "${url.path}/realtime"
    return "wss://$host$path?header=$header&payload=${base64("{}")}"
}

private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())

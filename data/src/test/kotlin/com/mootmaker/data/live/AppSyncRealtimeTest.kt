package com.mootmaker.data.live

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AppSyncRealtimeTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    /** Plays AppSync: acks the connection and the subscription, then pushes [pushes] as `data`. */
    private class FakeAppSync(private val pushes: List<String>, private val closeAfterPush: Boolean = false) : WebSocketListener() {
        val received = mutableListOf<String>()

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            val type = Json.parseToJsonElement(text).jsonObject["type"]!!.jsonPrimitive.content
            when (type) {
                "connection_init" -> webSocket.send("""{"type":"connection_ack","payload":{"connectionTimeoutMs":300000}}""")
                "start" -> {
                    webSocket.send("""{"type":"start_ack"}""")
                    pushes.forEach { webSocket.send("""{"type":"data","payload":{"data":{"daysInvalidated":{"dates":$it}}}}""") }
                    if (closeAfterPush) webSocket.close(1000, "bye")
                }
            }
        }
    }

    private fun realtime(): AppSyncRealtime {
        server.start()
        val base = server.url("/graphql").toString()
        return AppSyncRealtime(
            http = OkHttpClient(),
            httpEndpoint = { base },
            idToken = { "id-token" },
            realtimeUrl = { endpoint, header -> "$endpoint/realtime?header=$header" },
            backoff = AppSyncRealtime.Backoff(baseMs = 10, maxMs = 20),
        )
    }

    @Test
    fun subscribesAndReportsEachBroadcast() = runBlocking {
        val appSync = FakeAppSync(pushes = listOf("""["2026-10-08"]""", """["2026-10-09","2026-10-10"]"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(appSync))

        val events = withTimeout(10_000) { realtime().events().take(3).toList() }

        assertEquals(
            listOf(
                LiveEvent.Subscribed,
                LiveEvent.DaysChanged(listOf("2026-10-08")),
                LiveEvent.DaysChanged(listOf("2026-10-09", "2026-10-10")),
            ),
            events,
        )
        val request = server.takeRequest()
        assertEquals("graphql-ws", request.getHeader("Sec-WebSocket-Protocol"))
        val header = request.requestUrl!!.queryParameter("header")!!
        val authorization = Json.parseToJsonElement(String(Base64.getDecoder().decode(header))).jsonObject
        assertEquals("id-token", authorization["Authorization"]!!.jsonPrimitive.content)
        assertEquals(server.hostName, authorization["host"]!!.jsonPrimitive.content)
        val start = Json.parseToJsonElement(appSync.received.last()).jsonObject["payload"]!!.jsonObject
        assertTrue(start["data"]!!.jsonPrimitive.content.contains("daysInvalidated"))
        assertEquals("id-token", start["extensions"]!!.jsonObject["authorization"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
    }

    @Test
    fun reconnectsAfterTheSocketDropsAndSaysSoWithAFreshSubscribed() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeAppSync(emptyList(), closeAfterPush = true)))
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeAppSync(listOf("""["2026-10-08"]"""))))

        val events = withTimeout(10_000) { realtime().events().take(3).toList() }

        assertEquals(listOf(LiveEvent.Subscribed, LiveEvent.Subscribed, LiveEvent.DaysChanged(listOf("2026-10-08"))), events)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun keepsTryingWhenTheServerRefusesTheSocket() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeAppSync(emptyList())))

        val events = withTimeout(10_000) { realtime().events().take(1).toList() }

        assertEquals(listOf(LiveEvent.Subscribed), events)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun derivesTheRealtimeUrlFromTheHttpOne() {
        val raw = realtimeUrlFor("https://abc123.appsync-api.ap-southeast-2.amazonaws.com/graphql", "H")
        assertEquals("wss://abc123.appsync-realtime-api.ap-southeast-2.amazonaws.com/graphql?header=H&payload=e30=", raw)
        val custom = realtimeUrlFor("https://api.example.com/graphql", "H")
        assertEquals("wss://api.example.com/graphql/realtime?header=H&payload=e30=", custom)
    }
}

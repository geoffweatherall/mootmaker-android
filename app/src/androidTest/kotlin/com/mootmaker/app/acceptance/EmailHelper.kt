package com.mootmaker.app.acceptance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

/**
 * Real emailed codes for sign-up and reset tests, from the host-side helper in `email-helper/`
 * (design Decision 6: codes come only through mootmaker-email-testing, on the host). The workflow
 * starts it and runs `adb reverse tcp:8787 tcp:8787`, so it answers on the device's own localhost.
 *
 * A plain socket rather than OkHttp: the app's network security policy, which this test process
 * shares, refuses cleartext HTTP, and there is no reason to loosen the shipped app's policy for a
 * test. A socket isn't subject to it.
 */
object EmailHelper {
    data class Identity(val name: String, val email: String, val password: String)

    private const val PORT = 8787

    /** A fresh, never-used name, email and password that meets the pool's policy. */
    fun freshAccount(): Identity {
        val (status, body) = get("/account")
        check(status == 200) { "Email helper /account returned $status: $body" }
        return Identity(body.string("name"), body.string("email"), body.string("password"))
    }

    /** The next code emailed to [email], waiting up to two minutes for it to arrive. */
    fun waitForCode(email: String): String {
        val deadline = System.currentTimeMillis() + 130_000
        while (System.currentTimeMillis() < deadline) {
            val (status, body) = get("/code?email=" + URLEncoder.encode(email, "UTF-8"))
            when (status) {
                200 -> return body.string("code")
                202 -> Thread.sleep(2_000)
                else -> error("Email helper /code returned $status: $body")
            }
        }
        error("No code arrived for $email")
    }

    private fun get(path: String): Pair<Int, JsonObject> = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", PORT), 5_000)
        socket.soTimeout = 15_000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
        val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        val status = response.substringAfter(' ').substringBefore(' ').toInt()
        status to Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
}

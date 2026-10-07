package com.mootmaker.testing

import java.time.Instant
import java.util.Base64

/** An unsigned JWT carrying the claims the app reads. Good enough: the app never verifies them. */
fun fakeIdToken(
    email: String = "pat@example.com",
    name: String? = "Pat Example",
    personId: String? = "person-1",
    expiresAt: Instant = Instant.parse("2030-01-01T00:00:00Z"),
): String {
    val claims = buildList {
        add("\"email\":\"$email\"")
        name?.let { add("\"name\":\"$it\"") }
        personId?.let { add("\"custom:personId\":\"$it\"") }
        add("\"exp\":${expiresAt.epochSecond}")
    }.joinToString(",", "{", "}")
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return listOf("{\"alg\":\"none\"}", claims, "sig").joinToString(".") { encoder.encodeToString(it.toByteArray()) }
}

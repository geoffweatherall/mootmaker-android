package com.mootmaker.data.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.Base64

/**
 * What the app reads from the Cognito id token. Not verified here: the token came straight from
 * Cognito over TLS, and AppSync verifies it on every request. Presentation only, like the webapp's
 * currentUserClaims().
 */
data class IdTokenClaims(
    val email: String?,
    val name: String?,
    /** The linked Person's id, or null for an account with none (use case D.24). */
    val personId: String?,
    val expiresAt: Instant,
    /** The ID token's `custom:class` is "admin". Presentation only: the API enforces what an admin may do. */
    val isAdmin: Boolean = false,
) {
    companion object {
        fun parse(idToken: String): IdTokenClaims {
            val parts = idToken.split('.')
            require(parts.size == 3) { "Not a JWT" }
            val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
            return IdTokenClaims(
                email = payload.string("email"),
                name = payload.string("name"),
                personId = payload.string("custom:personId")?.takeIf { it.isNotBlank() },
                expiresAt = Instant.ofEpochSecond((payload["exp"] as? JsonPrimitive)?.longOrNull ?: 0),
                isAdmin = payload.string("custom:class") == "admin",
            )
        }

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    }
}

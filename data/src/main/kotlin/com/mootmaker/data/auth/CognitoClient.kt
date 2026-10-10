package com.mootmaker.data.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigInteger
import java.time.Clock

/** A Cognito API error, e.g. type "NotAuthorizedException" with "Incorrect username or password.". */
class CognitoException(val type: String, override val message: String) : Exception(message)

/** The three tokens Cognito issues on sign-in. A refresh returns no new refresh token. */
data class Tokens(val idToken: String, val accessToken: String, val refreshToken: String)

/**
 * A thin client for Cognito's user-pool API (JSON over HTTPS), per the design's Q2 option B.
 * Sign-in uses USER_SRP_AUTH, the same flow as the webapp, so the Android app client needs only
 * ALLOW_USER_SRP_AUTH and ALLOW_REFRESH_TOKEN_AUTH, like the webapp's.
 *
 * [endpoint] defaults to the pool's regional endpoint; tests point it at a local server.
 */
class CognitoClient(
    private val http: OkHttpClient,
    private val userPoolId: String,
    private val clientId: String,
    private val endpoint: String = "https://cognito-idp.${userPoolId.substringBefore('_')}.amazonaws.com/",
    private val clock: Clock = Clock.systemUTC(),
    private val newSrp: () -> Srp = { Srp() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val poolName = userPoolId.substringAfter('_')

    suspend fun signIn(email: String, password: String): Tokens = withContext(io) {
        val srp = newSrp()
        val initiated = call(
            "InitiateAuth",
            buildJsonObject {
                put("AuthFlow", "USER_SRP_AUTH")
                put("ClientId", clientId)
                putJsonObject("AuthParameters") {
                    put("USERNAME", email)
                    put("SRP_A", srp.largeA.toString(16))
                }
            },
        )
        val challengeName = initiated.string("ChallengeName")
        if (challengeName != "PASSWORD_VERIFIER") {
            throw CognitoException("UnexpectedChallenge", "Sign-in needs a step this app does not support yet ($challengeName).")
        }
        val params = initiated["ChallengeParameters"]!!.jsonObject
        val userIdForSrp = params.string("USER_ID_FOR_SRP")!!
        val secretBlock = params.string("SECRET_BLOCK")!!
        val key = srp.passwordAuthenticationKey(
            poolName = poolName,
            userIdForSrp = userIdForSrp,
            password = password,
            serverB = BigInteger(params.string("SRP_B")!!, 16),
            salt = BigInteger(params.string("SALT")!!, 16),
        )
        val timestamp = Srp.timestamp(clock.instant())
        val responded = call(
            "RespondToAuthChallenge",
            buildJsonObject {
                put("ChallengeName", "PASSWORD_VERIFIER")
                put("ClientId", clientId)
                putJsonObject("ChallengeResponses") {
                    put("USERNAME", userIdForSrp)
                    put("PASSWORD_CLAIM_SECRET_BLOCK", secretBlock)
                    put("TIMESTAMP", timestamp)
                    put("PASSWORD_CLAIM_SIGNATURE", Srp.signature(key, poolName, userIdForSrp, secretBlock, timestamp))
                }
            },
        )
        when (val next = responded.string("ChallengeName")) {
            null -> Unit
            "NEW_PASSWORD_REQUIRED" ->
                // Same wording as the webapp, which also doesn't handle this challenge.
                throw CognitoException(next, "A password change is required for this account.")
            else -> throw CognitoException("UnexpectedChallenge", "Sign-in needs a step this app does not support yet ($next).")
        }
        val result = responded["AuthenticationResult"]!!.jsonObject
        Tokens(
            idToken = result.string("IdToken")!!,
            accessToken = result.string("AccessToken")!!,
            refreshToken = result.string("RefreshToken")!!,
        )
    }

    /** New id and access tokens from a refresh token. Cognito keeps the refresh token unchanged. */
    suspend fun refresh(refreshToken: String): Tokens = withContext(io) {
        val response = call(
            "InitiateAuth",
            buildJsonObject {
                put("AuthFlow", "REFRESH_TOKEN_AUTH")
                put("ClientId", clientId)
                putJsonObject("AuthParameters") { put("REFRESH_TOKEN", refreshToken) }
            },
        )
        val result = response["AuthenticationResult"]!!.jsonObject
        Tokens(
            idToken = result.string("IdToken")!!,
            accessToken = result.string("AccessToken")!!,
            refreshToken = result.string("RefreshToken") ?: refreshToken,
        )
    }

    /**
     * Registers an unconfirmed user; Cognito emails them a verification code. The name is a user
     * attribute that the pool's PostConfirmation trigger turns into a linked Person (use case A.5).
     */
    suspend fun signUp(email: String, password: String, name: String) = withContext(io) {
        call(
            "SignUp",
            buildJsonObject {
                put("ClientId", clientId)
                put("Username", email)
                put("Password", password)
                put("UserAttributes", buildJsonArray { add(buildJsonObject { put("Name", "name"); put("Value", name) }) })
            },
        )
        Unit
    }

    /** Confirms a sign-up with the emailed code. It doesn't sign in: the caller does that next. */
    suspend fun confirmSignUp(email: String, code: String) = withContext(io) {
        call(
            "ConfirmSignUp",
            buildJsonObject {
                put("ClientId", clientId)
                put("Username", email)
                put("ConfirmationCode", code)
            },
        )
        Unit
    }

    /**
     * Starts a password reset; Cognito emails a code. With prevent_user_existence_errors on the
     * pool, an email with no account succeeds the same way, so this reveals nothing (use case C.17).
     *
     * Cognito has once answered an unknown email with "no registered/verified email" instead
     * (mootmaker-android#45). That says the address has no usable account, so it is treated as sent
     * too: the next step looks the same either way, and no code comes.
     */
    suspend fun forgotPassword(email: String) = withContext(io) {
        try {
            call(
                "ForgotPassword",
                buildJsonObject {
                    put("ClientId", clientId)
                    put("Username", email)
                },
            )
        } catch (refused: CognitoException) {
            if (!refused.saysNoVerifiedAddress()) throw refused
        }
        Unit
    }

    private fun CognitoException.saysNoVerifiedAddress() =
        type == "InvalidParameterException" && message.contains("no registered/verified email", ignoreCase = true)

    /** Sets a new password with the emailed code. It doesn't sign in: the caller does that next. */
    suspend fun confirmForgotPassword(email: String, code: String, newPassword: String) = withContext(io) {
        call(
            "ConfirmForgotPassword",
            buildJsonObject {
                put("ClientId", clientId)
                put("Username", email)
                put("ConfirmationCode", code)
                put("Password", newPassword)
            },
        )
        Unit
    }

    /** Revokes the refresh token server-side. Best effort: signing out locally never waits on it. */
    suspend fun revoke(refreshToken: String) = withContext(io) {
        call(
            "RevokeToken",
            buildJsonObject {
                put("Token", refreshToken)
                put("ClientId", clientId)
            },
        )
        Unit
    }

    private fun call(action: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.$action")
            .post(body.toString().toRequestBody(CONTENT_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                if (json == null) throw IOException("Cognito $action failed with HTTP ${response.code}")
                // "__type" can carry a namespace prefix: "com.amazonaws...#NotAuthorizedException".
                val type = json.string("__type")?.substringAfterLast('#') ?: "HTTP ${response.code}"
                val message = json.string("message") ?: json.string("Message") ?: type
                throw CognitoException(type, message)
            }
            return json ?: throw IOException("Cognito $action returned a body that is not JSON")
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private companion object {
        val CONTENT_TYPE = "application/x-amz-json-1.1".toMediaType()
    }
}

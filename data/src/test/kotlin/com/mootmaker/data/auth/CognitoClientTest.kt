package com.mootmaker.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CognitoClientTest {
    private val server = MockWebServer()
    private lateinit var client: CognitoClient

    @Before
    fun setUp() {
        server.start()
        client = CognitoClient(
            http = OkHttpClient(),
            userPoolId = "ap-southeast-2_AbCdEfGhI",
            clientId = "android-client",
            endpoint = server.url("/").toString(),
            clock = Clock.fixed(Instant.parse("2026-10-07T01:02:03Z"), ZoneOffset.UTC),
            newSrp = { Srp(BigInteger.valueOf(123456789)) },
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun signInRunsTheSrpExchange() = runTest {
        server.enqueue(json(200, CHALLENGE))
        server.enqueue(json(200, """{"AuthenticationResult":{"IdToken":"id","AccessToken":"access","RefreshToken":"refresh"}}"""))

        val tokens = client.signIn("demo@mootmaker.com", "secret")

        assertEquals(Tokens("id", "access", "refresh"), tokens)
        val initiate = server.takeRequest()
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", initiate.getHeader("X-Amz-Target"))
        assertEquals("application/x-amz-json-1.1", initiate.getHeader("Content-Type")?.substringBefore(';'))
        val initiateBody = Json.parseToJsonElement(initiate.body.readUtf8()).jsonObject
        assertEquals("USER_SRP_AUTH", initiateBody["AuthFlow"]!!.jsonPrimitive.content)
        assertEquals("android-client", initiateBody["ClientId"]!!.jsonPrimitive.content)
        val authParameters = initiateBody["AuthParameters"]!!.jsonObject
        assertEquals("demo@mootmaker.com", authParameters["USERNAME"]!!.jsonPrimitive.content)
        assertEquals(Srp(BigInteger.valueOf(123456789)).largeA.toString(16), authParameters["SRP_A"]!!.jsonPrimitive.content)

        val respond = server.takeRequest()
        assertEquals("AWSCognitoIdentityProviderService.RespondToAuthChallenge", respond.getHeader("X-Amz-Target"))
        val responses = Json.parseToJsonElement(respond.body.readUtf8()).jsonObject["ChallengeResponses"]!!.jsonObject
        // Cognito wants the user id it issued, not the email that was typed.
        assertEquals("user-id-for-srp", responses["USERNAME"]!!.jsonPrimitive.content)
        assertEquals("c2VjcmV0", responses["PASSWORD_CLAIM_SECRET_BLOCK"]!!.jsonPrimitive.content)
        assertEquals("Wed Oct 7 01:02:03 UTC 2026", responses["TIMESTAMP"]!!.jsonPrimitive.content)
        assertTrue(responses["PASSWORD_CLAIM_SIGNATURE"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun wrongPasswordSurfacesCognitosError() = runTest {
        server.enqueue(json(200, CHALLENGE))
        server.enqueue(json(400, """{"__type":"NotAuthorizedException","message":"Incorrect username or password."}"""))

        val error = signInFailure()

        assertEquals("NotAuthorizedException", error.type)
        assertEquals("Incorrect username or password.", error.message)
    }

    @Test
    fun namespacedErrorTypeIsShortened() = runTest {
        server.enqueue(json(400, """{"__type":"com.amazonaws.cognito#UserNotFoundException","message":"User does not exist."}"""))
        assertEquals("UserNotFoundException", signInFailure().type)
    }

    @Test
    fun newPasswordRequiredReadsLikeTheWebapp() = runTest {
        server.enqueue(json(200, CHALLENGE))
        server.enqueue(json(200, """{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"s","ChallengeParameters":{}}"""))
        assertEquals("A password change is required for this account.", signInFailure().message)
    }

    @Test
    fun refreshKeepsTheRefreshToken() = runTest {
        server.enqueue(json(200, """{"AuthenticationResult":{"IdToken":"id2","AccessToken":"access2"}}"""))

        assertEquals(Tokens("id2", "access2", "refresh"), client.refresh("refresh"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("REFRESH_TOKEN_AUTH", body["AuthFlow"]!!.jsonPrimitive.content)
    }

    private suspend fun signInFailure(): CognitoException {
        try {
            client.signIn("demo@mootmaker.com", "wrong")
        } catch (expected: CognitoException) {
            return expected
        }
        fail("Expected sign-in to fail")
        throw AssertionError()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-amz-json-1.1").setBody(body)

    private companion object {
        // B = 2 is a valid (if unrealistic) server value; the signature itself is checked in SrpTest.
        const val CHALLENGE = """{"ChallengeName":"PASSWORD_VERIFIER","ChallengeParameters":{"SALT":"abcd","SRP_B":"2","SECRET_BLOCK":"c2VjcmV0","USER_ID_FOR_SRP":"user-id-for-srp","USERNAME":"user-id-for-srp"}}"""
    }
}

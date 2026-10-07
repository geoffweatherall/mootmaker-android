package com.mootmaker.data.auth

import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.config.ConfigRepository
import com.mootmaker.data.config.Environment
import com.mootmaker.data.config.MobileConfig
import com.mootmaker.testing.fakeIdToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionTest {
    private val cognito = MockWebServer()
    private val now = Instant.parse("2026-10-07T01:00:00Z")
    private val store = InMemoryKeyValueStore()
    private val tokenStore = TokenStore(store, PlainCipher)
    private val config = MobileConfig(
        graphqlApiUrl = "https://api.example.com/graphql",
        userPoolId = "ap-southeast-2_Pool",
        androidClientId = "client",
    )

    @Before
    fun setUp() = cognito.start()

    @After
    fun tearDown() = cognito.shutdown()

    private suspend fun TestScope.startedSession(): Session {
        // A cached config, so start() doesn't need the network for it.
        store.put("config.production", config.toJson())
        val session = Session(
            configRepository = ConfigRepository(OkHttpClient(), store, Dispatchers.Unconfined),
            tokenStore = tokenStore,
            cognitoFor = {
                CognitoClient(OkHttpClient(), it.userPoolId, it.androidClientId, cognito.url("/").toString(), io = Dispatchers.Unconfined)
            },
            scope = backgroundScope,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        session.start()
        return session
    }

    @Test
    fun storedTokensMeanSignedInAtStart() = runTest {
        tokenStore.save(Tokens(fakeIdToken(), "access", "refresh"))
        val session = startedSession()
        assertEquals("person-1", (session.state.value as SessionState.SignedIn).claims.personId)
        assertEquals(ConfigState.Ready(Environment.PRODUCTION, config), session.config.value)
    }

    @Test
    fun aFreshTokenIsUsedAsIs() = runTest {
        val idToken = fakeIdToken(expiresAt = now.plusSeconds(3600))
        tokenStore.save(Tokens(idToken, "access", "refresh"))
        assertEquals(idToken, startedSession().idToken())
        assertEquals(0, cognito.requestCount)
    }

    @Test
    fun aNearlyExpiredTokenIsRefreshedAndStored() = runTest {
        tokenStore.save(Tokens(fakeIdToken(expiresAt = now.plusSeconds(60)), "access", "refresh"))
        val renewed = fakeIdToken(expiresAt = now.plusSeconds(3600))
        cognito.enqueue(MockResponse().setBody("""{"AuthenticationResult":{"IdToken":"$renewed","AccessToken":"a2"}}"""))
        val session = startedSession()

        assertEquals(renewed, session.idToken())
        assertEquals(Tokens(renewed, "a2", "refresh"), tokenStore.load())
    }

    @Test
    fun aRefusedRefreshSignsOutCleanly() = runTest {
        tokenStore.save(Tokens(fakeIdToken(expiresAt = now.minusSeconds(60)), "access", "refresh"))
        cognito.enqueue(MockResponse().setResponseCode(400).setBody("""{"__type":"NotAuthorizedException","message":"Refresh Token has expired"}"""))
        val session = startedSession()

        val thrown = runCatching { session.idToken() }.exceptionOrNull()

        assertTrue(thrown is SessionExpiredException)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(null, tokenStore.load())
    }

    @Test
    fun signOutForgetsTheTokens() = runTest {
        tokenStore.save(Tokens(fakeIdToken(), "access", "refresh"))
        cognito.enqueue(MockResponse().setBody("{}"))
        val session = startedSession()

        session.signOut()

        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(null, tokenStore.load())
    }

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(sealed: ByteArray) = sealed
    }
}

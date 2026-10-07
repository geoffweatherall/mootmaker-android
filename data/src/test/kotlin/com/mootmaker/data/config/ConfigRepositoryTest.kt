package com.mootmaker.data.config

import com.mootmaker.data.InMemoryKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigRepositoryTest {
    private val store = InMemoryKeyValueStore()

    @Test
    fun environmentDefaultsToProductionAndIsRemembered() = runTest {
        val repository = ConfigRepository(OkHttpClient(), store, Dispatchers.Unconfined)
        assertEquals(Environment.PRODUCTION, repository.environment())
        repository.setEnvironment(Environment("test"))
        assertEquals(Environment("test"), repository.environment())
    }

    @Test
    fun aFetchedConfigIsCachedForTheNextColdStart() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"GRAPHQL_API_URL":"https://x/graphql","COGNITO_USER_POOL_ID":"us-east-1_X","COGNITO_ANDROID_CLIENT_ID":"c"}"""))
        server.start()
        // Route the environment's URL to the local server.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/mobile-config.json")).build())
        }.build()
        val repository = ConfigRepository(http, store, Dispatchers.Unconfined)

        val fetched = repository.fetch(Environment("test"))
        server.shutdown()

        assertEquals(fetched, repository.load(Environment("test")))
        assertEquals(null, repository.cached(Environment.PRODUCTION))
    }
}

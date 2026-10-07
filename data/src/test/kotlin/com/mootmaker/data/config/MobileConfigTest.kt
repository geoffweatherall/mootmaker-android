package com.mootmaker.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileConfigTest {
    @Test
    fun parsesTheFileTheWebappPublishes() {
        val config = MobileConfig.parse(
            """
            {
              "GRAPHQL_API_URL": "https://abc.appsync-api.ap-southeast-2.amazonaws.com/graphql",
              "COGNITO_USER_POOL_ID": "ap-southeast-2_AbC123",
              "COGNITO_CLIENT_ID": "webapp-client",
              "COGNITO_ANDROID_CLIENT_ID": "android-client",
              "DEMO_USER_EMAIL": "demo@mootmaker.com",
              "DEMO_USER_PASSWORD": "demo-password"
            }
            """,
        )
        assertEquals("android-client", config.androidClientId)
        assertEquals("ap-southeast-2_AbC123", config.userPoolId)
        assertEquals("demo@mootmaker.com", config.demoUserEmail)
    }

    @Test
    fun demoCredentialsAreOptional() {
        val config = MobileConfig.parse(
            """{"GRAPHQL_API_URL":"https://x/graphql","COGNITO_USER_POOL_ID":"us-east-1_X","COGNITO_ANDROID_CLIENT_ID":"c"}""",
        )
        assertNull(config.demoUserEmail)
    }

    @Test
    fun rejectsAConfigWithoutTheAndroidClient() {
        val result = runCatching {
            MobileConfig.parse("""{"GRAPHQL_API_URL":"https://x/graphql","COGNITO_USER_POOL_ID":"us-east-1_X","COGNITO_CLIENT_ID":"web"}""")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsAPlainHttpApi() {
        val result = runCatching {
            MobileConfig.parse("""{"GRAPHQL_API_URL":"http://x/graphql","COGNITO_USER_POOL_ID":"us-east-1_X","COGNITO_ANDROID_CLIENT_ID":"c"}""")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun roundTripsThroughTheCache() {
        val config = MobileConfig("https://x/graphql", "us-east-1_X", "c", "d@e.com", "p")
        assertEquals(config, MobileConfig.parse(config.toJson()))
    }
}

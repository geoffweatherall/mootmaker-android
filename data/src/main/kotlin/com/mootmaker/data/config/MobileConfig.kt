package com.mootmaker.data.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The runtime configuration the webapp's deploy.sh publishes beside env-config.js, as
 * `mobile-config.json`: the same values, plus the Android Cognito app client's id. Choosing the
 * environment at runtime is what lets one APK be tested and then published (design Decision 4).
 */
@Serializable
data class MobileConfig(
    @SerialName("GRAPHQL_API_URL") val graphqlApiUrl: String,
    @SerialName("COGNITO_USER_POOL_ID") val userPoolId: String,
    @SerialName("COGNITO_ANDROID_CLIENT_ID") val androidClientId: String,
    // Optional, as on the web: an environment not seeded with the demo user won't have these.
    @SerialName("DEMO_USER_EMAIL") val demoUserEmail: String? = null,
    @SerialName("DEMO_USER_PASSWORD") val demoUserPassword: String? = null,
) {
    init {
        require(graphqlApiUrl.startsWith("https://")) { "GRAPHQL_API_URL must be https" }
        require(USER_POOL_ID.matches(userPoolId)) { "COGNITO_USER_POOL_ID is not a user pool id" }
        require(androidClientId.isNotBlank()) { "COGNITO_ANDROID_CLIENT_ID is empty" }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val USER_POOL_ID = Regex("^[a-z]{2}-[a-z]+-\\d_[A-Za-z0-9]+$")

        // Unknown keys are ignored, so the webapp can add values without breaking installed apps.
        private val json = Json { ignoreUnknownKeys = true }

        /** Throws IllegalArgumentException (or a SerializationException, which is one) if invalid. */
        fun parse(text: String): MobileConfig = json.decodeFromString(serializer(), text)
    }
}

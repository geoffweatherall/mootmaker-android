package com.mootmaker.data.config

import com.mootmaker.data.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Loads [MobileConfig] for the chosen [Environment], caching the last good copy so that a cold
 * start doesn't depend on the network (design choice 3).
 */
class ConfigRepository(
    private val http: OkHttpClient,
    private val store: KeyValueStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun environment(): Environment =
        store.get(ENVIRONMENT_KEY)?.let { Environment.fromInput(it) } ?: Environment.PRODUCTION

    suspend fun setEnvironment(environment: Environment) {
        store.put(ENVIRONMENT_KEY, environment.name)
    }

    /** The cached config for [environment], if one was ever fetched successfully. */
    suspend fun cached(environment: Environment): MobileConfig? =
        store.get(cacheKey(environment))?.let { runCatching { MobileConfig.parse(it) }.getOrNull() }

    /** Fetches and caches the config. Throws [IOException] or IllegalArgumentException. */
    suspend fun fetch(environment: Environment): MobileConfig {
        val text = withContext(io) {
            val request = Request.Builder().url(environment.configUrl).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("${environment.configUrl} returned HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
        val config = MobileConfig.parse(text)
        store.put(cacheKey(environment), config.toJson())
        return config
    }

    /** The cached config when there is one (refreshed for next time by the caller), else a fetch. */
    suspend fun load(environment: Environment): MobileConfig = cached(environment) ?: fetch(environment)

    private fun cacheKey(environment: Environment) = "config.${environment.name}"

    private companion object {
        const val ENVIRONMENT_KEY = "environment"
    }
}

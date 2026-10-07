package com.mootmaker.app

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.mootmaker.data.DataStoreKeyValueStore
import com.mootmaker.data.KeyValueStore
import com.mootmaker.data.api.AvailabilityRepository
import com.mootmaker.data.api.AvailabilitySource
import com.mootmaker.data.api.CalendarRepository
import com.mootmaker.data.api.CalendarSource
import com.mootmaker.data.api.HomeRepository
import com.mootmaker.data.api.HomeSource
import com.mootmaker.data.api.MeetingFormRepository
import com.mootmaker.data.api.MeetingFormSource
import com.mootmaker.data.api.MeetingRepository
import com.mootmaker.data.api.MeetingSource
import com.mootmaker.data.auth.AndroidKeystoreCipher
import com.mootmaker.data.auth.CognitoClient
import com.mootmaker.data.auth.ConfigState
import com.mootmaker.data.auth.Session
import com.mootmaker.data.auth.TokenCipher
import com.mootmaker.data.auth.TokenStore
import com.mootmaker.data.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's long-lived objects, wired by hand: few enough that a DI framework isn't worth it.
 * Tests pass an [http] client whose interceptor plays the backend, and a [cipher] that works
 * where the Android Keystore doesn't (Robolectric), and a [store] that starts empty.
 */
class AppContainer(
    context: Context,
    private val http: OkHttpClient = defaultHttpClient(),
    cipher: TokenCipher = AndroidKeystoreCipher(),
    store: KeyValueStore = DataStoreKeyValueStore(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val configRepository = ConfigRepository(http, store)

    val session = Session(
        configRepository = configRepository,
        tokenStore = TokenStore(store, cipher),
        cognitoFor = { CognitoClient(http, it.userPoolId, it.androidClientId) },
        scope = scope,
    )

    private var apollo: Pair<String, ApolloClient>? = null

    private suspend fun apolloClient(): ApolloClient {
        val url = session.config.filterIsInstance<ConfigState.Ready>().first().config.graphqlApiUrl
        apollo?.takeIf { it.first == url }?.let { return it.second }
        return ApolloClient.Builder().serverUrl(url).okHttpClient(http).build().also { apollo = url to it }
    }

    val homeSource: HomeSource = HomeRepository(apollo = ::apolloClient, idToken = session::idToken)

    val availabilitySource: AvailabilitySource = AvailabilityRepository(apollo = ::apolloClient, idToken = session::idToken)

    val meetingSource: MeetingSource = MeetingRepository(apollo = ::apolloClient, idToken = session::idToken)

    val meetingFormSource: MeetingFormSource = MeetingFormRepository(apollo = ::apolloClient, idToken = session::idToken)

    val calendarSource: CalendarSource = CalendarRepository(apollo = ::apolloClient, idToken = session::idToken)

    private var started = false

    /** Loads config and stored tokens. Idempotent; called when the first activity is created. */
    fun start() {
        if (started) return
        started = true
        scope.launch { session.start() }
    }

    private companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

package com.mootmaker.app

import android.content.Context
import com.apollographql.apollo.ApolloClient
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.apollographql.apollo.network.okHttpClient
import com.mootmaker.data.DataStoreKeyValueStore
import com.mootmaker.data.KeyValueStore
import com.mootmaker.data.api.AdminRepository
import com.mootmaker.data.api.AdminSource
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
import com.mootmaker.data.api.SettingsRepository
import com.mootmaker.data.api.SettingsSource
import com.mootmaker.data.auth.AndroidKeystoreCipher
import com.mootmaker.data.auth.CognitoClient
import com.mootmaker.data.auth.ConfigState
import com.mootmaker.data.auth.Session
import com.mootmaker.data.auth.TokenCipher
import com.mootmaker.data.auth.TokenStore
import com.mootmaker.data.config.ConfigRepository
import com.mootmaker.data.live.AppSyncRealtime
import com.mootmaker.data.live.LiveEvent
import com.mootmaker.data.live.LiveUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    live: LiveUpdates? = null,
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

    val settingsSource: SettingsSource = SettingsRepository(apollo = ::apolloClient, idToken = session::idToken, http = http)

    val adminSource: AdminSource = AdminRepository(apollo = ::apolloClient, idToken = session::idToken)

    val calendarSource: CalendarSource = CalendarRepository(apollo = ::apolloClient, idToken = session::idToken)

    /**
     * Loads avatars through the same HTTP client as everything else, so a test's fake backend plays
     * the avatar host too. Coil caches by URL, which is safe because an avatar's URL changes with its image.
     */
    fun avatarLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
            // Avatars are small static JPEGs; BitmapFactory decodes them everywhere, which Robolectric's ImageDecoder does not.
            add(BitmapFactoryDecoder.Factory())
        }
        .build()

    private val liveUpdates: LiveUpdates = live ?: AppSyncRealtime(
        http = http,
        httpEndpoint = { session.config.filterIsInstance<ConfigState.Ready>().first().config.graphqlApiUrl },
        idToken = session::idToken,
    )

    private val _liveEvents = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * What the open screens listen to: someone else changed something, or the channel has just
     * (re)connected, and in both cases what a screen holds may be stale. No replay, because a screen
     * that starts listening has just loaded.
     */
    val liveEvents: SharedFlow<LiveEvent> = _liveEvents

    /**
     * Holds the realtime subscription open until cancelled. The UI runs it only while the app is in
     * the foreground, and the socket goes with it: Android freezes background sockets, so
     * correctness never rests on one surviving.
     */
    suspend fun followLiveUpdates() {
        liveUpdates.events().collect { _liveEvents.emit(it) }
    }

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

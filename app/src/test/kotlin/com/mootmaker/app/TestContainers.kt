package com.mootmaker.app

import androidx.test.core.app.ApplicationProvider
import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.auth.TokenCipher
import com.mootmaker.data.live.LiveEvent
import com.mootmaker.data.live.LiveUpdates
import com.mootmaker.data.live.NoLiveUpdates
import kotlinx.coroutines.flow.MutableSharedFlow
import com.mootmaker.testing.FakeBackend

/** Robolectric has no Android Keystore; the tokens' encryption is tested on the emulator. */
object PlainCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(sealed: ByteArray) = sealed
}

/** A realtime channel the test pushes events into by hand. */
class FakeLiveUpdates : LiveUpdates {
    private val channel = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 16)
    override fun events() = channel
    suspend fun push(event: LiveEvent) = channel.emit(event)
}

/** Points the app at [backend] before any activity starts. Live updates are off unless [live] is given. */
fun useFakeBackend(backend: FakeBackend, live: LiveUpdates = NoLiveUpdates): AppContainer {
    val application = ApplicationProvider.getApplicationContext<MootmakerApplication>()
    return AppContainer(application, backend.httpClient, PlainCipher, InMemoryKeyValueStore(), live)
        .also(application::replaceContainer)
}

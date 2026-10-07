package com.mootmaker.app

import androidx.test.core.app.ApplicationProvider
import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.auth.TokenCipher
import com.mootmaker.testing.FakeBackend

/** Robolectric has no Android Keystore; the tokens' encryption is tested on the emulator. */
object PlainCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(sealed: ByteArray) = sealed
}

/** Points the app at [backend] before any activity starts. */
fun useFakeBackend(backend: FakeBackend): AppContainer {
    val application = ApplicationProvider.getApplicationContext<MootmakerApplication>()
    return AppContainer(application, backend.httpClient, PlainCipher, InMemoryKeyValueStore())
        .also(application::replaceContainer)
}

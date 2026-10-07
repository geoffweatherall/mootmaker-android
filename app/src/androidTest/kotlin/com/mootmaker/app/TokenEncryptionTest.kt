package com.mootmaker.app

import com.mootmaker.data.InMemoryKeyValueStore
import com.mootmaker.data.auth.AndroidKeystoreCipher
import com.mootmaker.data.auth.TokenStore
import com.mootmaker.data.auth.Tokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Android Keystore exists only on a device, so this is the one place token encryption is proven. */
class TokenEncryptionTest {
    @Test
    fun tokensRoundTripAndAreNotStoredInTheClear() = runBlocking {
        val store = InMemoryKeyValueStore()
        val tokens = Tokens("id-token-value", "access-token-value", "refresh-token-value")
        val tokenStore = TokenStore(store, AndroidKeystoreCipher("mootmaker-test"))

        tokenStore.save(tokens)

        assertEquals(tokens, tokenStore.load())
        assertFalse(store.get("tokens")!!.contains("refresh-token-value"))
    }
}

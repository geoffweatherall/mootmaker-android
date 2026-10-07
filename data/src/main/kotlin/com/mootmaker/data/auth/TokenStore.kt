package com.mootmaker.data.auth

import com.mootmaker.data.KeyValueStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small strings before they are written to disk. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM with a key that lives in the Android Keystore and never leaves it, so a copy of the app's
 * data directory alone cannot reveal the tokens.
 */
class AndroidKeystoreCipher(private val alias: String = "mootmaker-tokens") : TokenCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_LENGTH))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

/** Keeps the signed-in user's tokens, encrypted, so the app stays signed in across restarts. */
class TokenStore(private val store: KeyValueStore, private val cipher: TokenCipher) {

    suspend fun load(): Tokens? {
        val sealed = store.get(KEY) ?: return null
        // Undecryptable (a restored backup on a new phone, a reset Keystore): treat as signed out.
        val plain = runCatching { String(cipher.decrypt(Base64.getDecoder().decode(sealed))) }.getOrNull()
            ?: return null.also { store.remove(KEY) }
        val parts = plain.split('\n')
        if (parts.size != 3) return null
        return Tokens(idToken = parts[0], accessToken = parts[1], refreshToken = parts[2])
    }

    suspend fun save(tokens: Tokens) {
        val plain = listOf(tokens.idToken, tokens.accessToken, tokens.refreshToken).joinToString("\n")
        store.put(KEY, Base64.getEncoder().encodeToString(cipher.encrypt(plain.toByteArray())))
    }

    suspend fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "tokens"
    }
}

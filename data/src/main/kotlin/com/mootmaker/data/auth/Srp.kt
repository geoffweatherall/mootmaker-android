package com.mootmaker.data.auth

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cognito's variant of SRP-6a (Secure Remote Password), as `amazon-cognito-identity-js` implements
 * it for the webapp. The password never leaves the device: the client proves it knows the password
 * by signing a server challenge with a key both sides derive independently.
 *
 * Every hex conversion goes through [padHex], which mirrors the JavaScript library exactly. That is
 * where SRP implementations usually disagree with Cognito, and the unit tests pin it against values
 * produced by the webapp's own library.
 */
class Srp(private val smallA: BigInteger = randomSmallA()) {

    /** The public value A = g^a mod N, sent as SRP_A in InitiateAuth. */
    val largeA: BigInteger = G.modPow(smallA, N).also {
        check(it.mod(N) != BigInteger.ZERO) { "SRP A must not be zero mod N" }
    }

    /**
     * The 16-byte key used to sign the PASSWORD_VERIFIER challenge. [userIdForSrp] is Cognito's
     * USER_ID_FOR_SRP challenge parameter, not the email typed in.
     */
    fun passwordAuthenticationKey(
        poolName: String,
        userIdForSrp: String,
        password: String,
        serverB: BigInteger,
        salt: BigInteger,
    ): ByteArray {
        require(serverB.mod(N) != BigInteger.ZERO) { "SRP B must not be zero mod N" }
        val u = BigInteger(hexHash(padHex(largeA) + padHex(serverB)), 16)
        require(u != BigInteger.ZERO) { "SRP u must not be zero" }
        val usernamePasswordHash = sha256Hex("$poolName$userIdForSrp:$password".toByteArray(UTF_8))
        val x = BigInteger(hexHash(padHex(salt) + usernamePasswordHash), 16)
        val gModPowX = G.modPow(x, N)
        val intValue2 = serverB.subtract(K.multiply(gModPowX))
        val s = intValue2.modPow(smallA.add(u.multiply(x)), N)
        return hkdf(ikm = hexToBytes(padHex(s)), salt = hexToBytes(padHex(u)))
    }

    companion object {
        // The 3072-bit group from RFC 5054, which Cognito uses.
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G = BigInteger.valueOf(2)
        private val K = BigInteger(hexHash(padHex(N) + padHex(G)), 16)
        private val INFO = "Caldera Derived Key".toByteArray(UTF_8)

        private val TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss 'UTC' yyyy", Locale.US).withZone(ZoneOffset.UTC)

        private fun randomSmallA(): BigInteger {
            val bytes = ByteArray(128)
            SecureRandom().nextBytes(bytes)
            return BigInteger(1, bytes).mod(N)
        }

        /**
         * Cognito's TIMESTAMP challenge response: "Wed Oct 7 01:02:03 UTC 2026". The day of the
         * month is not zero-padded; hours, minutes and seconds are.
         */
        fun timestamp(now: Instant): String = TIMESTAMP_FORMAT.format(now)

        /** PASSWORD_CLAIM_SIGNATURE: HMAC-SHA256 over pool name, user id, secret block and timestamp. */
        fun signature(
            key: ByteArray,
            poolName: String,
            userIdForSrp: String,
            secretBlockBase64: String,
            timestamp: String,
        ): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            mac.update(poolName.toByteArray(UTF_8))
            mac.update(userIdForSrp.toByteArray(UTF_8))
            mac.update(Base64.getDecoder().decode(secretBlockBase64))
            mac.update(timestamp.toByteArray(UTF_8))
            return Base64.getEncoder().encodeToString(mac.doFinal())
        }

        /**
         * A non-negative big integer as even-length hex, with a leading "00" when the top bit would
         * otherwise be set, so it never reads as negative. Mirrors the JavaScript library's padHex.
         */
        internal fun padHex(value: BigInteger): String {
            require(value.signum() >= 0) { "padHex is only used for non-negative values" }
            var hex = value.toString(16)
            if (hex.length % 2 == 1) hex = "0$hex"
            if (hex[0] in "89abcdef") hex = "00$hex"
            return hex
        }

        private fun hexHash(hex: String): String = sha256Hex(hexToBytes(hex))

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex().padStart(64, '0')

        private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
            val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(INFO)
            mac.update(1)
            return mac.doFinal().copyOf(16)
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

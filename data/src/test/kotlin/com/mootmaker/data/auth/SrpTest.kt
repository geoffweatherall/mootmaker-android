package com.mootmaker.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.time.Instant

/**
 * Pinned against amazon-cognito-identity-js 6.x, the library the webapp signs in with: the expected
 * values below were produced by its AuthenticationHelper with the same fixed inputs (a fixed `a`
 * in place of the random one). If these agree, Cognito accepts our signatures as it accepts the
 * webapp's.
 */
class SrpTest {
    private val smallA = BigInteger("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90", 16)
    private val serverB = BigInteger(
        "a13bb29f9153ec753b3db4cc149cf6cf13a49fd57c3c5114dabc4e7529231a2d2871dbd7b3a0e8e040350131e98959eba5074541969d70af050c1b2d47fa387a5905a2a377ede5bbbacd54e7a0b10b81c0501a4c2b3d6e91f6d9b0e466b4672355152fa3ae13f369cffaad23d4953d65aa79d1f98aa8be3d341dfd71a72d8700b15343fea15e20c9b1a4513c8224b546c26095ae2b3853132bd50d61e54fa9ea9ec70a82895499a773f09531ca2320fc9c8d1e1d0ac2b3a8118e2c43f742e373ea43298f8026b74788fb0e181ebbe83b2c4ac296964a9108b4a056142b14795629babd30404b84157600812afbb1b1257c604a9977cbda56db976798e7995b8e367172a3db968e7cf80812f1eff30eb610e8f31bb2aebe9531430a02c029e980974ca3c4562af27644d5b1c7b2c47d39ec6fee624d66bd68e943adb9047021f56e334cc8744fac3df49c83a03ea7f2d332cab2d0dd9627db705aeba497aa6b4ccfba4b1795f4f47758bbb512ae91878e20b792a9fa5702bcf0bc3c5d2ade57bb",
        16,
    )
    // Starts with 8, so padHex has to prefix "00": the classic way SRP clients disagree with Cognito.
    private val salt = BigInteger("8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d", 16)
    private val secretBlock = "dGhpcyBpcyBhbiBvcGFxdWUgc2VjcmV0IGJsb2NrIGZyb20gY29nbml0bywgYW55IGJ5dGVzIHdpbGwgZG8="

    @Test
    fun largeAMatchesTheWebappLibrary() {
        assertEquals(
            "6b657335b894e72fbe31d477aed9e3e164910d3aac6b731f289e3ddf21f99a64c04e3161de12dd815c28d1ae699f4e619905fbcaeda9e6aacf9a8badcd4bcb58c792496b2a1a8d9a9ebb8741c9b20f2b7d85228fab9804f41d05fccfff642c1eef278941bdab3521835c87a16140d86230bee2c11d06bcfb68790bf832e1e62621b6ff6c2ce48401b53740f58906c776de295baf3656b2af30a17c0c078421dcfbf8ef169d123e2ddc05c77ef8ed738b1cfd3fe64d37b15c5c2004fe6d70fc82ece30a19f37f4d14fa10103ad82869a256d657896a787b73cb2aa16e51dabfd113671251c4d1f0ac008aa97cdadda92eafa13b3716e19805826c41c15beb8beef2923db495870107b65d36c775131008cdae648b401de4e060d027f6bd24f3f28fce0cf451c8bcd61f517c9df57666621f7d0a88a16fd28c8713e93a9839c2d3f869f131b111278e28de80c884b5aea5180ad6ea14a28c53efe7c58227da5372a50570cea8479e179e16181c1726d7c910d4ebae39ccfe2f1ee26071aba17be8",
            Srp(smallA).largeA.toString(16),
        )
    }

    @Test
    fun passwordKeyAndSignatureMatchTheWebappLibrary() {
        val key = Srp(smallA).passwordAuthenticationKey(
            poolName = "AbCdEfGhI",
            userIdForSrp = "3b2c1d0e-aaaa-bbbb-cccc-1234567890ab",
            password = "Demo-Passw0rd!",
            serverB = serverB,
            salt = salt,
        )
        assertEquals("c727ffd55cd556f7f1002b275f5dcba8", key.joinToString("") { "%02x".format(it) })
        assertEquals(
            "nS1/x2/BQqlzmSXYupV+paI3wqQXJKYKNLm5X2hpO7g=",
            Srp.signature(key, "AbCdEfGhI", "3b2c1d0e-aaaa-bbbb-cccc-1234567890ab", secretBlock, "Wed Oct 7 01:02:03 UTC 2026"),
        )
    }

    @Test
    fun timestampHasAnUnpaddedDayAndPaddedTime() {
        assertEquals("Wed Oct 7 01:02:03 UTC 2026", Srp.timestamp(Instant.parse("2026-10-07T01:02:03Z")))
        assertEquals("Sat Dec 26 23:59:09 UTC 2026", Srp.timestamp(Instant.parse("2026-12-26T23:59:09Z")))
    }

    @Test
    fun padHexMirrorsTheJavascriptLibrary() {
        assertEquals("14", Srp.padHex(BigInteger.valueOf(20)))
        assertEquals("00ec", Srp.padHex(BigInteger.valueOf(236)))
        assertEquals("0100", Srp.padHex(BigInteger.valueOf(256)))
        assertEquals("00", Srp.padHex(BigInteger.ZERO))
    }
}

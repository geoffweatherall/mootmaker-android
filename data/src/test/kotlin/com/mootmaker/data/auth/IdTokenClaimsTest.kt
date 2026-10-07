package com.mootmaker.data.auth

import com.mootmaker.testing.fakeIdToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IdTokenClaimsTest {
    @Test
    fun readsTheClaimsTheAppUses() {
        val claims = IdTokenClaims.parse(fakeIdToken(expiresAt = Instant.parse("2026-10-07T10:00:00Z")))
        assertEquals("pat@example.com", claims.email)
        assertEquals("Pat Example", claims.name)
        assertEquals("person-1", claims.personId)
        assertEquals(Instant.parse("2026-10-07T10:00:00Z"), claims.expiresAt)
    }

    @Test
    fun anAccountWithNoLinkedPersonHasNoPersonId() {
        assertNull(IdTokenClaims.parse(fakeIdToken(personId = null)).personId)
    }
}

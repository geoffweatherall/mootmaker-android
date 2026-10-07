package com.mootmaker.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnvironmentTest {
    @Test
    fun productionIsTheBareDomain() {
        assertEquals("https://www.mootmaker.com/mobile-config.json", Environment.PRODUCTION.configUrl)
    }

    @Test
    fun otherEnvironmentsAreSubdomains() {
        assertEquals("https://www.test.mootmaker.com/mobile-config.json", Environment("test").configUrl)
        assertEquals("https://www.geoff-261007-ab12.mootmaker.com", Environment("geoff-261007-ab12").siteUrl)
    }

    @Test
    fun blankInputMeansProduction() {
        assertEquals(Environment.PRODUCTION, Environment.fromInput("  "))
    }

    @Test
    fun inputIsTrimmedAndLowercased() {
        assertEquals(Environment("and-acc-261007-x1y2"), Environment.fromInput(" AND-ACC-261007-x1y2 "))
    }

    @Test
    fun anythingThatCouldEscapeTheDomainIsRefused() {
        assertNull(Environment.fromInput("evil.example.com/"))
        assertNull(Environment.fromInput("a b"))
    }
}

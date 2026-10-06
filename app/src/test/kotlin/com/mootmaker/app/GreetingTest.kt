package com.mootmaker.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun greetingNamesTheApp() {
        assertEquals("Mootmaker", greeting())
    }
}

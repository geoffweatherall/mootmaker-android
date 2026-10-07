package com.mootmaker.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeInstrumentedTest {
    @Test
    fun appPackageIsCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.mootmaker.app", context.packageName)
    }
}

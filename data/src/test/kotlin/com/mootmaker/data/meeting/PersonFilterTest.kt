package com.mootmaker.data.meeting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonFilterTest {
    @Test
    fun matchesAnyPartOfTheNameIgnoringCase() {
        assertTrue(matchesName("Joanna", "ann"))
        assertTrue(matchesName("Anne", "ANN"))
        assertTrue(matchesName("Pat Example", "t ex"))
        assertFalse(matchesName("Bob", "ann"))
    }

    @Test
    fun aBlankQueryMatchesEveryone() {
        assertTrue(matchesName("Bob", ""))
        assertTrue(matchesName("Bob", "   "))
    }

    @Test
    fun theQueryIsTrimmed() {
        assertTrue(matchesName("Joanna", "  ann "))
    }

    @Test
    fun accentsDoNotGetInTheWay() {
        assertTrue(matchesName("José", "jose"))
        assertTrue(matchesName("Jose", "JOSÉ"))
    }

    @Test
    fun capitalIDoesNotBecomeDotlessUnderATurkishLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertTrue(matchesName("IAN", "ian"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun anEmailAddressIsNotPartOfTheName() {
        assertFalse(matchesName("Bob", "example.com"))
    }
}

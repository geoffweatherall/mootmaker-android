package com.mootmaker.data.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class AdminTest {
    private val pat = AdminPerson("p1", "Pat Example", true, listOf("pat@example.com"), null, isSelf = true)
    private val sam = AdminPerson("p2", "Sam Other", false, listOf("sam@work.example.org"), null, isSelf = false)
    private val gale = AdminPerson("p3", "Guest Gale", false, emptyList(), null, isSelf = false)
    private val everyone = listOf(pat, sam, gale)

    @Test
    fun anEmptyFilterKeepsEveryone() = assertEquals(everyone, filterPeople(everyone, "  "))

    @Test
    fun theFilterMatchesANameIgnoringCaseAndSpaces() = assertEquals(listOf(gale), filterPeople(everyone, "  gUEST "))

    @Test
    fun theFilterMatchesAnyLinkedEmail() = assertEquals(listOf(sam), filterPeople(everyone, "WORK.example"))

    @Test
    fun aGuestIsSomeoneWithNoLinkedAccount() {
        assertEquals(true, gale.isGuest)
        assertEquals(false, sam.isGuest)
    }

    @Test
    fun refusalsAreWordedAsOnTheWebapp() {
        assertEquals("Room capacity must be at least 2.", roomErrorMessage("CapacityTooLow"))
        assertEquals(
            "You cannot delete your own person this way - use Delete account in Settings instead.",
            adminPersonErrorMessage("CannotDeleteSelf"),
        )
        assertEquals("That change was not accepted (Unknown).", roomErrorMessage("Unknown"))
    }
}

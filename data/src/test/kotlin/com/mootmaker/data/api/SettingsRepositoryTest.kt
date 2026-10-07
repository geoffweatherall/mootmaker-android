package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.settings.SettingsResult
import com.mootmaker.testing.FakeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings requests, against [FakeBackend], including the avatar's presigned PUT. */
class SettingsRepositoryTest {
    private val backend = FakeBackend()
    private val repository = SettingsRepository(
        apollo = { ApolloClient.Builder().serverUrl("https://${FakeBackend.GRAPHQL_HOST}/graphql").okHttpClient(backend.httpClient).build() },
        idToken = { "token" },
        http = backend.httpClient,
    )

    @Test
    fun loadReadsTheProfileAndAnAccountWithNoPersonHasNone() = runBlocking {
        backend.dateFormat = "Usa"
        backend.timeFormat = "AmPm"
        backend.weekStart = "Sunday"
        val profile = repository.load()!!
        assertEquals("person-1", profile.personId)
        assertEquals("Pat Example", profile.name)
        assertEquals(DateFormat.Usa, profile.dateFormat)
        assertEquals(TimeFormat.AmPm, profile.timeFormat)
        assertEquals("Sunday", profile.weekStart)
        assertNull(profile.avatarUrl)

        backend.personId = null
        assertNull(repository.load())
    }

    @Test
    fun aBlankNameIsRefusedWithTheApisWordsAndNothingChanges() = runBlocking {
        val result = repository.updateName("  ")
        assertEquals(SettingsResult.Rejected(listOf("Name must not be blank.")), result)
        assertEquals("Pat Example", backend.personName)
        assertEquals(SettingsResult.Saved, repository.updateName("Patricia"))
        assertEquals("Patricia", backend.personName)
    }

    @Test
    fun preferencesAreSavedTogetherAndKeepTheWeekStart() = runBlocking {
        backend.weekStart = "Sunday"
        assertEquals(SettingsResult.Saved, repository.updatePreferences(DateFormat.British, TimeFormat.AmPm, "Sunday"))
        assertEquals("British/AmPm/Sunday", backend.lastPreferences)
    }

    @Test
    fun anAvatarIsRequestedPutWithoutAuthorizationAndConfirmed() = runBlocking {
        val image = ByteArray(1234) { it.toByte() }
        assertEquals(SettingsResult.Saved, repository.setAvatar("person-1", image, "image/jpeg"))

        assertTrue(backend.uploadedBytes!!.contentEquals(image))
        assertEquals("image/jpeg", backend.uploadHeaders["Content-Type"])
        assertEquals("1234", backend.uploadHeaders["Content-Length"])
        assertNull("The presigned URL must not be sent the API's token", backend.uploadHeaders["Authorization"])
        assertEquals(listOf("graphql RequestAvatarUpload", "upload PUT", "graphql ConfirmAvatarUpload"), backend.requests.filter { it.startsWith("graphql Re") || it.startsWith("upload") || it.startsWith("graphql Conf") })
        assertNotNull(backend.avatarUrl)
    }

    @Test
    fun anUnsupportedImageTypeIsRefusedBeforeAnythingIsUploaded() = runBlocking {
        val result = repository.setAvatar("person-1", ByteArray(10), "image/gif")
        assertEquals(SettingsResult.Rejected(listOf("Avatars must be JPEG or PNG images.")), result)
        assertNull(backend.uploadedBytes)
    }

    @Test
    fun removingTheAvatarClearsIt() = runBlocking {
        repository.setAvatar("person-1", ByteArray(10), "image/png")
        assertEquals(SettingsResult.Saved, repository.removeAvatar("person-1"))
        assertNull(backend.avatarUrl)
    }
}

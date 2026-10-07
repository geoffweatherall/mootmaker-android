package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.graphql.ConfirmAvatarUploadMutation
import com.mootmaker.data.graphql.DeleteMyAccountMutation
import com.mootmaker.data.graphql.RemoveAvatarMutation
import com.mootmaker.data.graphql.RequestAvatarUploadMutation
import com.mootmaker.data.graphql.SettingsQuery
import com.mootmaker.data.graphql.UpdateMyNameMutation
import com.mootmaker.data.graphql.UpdateMyPreferencesMutation
import com.mootmaker.data.graphql.type.DateFormat as ApiDateFormat
import com.mootmaker.data.graphql.type.PreferencesInput
import com.mootmaker.data.graphql.type.TimeFormat as ApiTimeFormat
import com.mootmaker.data.graphql.type.WeekStart
import com.mootmaker.data.settings.Profile
import com.mootmaker.data.settings.SettingsResult
import com.mootmaker.data.settings.avatarErrorMessage
import com.mootmaker.data.settings.personErrorMessage
import com.mootmaker.data.settings.preferencesErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface SettingsSource {
    /** The signed-in person, or null for an account with no linked Person. */
    suspend fun load(): Profile?

    suspend fun updateName(name: String): SettingsResult

    suspend fun updatePreferences(dateFormat: DateFormat, timeFormat: TimeFormat, weekStart: String): SettingsResult

    /** Sets the avatar to [bytes], a JPEG or PNG: request a slot, PUT the bytes there, then confirm. */
    suspend fun setAvatar(personId: String, bytes: ByteArray, contentType: String): SettingsResult

    suspend fun removeAvatar(personId: String): SettingsResult

    /** Deletes the caller's account for good, or throws an [ApiException] worded for the screen. */
    suspend fun deleteMyAccount()
}

/** Reads and changes the caller's own profile. Validation, including the image's, stays on the server. */
class SettingsRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
    private val http: OkHttpClient,
) : SettingsSource {
    override suspend fun load(): Profile? {
        val me = apollo().call(SettingsQuery(), idToken(), "Something went wrong loading your settings.").workspace.me ?: return null
        return Profile(
            personId = me.id,
            name = me.name,
            dateFormat = when (me.dateFormat) {
                ApiDateFormat.Usa -> DateFormat.Usa
                ApiDateFormat.British -> DateFormat.British
                else -> DateFormat.Iso
            },
            timeFormat = if (me.timeFormat == ApiTimeFormat.AmPm) TimeFormat.AmPm else TimeFormat.TwentyFourHour,
            weekStart = me.weekStart.rawValue,
            avatarUrl = me.avatarUrl,
        )
    }

    override suspend fun updateName(name: String): SettingsResult {
        val result = apollo().send(UpdateMyNameMutation(name), idToken(), FAILED).updateMyName
        return outcome(result.errors.map { personErrorMessage(it.rawValue) }, result.person != null)
    }

    override suspend fun updatePreferences(dateFormat: DateFormat, timeFormat: TimeFormat, weekStart: String): SettingsResult {
        val preferences = PreferencesInput(
            dateFormat = when (dateFormat) {
                DateFormat.Usa -> ApiDateFormat.Usa
                DateFormat.British -> ApiDateFormat.British
                DateFormat.Iso -> ApiDateFormat.Iso
            },
            timeFormat = if (timeFormat == TimeFormat.AmPm) ApiTimeFormat.AmPm else ApiTimeFormat.TwentyFourHour,
            weekStart = if (weekStart == WeekStart.Sunday.rawValue) WeekStart.Sunday else WeekStart.Monday,
        )
        val result = apollo().send(UpdateMyPreferencesMutation(preferences), idToken(), FAILED).updateMyPreferences
        return outcome(result.errors.map { preferencesErrorMessage(it.rawValue) }, result.person != null)
    }

    override suspend fun setAvatar(personId: String, bytes: ByteArray, contentType: String): SettingsResult {
        val requested = apollo().send(RequestAvatarUploadMutation(personId, contentType, bytes.size), idToken(), FAILED).requestAvatarUpload
        val upload = requested.upload
        if (requested.errors.isNotEmpty()) return SettingsResult.Rejected(requested.errors.map { avatarErrorMessage(it.rawValue) })
        if (upload == null) throw ApiException(FAILED)

        put(upload.url, upload.contentType, bytes)

        val confirmed = apollo().send(ConfirmAvatarUploadMutation(personId, upload.uploadId), idToken(), FAILED).confirmAvatarUpload
        return outcome(confirmed.errors.map { avatarErrorMessage(it.rawValue) }, confirmed.person != null)
    }

    override suspend fun removeAvatar(personId: String): SettingsResult {
        val result = apollo().send(RemoveAvatarMutation(personId), idToken(), FAILED).removeAvatar
        return outcome(result.errors.map { avatarErrorMessage(it.rawValue) }, result.person != null)
    }

    override suspend fun deleteMyAccount() {
        val deleted = apollo().send(DeleteMyAccountMutation(), idToken(), DELETE_FAILED).deleteMyAccount
        if (!deleted) throw ApiException(DELETE_FAILED)
    }

    /**
     * The presigned PUT. It must carry exactly the declared Content-Type and length and no
     * Authorization header: the URL's signature covers both, and the bucket refuses a stray one.
     */
    private suspend fun put(url: String, contentType: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).put(bytes.toRequestBody(contentType.toMediaType())).build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("The image could not be uploaded (HTTP ${response.code}).")
            }
        } catch (network: IOException) {
            throw ApiException(HomeRepository.NETWORK_MESSAGE, network)
        }
    }

    private fun outcome(errors: List<String>, applied: Boolean): SettingsResult = when {
        errors.isNotEmpty() -> SettingsResult.Rejected(errors)
        applied -> SettingsResult.Saved
        else -> throw ApiException(FAILED)
    }

    private companion object {
        const val FAILED = "Something went wrong saving your settings."
        const val DELETE_FAILED = "Something went wrong deleting your account."
    }
}

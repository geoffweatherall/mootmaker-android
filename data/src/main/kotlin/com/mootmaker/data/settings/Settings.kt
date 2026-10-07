package com.mootmaker.data.settings

import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat

/** The signed-in person as Settings shows them. [weekStart] is carried only so a save sends it back unchanged. */
data class Profile(
    val personId: String,
    val name: String,
    val dateFormat: DateFormat,
    val timeFormat: TimeFormat,
    val weekStart: String,
    val avatarUrl: String?,
)

/** What a change to the profile came to: it was applied, or the API refused it with these messages. */
sealed interface SettingsResult {
    data object Saved : SettingsResult
    data class Rejected(val messages: List<String>) : SettingsResult
}

fun personErrorMessage(code: String): String = when (code) {
    "NameRequired" -> "Name must not be blank."
    "NoLinkedPerson" -> "Your account has no linked person yet, so this can't be changed here."
    else -> "That change was not accepted ($code)."
}

fun preferencesErrorMessage(code: String): String = when (code) {
    "NoLinkedPerson" -> "Your account has no linked person yet, so these can't be changed here."
    else -> "That change was not accepted ($code)."
}

fun avatarErrorMessage(code: String): String = when (code) {
    "UnsupportedContentType" -> "Avatars must be JPEG or PNG images."
    "UploadTooLarge" -> "That image is too large. Avatars can be at most 2 MB."
    "InvalidContentLength" -> "That image is empty."
    "UploadNotFound" -> "The upload did not complete. Please try again."
    "NotAnImage" -> "That file is not a JPEG or PNG image."
    "ImageTooSmall" -> "That image is too small. Avatars must be at least 64 by 64 pixels."
    "ImageTooLarge" -> "That image is too large. Avatars can be at most 4096 by 4096 pixels."
    "PersonNotFound" -> "Your person could not be found."
    else -> "That change was not accepted ($code)."
}

/** The date and time formats the Settings pickers offer, each with the example the webapp uses. */
val DATE_FORMAT_EXAMPLES: List<Pair<DateFormat, String>> = listOf(
    DateFormat.Iso to "2026-08-24",
    DateFormat.British to "24/08/2026",
    DateFormat.Usa to "08/24/2026",
)

val TIME_FORMAT_EXAMPLES: List<Pair<TimeFormat, String>> = listOf(
    TimeFormat.TwentyFourHour to "14:30",
    TimeFormat.AmPm to "02:30 PM",
)

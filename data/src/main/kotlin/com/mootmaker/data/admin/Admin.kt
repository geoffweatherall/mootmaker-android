package com.mootmaker.data.admin

import com.mootmaker.data.agenda.RoomColor

/**
 * A room as the Rooms screen shows and edits it. [color] is null for a room with no explicit colour;
 * [colorSlot] is the colour it is drawn in either way, the same slot room availability uses.
 */
data class AdminRoom(val id: String, val name: String, val capacity: Int, val color: RoomColor?, val colorSlot: Int = color?.ordinal ?: 0)

/**
 * A person as the Persons screen shows them. [linkedEmails] is empty for a guest, someone added by an
 * admin who has not signed up. [isSelf] is the signed-in admin's own Person.
 */
data class AdminPerson(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    val linkedEmails: List<String>,
    val avatarUrl: String?,
    val isSelf: Boolean,
) {
    val isGuest: Boolean get() = linkedEmails.isEmpty()
}

/** What an admin change came to. */
sealed interface AdminResult {
    data object Done : AdminResult

    /** The Person changed, but syncing admin access to their sign-in account failed. Retrying is safe. */
    data object SyncFailed : AdminResult

    data class Rejected(val messages: List<String>) : AdminResult
}

/** The webapp's ROOM_ERROR_MESSAGES, word for word. */
fun roomErrorMessage(code: String): String = when (code) {
    "NameRequired" -> "Name must not be blank."
    "CapacityTooLow" -> "Room capacity must be at least 2."
    "RoomNotFound" -> "This room no longer exists - it may have been deleted."
    "RoomHasUpcomingMeetings" -> "This room has one or more meetings booked from today onward - reassign or cancel them before deleting it."
    else -> "That change was not accepted ($code)."
}

/** The webapp's PERSON_ERROR_MESSAGES for the admin mutations, word for word. */
fun adminPersonErrorMessage(code: String): String = when (code) {
    "NameRequired" -> "Name must not be blank."
    "NameAlreadyExists" -> "A person with this name already exists."
    "PersonNotFound" -> "This person no longer exists - it may have been deleted."
    "CannotDeleteSelf" -> "You cannot delete your own person this way - use Delete account in Settings instead."
    "ReservedAccount" -> "This account is reserved and cannot be deleted."
    "NoLinkedAccount" -> "This person hasn't signed in yet, so admin access can't be granted until they sign up."
    "CannotRevokeOwnAdminAccess" -> "You cannot remove your own admin access."
    else -> "That change was not accepted ($code)."
}

/**
 * People whose name or any linked email contains [filter], ignoring case and surrounding spaces, as
 * the webapp matches: an admin is as likely to search by one as the other. An empty filter keeps everyone.
 */
fun filterPeople(people: List<AdminPerson>, filter: String): List<AdminPerson> {
    val needle = filter.trim().lowercase()
    if (needle.isEmpty()) return people
    return people.filter { person ->
        person.name.lowercase().contains(needle) || person.linkedEmails.any { it.lowercase().contains(needle) }
    }
}

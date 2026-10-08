package com.mootmaker.data.api

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.mootmaker.data.admin.AdminPerson
import com.mootmaker.data.admin.AdminResult
import com.mootmaker.data.admin.AdminRoom
import com.mootmaker.data.admin.adminPersonErrorMessage
import com.mootmaker.data.admin.roomErrorMessage
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.RoomInput
import com.mootmaker.data.agenda.roomColorSlots
import com.mootmaker.data.graphql.AdminPeopleQuery
import com.mootmaker.data.graphql.AdminRoomsQuery
import com.mootmaker.data.graphql.CreatePersonMutation
import com.mootmaker.data.graphql.CreateRoomMutation
import com.mootmaker.data.graphql.DeletePersonMutation
import com.mootmaker.data.graphql.DeleteRoomMutation
import com.mootmaker.data.graphql.RenamePersonMutation
import com.mootmaker.data.graphql.SetPersonAdminMutation
import com.mootmaker.data.graphql.UpdateRoomMutation
import com.mootmaker.data.graphql.type.RoomColor as ApiRoomColor
import com.mootmaker.data.graphql.type.RoomInput as ApiRoomInput

/** The admin screens' reads and writes (M9). The server enforces who may call them; see Admin.graphql. */
interface AdminSource {
    /** Every room, sorted by name as the webapp sorts them. */
    suspend fun rooms(): List<AdminRoom>

    suspend fun createRoom(name: String, capacity: Int, color: RoomColor?): AdminResult

    suspend fun updateRoom(id: String, name: String, capacity: Int, color: RoomColor?): AdminResult

    suspend fun deleteRoom(id: String): AdminResult

    /** Every person, sorted by name, with the caller's own Person marked. */
    suspend fun people(): List<AdminPerson>

    suspend fun createPerson(name: String): AdminResult

    suspend fun renamePerson(id: String, name: String): AdminResult

    suspend fun setPersonAdmin(id: String, isAdmin: Boolean): AdminResult

    suspend fun deletePerson(id: String): AdminResult
}

class AdminRepository(
    private val apollo: suspend () -> ApolloClient,
    private val idToken: suspend () -> String,
    /** Called after every write. Rooms and people are never broadcast, and a deleted person leaves meetings. */
    private val onWrite: () -> Unit = {},
) : AdminSource {
    override suspend fun rooms(): List<AdminRoom> {
        val rooms = apollo().call(AdminRoomsQuery(), idToken(), "Something went wrong loading the rooms.").workspace.rooms
        // Slots come from the API's order, before sorting, so a room has the same colour as in availability.
        val slots = roomColorSlots(rooms.map { RoomInput(it.id, it.name, it.color.toRoomColor()) })
        return rooms
            .map { AdminRoom(it.id, it.name, it.capacity, it.color.toRoomColor(), slots.getValue(it.id)) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun createRoom(name: String, capacity: Int, color: RoomColor?): AdminResult {
        val result = apollo().send(CreateRoomMutation(roomInput(name, capacity, color)), idToken(), FAILED).createRoom
        return outcome(result.errors.map { roomErrorMessage(it.rawValue) }, result.room != null)
    }

    override suspend fun updateRoom(id: String, name: String, capacity: Int, color: RoomColor?): AdminResult {
        val result = apollo().send(UpdateRoomMutation(id, roomInput(name, capacity, color)), idToken(), FAILED).updateRoom
        return outcome(result.errors.map { roomErrorMessage(it.rawValue) }, result.room != null)
    }

    override suspend fun deleteRoom(id: String): AdminResult {
        val result = apollo().send(DeleteRoomMutation(id), idToken(), FAILED).deleteRoom
        return outcome(result.errors.map { roomErrorMessage(it.rawValue) }, applied = true)
    }

    override suspend fun people(): List<AdminPerson> {
        val workspace = apollo().call(AdminPeopleQuery(), idToken(), "Something went wrong loading the people.").workspace
        val me = workspace.me?.id
        return workspace.people
            .map { AdminPerson(it.id, it.name, it.isAdmin, it.linkedEmails, it.avatarUrl, isSelf = it.id == me) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun createPerson(name: String): AdminResult {
        val result = apollo().send(CreatePersonMutation(name), idToken(), FAILED).createPerson
        return outcome(result.errors.map { adminPersonErrorMessage(it.rawValue) }, result.person != null)
    }

    override suspend fun renamePerson(id: String, name: String): AdminResult {
        val result = apollo().send(RenamePersonMutation(id, name), idToken(), FAILED).renamePerson
        return outcome(result.errors.map { adminPersonErrorMessage(it.rawValue) }, result.person != null)
    }

    override suspend fun setPersonAdmin(id: String, isAdmin: Boolean): AdminResult {
        val result = apollo().send(SetPersonAdminMutation(id, isAdmin), idToken(), FAILED).setPersonAdmin
        val rejected = outcome(result.errors.map { adminPersonErrorMessage(it.rawValue) }, result.person != null)
        return if (rejected == AdminResult.Done && result.cognitoSyncFailed) AdminResult.SyncFailed else rejected
    }

    override suspend fun deletePerson(id: String): AdminResult {
        val result = apollo().send(DeletePersonMutation(id), idToken(), FAILED).deletePerson
        return outcome(result.errors.map { adminPersonErrorMessage(it.rawValue) }, applied = true)
    }

    private fun roomInput(name: String, capacity: Int, color: RoomColor?) = ApiRoomInput(
        name = name,
        capacity = capacity,
        color = Optional.present(color?.let { chosen -> ApiRoomColor.entries.first { it.rawValue == chosen.name } }),
    )

    private fun ApiRoomColor?.toRoomColor(): RoomColor? =
        this?.let { api -> RoomColor.entries.firstOrNull { it.name == api.rawValue } }

    private fun outcome(errors: List<String>, applied: Boolean): AdminResult {
        onWrite()
        return when {
            errors.isNotEmpty() -> AdminResult.Rejected(errors)
            applied -> AdminResult.Done
            else -> throw ApiException(FAILED)
        }
    }

    private companion object {
        const val FAILED = "Something went wrong saving that change."
    }
}

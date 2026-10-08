package com.mootmaker.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.admin.AdminResult
import com.mootmaker.data.admin.AdminRoom
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.api.AdminSource
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.auth.SessionExpiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The add or edit dialog. [roomId] is null when adding. [capacity] is what was typed. */
data class RoomEditor(
    val roomId: String? = null,
    val name: String = "",
    val capacity: String = "",
    val color: RoomColor? = null,
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
)

/** The remove confirmation for [room]. */
data class RoomRemoval(val room: AdminRoom, val removing: Boolean = false, val errors: List<String> = emptyList())

data class RoomsState(
    val rooms: List<AdminRoom> = emptyList(),
    val loaded: Boolean = false,
    val loadError: String? = null,
    val editor: RoomEditor? = null,
    val removal: RoomRemoval? = null,
)

/** Use cases P.125 to P.131. Validation is the API's: the dialog shows whatever it refuses, in its words. */
class RoomsViewModel(private val source: AdminSource) : ViewModel() {
    private val _state = MutableStateFlow(RoomsState())
    val state: StateFlow<RoomsState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            try {
                val rooms = source.rooms()
                _state.update { it.copy(rooms = rooms, loaded = true, loadError = null) }
            } catch (expired: SessionExpiredException) {
                // Signed out meanwhile; navigation returns to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(loadError = failure.message) }
            }
        }
    }

    fun startAdding() = _state.update { it.copy(editor = RoomEditor()) }

    fun startEditing(room: AdminRoom) =
        _state.update { it.copy(editor = RoomEditor(room.id, room.name, room.capacity.toString(), room.color)) }

    fun setName(name: String) = editor { it.copy(name = name, errors = emptyList()) }

    fun setCapacity(capacity: String) = editor { it.copy(capacity = capacity.filter(Char::isDigit), errors = emptyList()) }

    fun setColor(color: RoomColor?) = editor { it.copy(color = color, errors = emptyList()) }

    fun closeEditor() = _state.update { if (it.editor?.saving == true) it else it.copy(editor = null) }

    fun save() {
        val editing = _state.value.editor ?: return
        if (editing.saving) return
        // An empty or unreadable capacity goes to the API as 0, which it refuses as too low (P.127).
        val capacity = editing.capacity.toIntOrNull() ?: 0
        editor { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            val result = attempt {
                if (editing.roomId == null) {
                    source.createRoom(editing.name, capacity, editing.color)
                } else {
                    source.updateRoom(editing.roomId, editing.name, capacity, editing.color)
                }
            }
            when (result) {
                is AdminResult.Rejected -> editor { it.copy(saving = false, errors = result.messages) }
                else -> {
                    _state.update { it.copy(editor = null) }
                    load()
                }
            }
        }
    }

    fun askToRemove(room: AdminRoom) = _state.update { it.copy(removal = RoomRemoval(room)) }

    fun keepRoom() = _state.update { if (it.removal?.removing == true) it else it.copy(removal = null) }

    fun confirmRemove() {
        val removal = _state.value.removal ?: return
        if (removal.removing) return
        _state.update { it.copy(removal = removal.copy(removing = true, errors = emptyList())) }
        viewModelScope.launch {
            when (val result = attempt { source.deleteRoom(removal.room.id) }) {
                is AdminResult.Rejected -> _state.update { it.copy(removal = removal.copy(removing = false, errors = result.messages)) }
                else -> {
                    _state.update { it.copy(removal = null) }
                    load()
                }
            }
        }
    }

    private fun editor(change: (RoomEditor) -> RoomEditor) = _state.update { state -> state.editor?.let { state.copy(editor = change(it)) } ?: state }

    /** A network or server failure is shown like a refusal, in the open dialog. */
    private suspend fun attempt(action: suspend () -> AdminResult): AdminResult = try {
        action()
    } catch (expired: SessionExpiredException) {
        AdminResult.Rejected(emptyList())
    } catch (failure: ApiException) {
        AdminResult.Rejected(listOfNotNull(failure.message))
    }
}

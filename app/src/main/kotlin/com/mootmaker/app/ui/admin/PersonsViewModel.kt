package com.mootmaker.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.admin.AdminPerson
import com.mootmaker.data.admin.AdminResult
import com.mootmaker.data.api.AdminSource
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.auth.SessionExpiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The add or edit dialog. [person] is null when adding, which offers a name only. */
data class PersonEditor(
    val person: AdminPerson? = null,
    val name: String = "",
    val isAdmin: Boolean = false,
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    /**
     * Use cases Q.139 and Q.140: a guest has no sign-in account for admin access to apply to, and no
     * one may remove their own, so the switch is off rather than offering a change the API refuses.
     */
    val adminSwitchEnabled: Boolean get() = person != null && !person.isGuest && !person.isSelf
}

/** Admin access saved on the Person but not yet synced to their sign-in account; Retry resends it. */
data class SyncFailure(val personId: String, val name: String, val isAdmin: Boolean, val retrying: Boolean = false, val stillFailing: Boolean = false)

data class PersonRemoval(val person: AdminPerson, val removing: Boolean = false, val errors: List<String> = emptyList())

data class PersonsState(
    val people: List<AdminPerson> = emptyList(),
    val filter: String = "",
    val loaded: Boolean = false,
    val loadError: String? = null,
    val editor: PersonEditor? = null,
    val syncFailure: SyncFailure? = null,
    val removal: PersonRemoval? = null,
)

/** Use cases Q.133 to Q.142. */
class PersonsViewModel(private val source: AdminSource) : ViewModel() {
    private val _state = MutableStateFlow(PersonsState())
    val state: StateFlow<PersonsState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            try {
                val people = source.people()
                _state.update { it.copy(people = people, loaded = true, loadError = null) }
            } catch (expired: SessionExpiredException) {
                // Signed out meanwhile; navigation returns to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(loadError = failure.message) }
            }
        }
    }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun startAdding() = _state.update { it.copy(editor = PersonEditor()) }

    fun startEditing(person: AdminPerson) = _state.update { it.copy(editor = PersonEditor(person, person.name, person.isAdmin)) }

    fun setName(name: String) = editor { it.copy(name = name, errors = emptyList()) }

    fun setAdmin(isAdmin: Boolean) = editor { if (it.adminSwitchEnabled) it.copy(isAdmin = isAdmin, errors = emptyList()) else it }

    fun dismissErrors() = editor { it.copy(errors = emptyList()) }

    fun closeEditor() = _state.update { if (it.editor?.saving == true) it else it.copy(editor = null) }

    /**
     * Adding sends the name. Editing renames if the name changed and sets admin access if that
     * changed, reporting every refusal from either; if admin access saved but did not reach the
     * sign-in account, the edit closes and the sync prompt opens, as on the webapp.
     */
    fun save() {
        val editing = _state.value.editor ?: return
        if (editing.saving) return
        editor { it.copy(saving = true, errors = emptyList()) }
        viewModelScope.launch {
            val person = editing.person
            val results = if (person == null) {
                listOf(attempt { source.createPerson(editing.name) })
            } else {
                listOfNotNull(
                    if (editing.name != person.name) attempt { source.renamePerson(person.id, editing.name) } else null,
                    if (editing.isAdmin != person.isAdmin) attempt { source.setPersonAdmin(person.id, editing.isAdmin) } else null,
                )
            }
            val errors = results.filterIsInstance<AdminResult.Rejected>().flatMap { it.messages }
            when {
                errors.isNotEmpty() || results.any { it is AdminResult.Rejected } -> editor { it.copy(saving = false, errors = errors) }
                results.any { it == AdminResult.SyncFailed } && person != null -> _state.update {
                    it.copy(editor = null, syncFailure = SyncFailure(person.id, editing.name, editing.isAdmin))
                }
                else -> _state.update { it.copy(editor = null) }
            }
            load()
        }
    }

    fun retrySync() {
        val failure = _state.value.syncFailure ?: return
        if (failure.retrying) return
        _state.update { it.copy(syncFailure = failure.copy(retrying = true, stillFailing = false)) }
        viewModelScope.launch {
            when (attempt { source.setPersonAdmin(failure.personId, failure.isAdmin) }) {
                AdminResult.Done -> _state.update { it.copy(syncFailure = null) }
                else -> _state.update { it.copy(syncFailure = failure.copy(retrying = false, stillFailing = true)) }
            }
            load()
        }
    }

    fun dismissSyncFailure() = _state.update { if (it.syncFailure?.retrying == true) it else it.copy(syncFailure = null) }

    fun askToRemove(person: AdminPerson) = _state.update { it.copy(removal = PersonRemoval(person)) }

    fun keepPerson() = _state.update { if (it.removal?.removing == true) it else it.copy(removal = null) }

    fun confirmRemove() {
        val removal = _state.value.removal ?: return
        if (removal.removing) return
        _state.update { it.copy(removal = removal.copy(removing = true, errors = emptyList())) }
        viewModelScope.launch {
            when (val result = attempt { source.deletePerson(removal.person.id) }) {
                is AdminResult.Rejected -> _state.update { it.copy(removal = removal.copy(removing = false, errors = result.messages)) }
                else -> {
                    _state.update { it.copy(removal = null) }
                    load()
                }
            }
        }
    }

    private fun editor(change: (PersonEditor) -> PersonEditor) = _state.update { state -> state.editor?.let { state.copy(editor = change(it)) } ?: state }

    private suspend fun attempt(action: suspend () -> AdminResult): AdminResult = try {
        action()
    } catch (expired: SessionExpiredException) {
        AdminResult.Rejected(emptyList())
    } catch (failure: ApiException) {
        AdminResult.Rejected(listOfNotNull(failure.message))
    }
}

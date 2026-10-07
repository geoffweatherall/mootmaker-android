package com.mootmaker.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.api.SettingsSource
import com.mootmaker.data.auth.SessionExpiredException
import com.mootmaker.data.settings.Profile
import com.mootmaker.data.settings.SettingsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How one section's last save went: still saving, refused (with the API's messages) or confirmed. */
data class SectionStatus(
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
    val success: String? = null,
)

data class SettingsState(
    /** Null until loaded, and for an account with no linked Person (see [loaded]). */
    val profile: Profile? = null,
    val loaded: Boolean = false,
    val loadError: String? = null,
    // What the fields hold now, which may differ from [profile] until saved.
    val name: String = "",
    val dateFormat: DateFormat = DateFormat.Iso,
    val timeFormat: TimeFormat = TimeFormat.TwentyFourHour,
    val nameStatus: SectionStatus = SectionStatus(),
    val formatStatus: SectionStatus = SectionStatus(),
    val avatarStatus: SectionStatus = SectionStatus(),
)

class SettingsViewModel(private val source: SettingsSource) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun load() {
        _state.update { it.copy(loadError = null) }
        viewModelScope.launch {
            try {
                val profile = source.load()
                _state.update { it.fromProfile(profile) }
            } catch (expired: SessionExpiredException) {
                // Signed out meanwhile; navigation returns to sign-in.
            } catch (failure: ApiException) {
                _state.update { it.copy(loadError = failure.message) }
            }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name, nameStatus = SectionStatus()) }

    fun setDateFormat(format: DateFormat) = _state.update { it.copy(dateFormat = format, formatStatus = SectionStatus()) }

    fun setTimeFormat(format: TimeFormat) = _state.update { it.copy(timeFormat = format, formatStatus = SectionStatus()) }

    fun saveName() {
        val name = _state.value.name
        run(SettingsState::nameStatus, { s, v -> s.copy(nameStatus = v) }, "Your name was updated.", { s, p -> s.copy(name = p?.name ?: s.name) }) { source.updateName(name) }
    }

    fun saveFormats() {
        val current = _state.value
        val weekStart = current.profile?.weekStart ?: return
        run(SettingsState::formatStatus, { s, v -> s.copy(formatStatus = v) }, "Your date and time formats were updated.", { s, p -> s.copy(dateFormat = p?.dateFormat ?: s.dateFormat, timeFormat = p?.timeFormat ?: s.timeFormat) }) {
            source.updatePreferences(current.dateFormat, current.timeFormat, weekStart)
        }
    }

    /** [bytes] is a JPEG or PNG already within the API's size limits; the API still has the last word. */
    fun setAvatar(bytes: ByteArray, contentType: String) {
        val personId = _state.value.profile?.personId ?: return
        run(SettingsState::avatarStatus, { s, v -> s.copy(avatarStatus = v) }, "Your photo was updated.", { s, _ -> s }) { source.setAvatar(personId, bytes, contentType) }
    }

    /** The picked image could not be read or prepared, so nothing is sent. */
    fun avatarUnreadable() = _state.update { it.copy(avatarStatus = SectionStatus(errors = listOf("That image could not be read."))) }

    fun removeAvatar() {
        val personId = _state.value.profile?.personId ?: return
        run(SettingsState::avatarStatus, { s, v -> s.copy(avatarStatus = v) }, "Your photo was removed.", { s, _ -> s }) { source.removeAvatar(personId) }
    }

    private fun run(
        read: (SettingsState) -> SectionStatus,
        write: (SettingsState, SectionStatus) -> SettingsState,
        successMessage: String,
        sync: (SettingsState, Profile?) -> SettingsState,
        action: suspend () -> SettingsResult,
    ) {
        if (read(_state.value).saving) return
        _state.update { write(it, SectionStatus(saving = true)) }
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    is SettingsResult.Rejected -> _state.update { write(it, SectionStatus(errors = result.messages)) }
                    SettingsResult.Saved -> {
                        // What the server now holds. Only the saved section's fields are reset to it, so
                        // an unsaved edit in another section survives.
                        val profile = source.load()
                        _state.update { write(sync(it.copy(profile = profile), profile), SectionStatus(success = successMessage)) }
                    }
                }
            } catch (expired: SessionExpiredException) {
                _state.update { write(it, SectionStatus()) }
            } catch (failure: ApiException) {
                _state.update { write(it, SectionStatus(errors = listOfNotNull(failure.message))) }
            }
        }
    }

    private fun SettingsState.fromProfile(profile: Profile?) = copy(
        profile = profile,
        loaded = true,
        loadError = null,
        name = profile?.name ?: "",
        dateFormat = profile?.dateFormat ?: DateFormat.Iso,
        timeFormat = profile?.timeFormat ?: TimeFormat.TwentyFourHour,
    )
}

package com.mootmaker.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.auth.accountErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ForgotPasswordState(
    /** False while asking for a code, true once one has been sent. */
    val resetting: Boolean = false,
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val missing: Set<String> = emptySet(),
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Two-step password reset, as on the webapp: an email, then the emailed code with a new password.
 * Resetting signs in with the new password, and navigation away follows the session. An email with
 * no account moves to the code step exactly like one with an account (use case C.17).
 */
class ForgotPasswordViewModel(
    private val requestCode: suspend (email: String) -> Unit,
    private val reset: suspend (email: String, code: String, newPassword: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordState())
    val state: StateFlow<ForgotPasswordState> = _state.asStateFlow()

    fun onEmail(email: String) = _state.update { it.copy(email = email, missing = it.missing - EMAIL) }

    fun onCode(code: String) = _state.update { it.copy(code = code, missing = it.missing - CODE) }

    fun onNewPassword(password: String) = _state.update { it.copy(newPassword = password, missing = it.missing - NEW_PASSWORD) }

    fun submitEmail() {
        val current = _state.value
        if (current.busy) return
        if (current.email.isBlank()) return _state.update { it.copy(missing = setOf(EMAIL)) }
        launch("Failed to send a reset code.") {
            requestCode(current.email.trim())
            _state.update { it.copy(resetting = true) }
        }
    }

    fun submitReset() {
        val current = _state.value
        if (current.busy) return
        val missing = buildSet {
            if (current.code.isBlank()) add(CODE)
            if (current.newPassword.isEmpty()) add(NEW_PASSWORD)
        }
        if (missing.isNotEmpty()) return _state.update { it.copy(missing = missing) }
        launch("Password reset failed.") {
            reset(current.email.trim(), current.code.trim(), current.newPassword)
            _state.update { it.copy(newPassword = "", code = "") }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun launch(fallback: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(busy = false) }
            } catch (failure: Exception) {
                _state.update { it.copy(busy = false, error = accountErrorMessage(failure, fallback)) }
            }
        }
    }

    companion object {
        const val EMAIL = "Email"
        const val CODE = "Verification code"
        const val NEW_PASSWORD = "New password"
    }
}

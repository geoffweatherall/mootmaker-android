package com.mootmaker.app.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.auth.accountErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignUpState(
    /** False on the details step, true once Cognito has emailed a code. */
    val confirming: Boolean = false,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val code: String = "",
    /** A field left empty, by field label. Everything else is Cognito's to judge. */
    val missing: Set<String> = emptySet(),
    /** A failure from Cognito or the network, shown above the form. */
    val error: String? = null,
    val busy: Boolean = false,
)

/**
 * Two-step sign-up, as on the webapp: name, email and password, then the emailed code. Confirming
 * signs in, and navigation away follows the session. The password rule is Cognito's alone: like the
 * webapp, nothing here checks it, so the helper text is a hint and Cognito's message is the verdict
 * (use case A.2).
 */
class SignUpViewModel(
    private val signUp: suspend (email: String, password: String, name: String) -> Unit,
    private val confirm: suspend (email: String, code: String, password: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpState())
    val state: StateFlow<SignUpState> = _state.asStateFlow()

    fun onName(name: String) = _state.update { it.copy(name = name, missing = it.missing - NAME) }

    fun onEmail(email: String) = _state.update { it.copy(email = email, missing = it.missing - EMAIL) }

    fun onPassword(password: String) = _state.update { it.copy(password = password, missing = it.missing - PASSWORD) }

    fun onCode(code: String) = _state.update { it.copy(code = code, missing = it.missing - CODE) }

    fun submitDetails() {
        val current = _state.value
        if (current.busy) return
        val missing = buildSet {
            if (current.name.isBlank()) add(NAME)
            if (current.email.isBlank()) add(EMAIL)
            if (current.password.isEmpty()) add(PASSWORD)
        }
        if (missing.isNotEmpty()) return _state.update { it.copy(missing = missing) }
        launch("Sign up failed.") {
            signUp(current.email.trim(), current.password, current.name.trim())
            _state.update { it.copy(confirming = true) }
        }
    }

    fun submitCode() {
        val current = _state.value
        if (current.busy) return
        if (current.code.isBlank()) return _state.update { it.copy(missing = setOf(CODE)) }
        launch("Confirmation failed.") {
            confirm(current.email.trim(), current.code.trim(), current.password)
            // Signed in: the passwords are no longer needed in memory.
            _state.update { it.copy(password = "", code = "") }
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
        const val NAME = "Name"
        const val EMAIL = "Email"
        const val PASSWORD = "Password"
        const val CODE = "Verification code"
    }
}

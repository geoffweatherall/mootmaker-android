package com.mootmaker.app.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mootmaker.data.auth.signInErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    /** A failure from Cognito or the network, shown above the form. */
    val error: String? = null,
    val signingIn: Boolean = false,
)

/** Sign-in form state. Navigation away on success follows the session, not this ViewModel. */
class SignInViewModel(
    private val signIn: suspend (email: String, password: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var prefilled = false

    /** Pre-fills the publicly known demo user's credentials, once, as the webapp does. */
    fun prefill(email: String?, password: String?) {
        if (prefilled || email.isNullOrBlank() || password.isNullOrBlank()) return
        prefilled = true
        _state.update { if (it.email.isEmpty() && it.password.isEmpty()) it.copy(email = email, password = password) else it }
    }

    fun onEmailChange(email: String) = _state.update { it.copy(email = email, emailError = null) }

    fun onPasswordChange(password: String) = _state.update { it.copy(password = password, passwordError = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun submit() {
        val current = _state.value
        if (current.signingIn) return
        val email = current.email.trim()
        val emailError = when {
            email.isEmpty() -> "Enter your email address."
            !email.contains('@') -> "Enter a valid email address."
            else -> null
        }
        val passwordError = if (current.password.isEmpty()) "Enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            try {
                signIn(email, current.password)
                // Clears the password from memory now that it's no longer needed.
                _state.update { it.copy(signingIn = false, password = "") }
            } catch (failure: Exception) {
                _state.update { it.copy(signingIn = false, error = signInErrorMessage(failure)) }
            }
        }
    }
}

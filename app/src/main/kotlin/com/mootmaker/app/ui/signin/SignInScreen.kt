package com.mootmaker.app.ui.signin

import com.mootmaker.app.ui.ErrorBanner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** What the screen knows about the app's configuration for the chosen environment. */
sealed interface SignInConfig {
    data object Loading : SignInConfig
    data class Ready(val hasDemoUser: Boolean) : SignInConfig
    data class Failed(val environmentName: String) : SignInConfig
}

@Composable
fun SignInScreen(
    state: SignInState,
    config: SignInConfig,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetryConfig: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onAbout: () -> Unit,
    sessionExpired: Boolean = false,
    onDismissError: () -> Unit = {},
    onCodeChange: (String) -> Unit = {},
    onConfirm: () -> Unit = {},
    onResendCode: () -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
        ErrorBanner(listOfNotNull(state.error), onDismissError)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Text("Mootmaker", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Sign in", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                when (config) {
                    is SignInConfig.Failed -> ConfigFailed(config.environmentName, onRetryConfig)
                    else -> if (state.unconfirmed) {
                        ConfirmForm(state, onCodeChange, onConfirm, onResendCode)
                    } else Form(state, config, sessionExpired, onEmailChange, onPasswordChange, onSubmit)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("No account yet?", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCreateAccount) { Text("Create an account") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onForgotPassword) { Text("Forgot password?") }
                    TextButton(onClick = onAbout) { Text("About") }
                }
            }
        }
        }
    }
}

@Composable
private fun Form(
    state: SignInState,
    config: SignInConfig,
    sessionExpired: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sessionExpired) {
                Text(
                    "Your session has expired. Sign in again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (config is SignInConfig.Ready && config.hasDemoUser) {
                Text(
                    "The demo account is filled in. Tap Sign in to look around.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                isError = state.emailError != null,
                supportingText = state.emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSubmit,
                enabled = config is SignInConfig.Ready && !state.signingIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.signingIn || config is SignInConfig.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Sign in")
                }
            }
        }
    }
}

/**
 * The account's sign-up was never confirmed: ask for the emailed code, or send a new one, then sign
 * in with the password already entered (use case 150).
 */
@Composable
private fun ConfirmForm(
    state: SignInState,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onResendCode: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "This account hasn't been confirmed yet. Enter the verification code we emailed to ${state.email.trim()}, or send a new one.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.codeSent) {
                Text(
                    "We've sent a new code. It can take a minute to arrive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text("Verification code") },
                singleLine = true,
                isError = state.codeError != null,
                supportingText = state.codeError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onConfirm, enabled = !state.signingIn, modifier = Modifier.fillMaxWidth()) {
                if (state.signingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Confirm")
                }
            }
            TextButton(onClick = onResendCode, enabled = !state.sendingCode) { Text("Send a new code") }
        }
    }
}

@Composable
private fun ConfigFailed(environmentName: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Couldn't load Mootmaker's settings for $environmentName. Check your connection and try again.",
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

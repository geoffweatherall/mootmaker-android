package com.mootmaker.app.ui.account

import com.mootmaker.app.ui.ErrorBanner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** The password rule, as the webapp words its hint. Cognito enforces it; the app only describes it. */
const val PASSWORD_HINT = "At least 10 characters, with a lowercase letter and a number."

data class SignUpActions(
    val onName: (String) -> Unit,
    val onEmail: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onSubmitDetails: () -> Unit,
    val onSubmitCode: () -> Unit,
    val onSignIn: () -> Unit,
    val onDismissError: () -> Unit = {},
)

/** Use cases A.1 to A.4: the details step, then the emailed code. */
@Composable
fun SignUpScreen(state: SignUpState, actions: SignUpActions) {
    AccountPage("Sign up", state.error, actions.onDismissError) {
        if (!state.confirming) {
            FormCard {
                TextInput("Name", state.name, actions.onName, state.missing, capitalization = KeyboardCapitalization.Words)
                TextInput("Email", state.email, actions.onEmail, state.missing, keyboardType = KeyboardType.Email)
                TextInput(
                    "Password",
                    state.password,
                    actions.onPassword,
                    state.missing,
                    keyboardType = KeyboardType.Password,
                    hint = PASSWORD_HINT,
                    onDone = actions.onSubmitDetails,
                )
                SubmitButton("Sign up", state.busy, actions.onSubmitDetails)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Already have an account?", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onSignIn) { Text("Sign in") }
            }
        } else {
            FormCard {
                SentTo(state.email.trim(), "Enter it below to finish creating your account.")
                TextInput("Verification code", state.code, actions.onCode, state.missing, keyboardType = KeyboardType.Number, onDone = actions.onSubmitCode)
                SubmitButton("Confirm", state.busy, actions.onSubmitCode)
            }
        }
    }
}

data class ForgotPasswordActions(
    val onEmail: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onNewPassword: (String) -> Unit,
    val onSubmitEmail: () -> Unit,
    val onSubmitReset: () -> Unit,
    val onSignIn: () -> Unit,
    val onDismissError: () -> Unit = {},
)

/** Use cases C.16 to C.20: ask for a code, then the code with a new password. */
@Composable
fun ForgotPasswordScreen(state: ForgotPasswordState, actions: ForgotPasswordActions) {
    AccountPage("Reset password", state.error, actions.onDismissError) {
        if (!state.resetting) {
            FormCard {
                Text(
                    "Enter your email address and we will send you a verification code to reset your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextInput("Email", state.email, actions.onEmail, state.missing, keyboardType = KeyboardType.Email, onDone = actions.onSubmitEmail)
                SubmitButton("Send code", state.busy, actions.onSubmitEmail)
            }
        } else {
            FormCard {
                SentTo(state.email.trim(), "Enter it below with your new password.")
                TextInput("Verification code", state.code, actions.onCode, state.missing, keyboardType = KeyboardType.Number)
                TextInput(
                    "New password",
                    state.newPassword,
                    actions.onNewPassword,
                    state.missing,
                    keyboardType = KeyboardType.Password,
                    hint = PASSWORD_HINT,
                    onDone = actions.onSubmitReset,
                )
                SubmitButton("Reset password", state.busy, actions.onSubmitReset)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remembered it?", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = actions.onSignIn) { Text("Sign in") }
        }
    }
}

/** The sign-in screen's frame: the wordmark, a heading, and any failure above the form. */
@Composable
private fun AccountPage(title: String, error: String?, onDismissError: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            ErrorBanner(listOfNotNull(error), onDismissError)
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
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                content()
            }
            }
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SentTo(email: String, then: String) {
    Text(
        buildAnnotatedString {
            append("We sent a verification code to ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(email) }
            append(". $then")
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** A labelled field. A field left empty says so under itself; [hint] shows there otherwise. */
@Composable
private fun TextInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    missing: Set<String>,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    hint: String? = null,
    onDone: (() -> Unit)? = null,
) {
    val isMissing = label in missing
    val supporting = if (isMissing) "Enter your ${label.lowercase()}." else hint
    val password = keyboardType == KeyboardType.Password
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isMissing,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(text)
    }
}

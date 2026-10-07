package com.mootmaker.data.auth

import java.io.IOException

/**
 * The message the sign-in screen shows for a failure. Cognito's own messages are shown as they
 * are, as the webapp does: with prevent_user_existence_errors enabled on the pool, a wrong password
 * and an unknown email both read "Incorrect username or password." (use cases B.9 and B.10).
 */
fun signInErrorMessage(error: Throwable): String = when (error) {
    is CognitoException -> when (error.type) {
        "TooManyRequestsException", "LimitExceededException" -> "Too many attempts. Wait a moment and try again."
        "UserNotConfirmedException" -> "This account hasn't been confirmed yet. Finish signing up on the web first."
        else -> error.message
    }
    is IOException -> "Couldn't reach Mootmaker. Check your connection and try again."
    else -> error.message ?: "Sign in failed."
}

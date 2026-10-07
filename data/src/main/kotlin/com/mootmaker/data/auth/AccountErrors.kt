package com.mootmaker.data.auth

import java.io.IOException

/**
 * The message the sign-up and reset-password screens show for a failure. Cognito's own messages are
 * shown as they are, as the webapp does (a weak password, an email that already has an account, a
 * wrong code), except the wrapper Cognito puts around a PreSignUp trigger's rejection.
 */
fun accountErrorMessage(error: Throwable, fallback: String): String = when (error) {
    is CognitoException -> when (error.type) {
        "TooManyRequestsException", "LimitExceededException" -> "Too many attempts. Wait a moment and try again."
        "UserLambdaValidationException" -> preSignUpRejection(error.message) ?: error.message
        else -> error.message
    }
    is IOException -> "Couldn't reach Mootmaker. Check your connection and try again."
    else -> error.message ?: fallback
}

private val PRE_SIGN_UP_REJECTION = Regex("^PreSignUp failed with error (.+)$")

/**
 * Cognito wraps a PreSignUp trigger's message as `PreSignUp failed with error <message>.`. The
 * trigger (mootmaker-api's PreSignUpNameCollisionHandler) writes a sentence for a person to read, so
 * this keeps only that, with the doubled full stop collapsed. The webapp's readableSignUpError does
 * the same. Null when the message isn't in that shape, so it is shown as it came.
 */
internal fun preSignUpRejection(message: String): String? =
    PRE_SIGN_UP_REJECTION.find(message)?.groupValues?.get(1)?.replace(Regex("\\.+$"), ".")

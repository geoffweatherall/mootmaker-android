package com.mootmaker.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Cases ported from the webapp's signUpErrors.test.ts, so both frontends word refusals the same. */
class AccountErrorsTest {
    @Test
    fun cognitosOwnMessageIsShownAsItIs() {
        val error = CognitoException("UsernameExistsException", "An account with the given email already exists.")
        assertEquals("An account with the given email already exists.", accountErrorMessage(error, "Sign up failed."))
    }

    @Test
    fun aPreSignUpRejectionLosesCognitosWrapperAndTheDoubledFullStop() {
        val error = CognitoException(
            "UserLambdaValidationException",
            "PreSignUp failed with error Someone called Pat already has an account. Add something to tell you apart..",
        )
        assertEquals("Someone called Pat already has an account. Add something to tell you apart.", accountErrorMessage(error, "Sign up failed."))
    }

    @Test
    fun anotherLambdaRejectionIsShownAsItCame() {
        val error = CognitoException("UserLambdaValidationException", "PostConfirmation failed with error boom.")
        assertEquals("PostConfirmation failed with error boom.", accountErrorMessage(error, "Sign up failed."))
    }

    @Test
    fun rateLimitingAndNetworkFailuresAreWordedForPeople() {
        assertEquals("Too many attempts. Wait a moment and try again.", accountErrorMessage(CognitoException("LimitExceededException", "Attempt limit exceeded"), "x"))
        assertEquals("Couldn't reach Mootmaker. Check your connection and try again.", accountErrorMessage(IOException("down"), "x"))
    }

    @Test
    fun anythingElseFallsBack() {
        assertEquals("Sign up failed.", accountErrorMessage(IllegalStateException(), "Sign up failed."))
    }
}

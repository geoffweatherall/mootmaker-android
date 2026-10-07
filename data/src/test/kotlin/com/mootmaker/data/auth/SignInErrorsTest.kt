package com.mootmaker.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SignInErrorsTest {
    @Test
    fun cognitosOwnMessageIsShownForBadCredentials() {
        assertEquals(
            "Incorrect username or password.",
            signInErrorMessage(CognitoException("NotAuthorizedException", "Incorrect username or password.")),
        )
    }

    @Test
    fun throttlingGetsAPlainMessage() {
        assertEquals(
            "Too many attempts. Wait a moment and try again.",
            signInErrorMessage(CognitoException("TooManyRequestsException", "Rate exceeded")),
        )
    }

    @Test
    fun networkFailureSaysSo() {
        assertEquals(
            "Couldn't reach Mootmaker. Check your connection and try again.",
            signInErrorMessage(IOException("Unable to resolve host")),
        )
    }
}

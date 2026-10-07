package com.mootmaker.data.auth

import com.mootmaker.data.config.ConfigRepository
import com.mootmaker.data.config.Environment
import com.mootmaker.data.config.MobileConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

/** Where the app's configuration stands for the chosen environment. */
sealed interface ConfigState {
    val environment: Environment

    data class Loading(override val environment: Environment) : ConfigState
    data class Ready(override val environment: Environment, val config: MobileConfig) : ConfigState
    data class Failed(override val environment: Environment, val cause: Throwable) : ConfigState
}

sealed interface SessionState {
    data object Starting : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val claims: IdTokenClaims) : SessionState
}

/** Thrown when the stored session can no longer be refreshed; the app is now signed out. */
class SessionExpiredException : Exception("Your session has expired. Sign in again.")

/**
 * The signed-in session: configuration, sign-in and sign-out, and a fresh id token on demand.
 *
 * When a refresh is refused (the pool was recreated, or the refresh token expired), the session
 * signs out cleanly rather than looping or crashing, which matters because ephemeral and test
 * pools come and go (design: Token storage).
 */
class Session(
    private val configRepository: ConfigRepository,
    private val tokenStore: TokenStore,
    private val cognitoFor: (MobileConfig) -> CognitoClient,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _config = MutableStateFlow<ConfigState>(ConfigState.Loading(Environment.PRODUCTION))
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val tokenLock = Mutex()
    private var tokens: Tokens? = null

    /** Loads the environment, its config and any stored tokens. Call once at app start. */
    suspend fun start() {
        val environment = configRepository.environment()
        val cached = configRepository.cached(environment)
        if (cached != null) {
            _config.value = ConfigState.Ready(environment, cached)
            // Refresh for next time without making this start wait on the network.
            scope.launch { runCatching { configRepository.fetch(environment) } }
        } else {
            loadConfig(environment)
        }
        val stored = tokenStore.load()
        tokens = stored
        _state.value = if (stored != null) SessionState.SignedIn(IdTokenClaims.parse(stored.idToken)) else SessionState.SignedOut
    }

    suspend fun retryConfig() = loadConfig(_config.value.environment)

    private suspend fun loadConfig(environment: Environment) {
        _config.value = ConfigState.Loading(environment)
        _config.value = runCatching { configRepository.fetch(environment) }.fold(
            onSuccess = { ConfigState.Ready(environment, it) },
            onFailure = { ConfigState.Failed(environment, it) },
        )
    }

    /** Signs in, or throws [CognitoException] or an IOException. */
    suspend fun signIn(email: String, password: String) {
        val config = (config.value as? ConfigState.Ready)?.config
            ?: throw IllegalStateException("The app's settings haven't loaded yet.")
        val signedIn = cognitoFor(config).signIn(email, password)
        tokenLock.withLock {
            tokens = signedIn
            tokenStore.save(signedIn)
        }
        _state.value = SessionState.SignedIn(IdTokenClaims.parse(signedIn.idToken))
    }

    suspend fun signOut() {
        val previous = tokenLock.withLock {
            val held = tokens
            tokens = null
            tokenStore.clear()
            held
        }
        _state.value = SessionState.SignedOut
        val config = (config.value as? ConfigState.Ready)?.config
        if (previous != null && config != null) {
            scope.launch { runCatching { cognitoFor(config).revoke(previous.refreshToken) } }
        }
    }

    /** Switching environment signs out: tokens belong to one environment's user pool. */
    suspend fun switchEnvironment(environment: Environment) {
        signOut()
        configRepository.setEnvironment(environment)
        loadConfig(environment)
    }

    /**
     * The id token AppSync expects in the Authorization header, refreshed when it is within a few
     * minutes of expiring. Throws [SessionExpiredException] (and signs out) if Cognito refuses the
     * refresh; a network failure is thrown as-is and leaves the session alone.
     */
    suspend fun idToken(): String {
        val refreshed = tokenLock.withLock {
            val current = tokens ?: throw SessionExpiredException()
            val claims = IdTokenClaims.parse(current.idToken)
            if (Duration.between(clock.instant(), claims.expiresAt) > REFRESH_MARGIN) return current.idToken
            val config = (config.value as? ConfigState.Ready)?.config ?: throw SessionExpiredException()
            try {
                cognitoFor(config).refresh(current.refreshToken).also {
                    tokens = it
                    tokenStore.save(it)
                }
            } catch (refused: CognitoException) {
                null
            }
        }
        if (refreshed == null) {
            signOut()
            throw SessionExpiredException()
        }
        _state.value = SessionState.SignedIn(IdTokenClaims.parse(refreshed.idToken))
        return refreshed.idToken
    }

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
    }
}

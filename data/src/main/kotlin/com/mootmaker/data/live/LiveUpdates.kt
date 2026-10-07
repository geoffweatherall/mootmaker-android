package com.mootmaker.data.live

import kotlinx.coroutines.flow.Flow

/**
 * What the realtime channel tells the app. Both mean the same thing to a screen, "what you hold may
 * be stale, fetch it again", and differ only in why.
 */
sealed interface LiveEvent {
    /** Another client changed these dates. The broadcast carries dates, never data. */
    data class DaysChanged(val dates: List<String>) : LiveEvent

    /**
     * The subscription is live. Anything published before this point was never delivered and AppSync
     * does not replay, so whatever is on screen is suspect: this is the Android form of the webapp's
     * `onResubscribed`, sent on the first connection too because a screen refetches when it
     * resumes, a moment before the socket is up.
     */
    data object Subscribed : LiveEvent
}

/** The source of [LiveEvent]s. A real one talks to AppSync; tests pass a fake. */
fun interface LiveUpdates {
    /**
     * Connects when collected and keeps reconnecting until the collector is cancelled, so cancelling
     * is how the app lets go of the socket when it leaves the foreground.
     */
    fun events(): Flow<LiveEvent>
}

/** For a container that never goes online. */
object NoLiveUpdates : LiveUpdates {
    override fun events(): Flow<LiveEvent> = kotlinx.coroutines.flow.emptyFlow()
}

package com.mootmaker.data.cache

/**
 * What a screen draws from the store: [data] once everything it needs is known, kept while anything
 * it shows is [fetching] again; and the last failure, if any. Data null and fetching is the full
 * spinner; data present and fetching is the slim bar (use case M.92).
 */
data class Loaded<out T>(val data: T?, val fetching: Boolean, val error: Throwable?)

/** A failure worded for the screen: the API's own words, or the network message. */
fun Throwable.screenMessage(): String = when (this) {
    is com.mootmaker.data.api.ApiException -> message.orEmpty()
    is java.io.IOException -> com.mootmaker.data.api.HomeRepository.NETWORK_MESSAGE
    else -> message ?: "Something went wrong."
}

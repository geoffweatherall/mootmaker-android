package com.mootmaker.data.api

import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.exception.ApolloNetworkException
import java.io.IOException

/**
 * Runs [operation] as the signed-in user and returns its data, or throws an [ApiException] worded for
 * the screen: GraphQL errors as the API words them, a network failure as [HomeRepository.NETWORK_MESSAGE],
 * anything else as [fallback].
 */
internal suspend fun <D : Query.Data> ApolloClient.call(operation: Query<D>, idToken: String, fallback: String): D =
    query(operation).run(idToken, fallback)

/** [call] for a mutation. A rejected write is data (an `errors` field), not an exception. */
internal suspend fun <D : Mutation.Data> ApolloClient.send(operation: Mutation<D>, idToken: String, fallback: String): D =
    mutation(operation).run(idToken, fallback)

private suspend fun <D : Operation.Data> ApolloCall<D>.run(idToken: String, fallback: String): D {
    val response = try {
        addHttpHeader("Authorization", idToken).execute()
    } catch (network: IOException) {
        throw ApiException(HomeRepository.NETWORK_MESSAGE, network)
    }
    response.data?.let { return it }
    val messages = response.errors?.map { it.message }.orEmpty()
    throw when {
        messages.isNotEmpty() -> ApiException(messages.joinToString("\n"))
        response.exception is ApolloNetworkException -> ApiException(HomeRepository.NETWORK_MESSAGE, response.exception)
        else -> ApiException(response.exception?.message ?: fallback, response.exception)
    }
}

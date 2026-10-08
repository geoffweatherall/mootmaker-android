package com.mootmaker.app.ui

import com.mootmaker.data.cache.CachedDay
import com.mootmaker.data.cache.CachedMeeting
import com.mootmaker.data.cache.Reference
import com.mootmaker.data.cache.WorkspaceApi
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate

/**
 * A [WorkspaceApi] for ViewModel tests: it records every request and answers from [meetings].
 * Answers come at once, or, with [gated] set, only when the test calls [release], so a test can
 * look at the state between a request and its answer.
 */
class FakeWorkspaceApi(private val reference: Reference) : WorkspaceApi {
    /** The meetings the server holds, by the date they start on. */
    val meetings = mutableMapOf<LocalDate, List<CachedMeeting>>()

    val referenceCalls = mutableListOf<Unit>()
    val dayCalls = mutableListOf<List<LocalDate>>()
    val meetingCalls = mutableListOf<String>()

    /** When set, every request fails with it. */
    var failure: Exception? = null

    /** When true, days requests wait for [release]. */
    var gated = false

    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var released = 0

    /** Answers every days request still waiting. */
    fun release() {
        while (released < gates.size) gates[released++].complete(Unit)
    }

    override suspend fun reference(): Reference {
        referenceCalls += Unit
        failure?.let { throw it }
        return reference
    }

    override suspend fun days(dates: List<LocalDate>): List<CachedDay> {
        dayCalls += dates
        if (gated) CompletableDeferred<Unit>().also { gates += it }.await()
        failure?.let { throw it }
        return dates.map { CachedDay(it, meetings[it].orEmpty()) }
    }

    override suspend fun meeting(id: String): CachedMeeting? {
        meetingCalls += id
        failure?.let { throw it }
        return meetings.values.flatten().firstOrNull { it.id == id }
    }
}

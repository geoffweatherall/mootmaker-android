package com.mootmaker.data.cache

import com.apollographql.apollo.ApolloClient
import com.mootmaker.data.agenda.DateFormat
import com.mootmaker.data.agenda.RoomColor
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.api.ApiException
import com.mootmaker.data.meeting.AttendeeStatus
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ApolloWorkspaceApi]'s mapping, against canned responses from a MockWebServer. */
class ApolloWorkspaceApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = ApolloWorkspaceApi(
        apollo = { ApolloClient.Builder().serverUrl(server.url("/graphql").toString()).build() },
        idToken = { "token" },
    )

    @After
    fun tearDown() = server.shutdown()

    private fun respond(json: String) = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody(json),
    )

    private fun referenceJson(me: String, timeFormat: String = "AmPm", dateFormat: String = "Usa") = """
        {"data":{"workspace":{
          "me":${if (me == "null") "null" else """{"id":"p1","name":"Pat","avatarUrl":"https://a/p1.jpg","timeFormat":"$timeFormat","dateFormat":"$dateFormat"}"""},
          "people":[{"id":"p1","name":"Pat","avatarUrl":"https://a/p1.jpg"},{"id":"p2","name":"Sam","avatarUrl":null}],
          "rooms":[{"id":"r1","name":"Atlas","capacity":8,"color":"Green"},{"id":"r2","name":"Birch","capacity":4,"color":null}],
          "boundaries":{"earliestRetainedDate":"2026-09-28","latestBookableDate":"2027-03-26"}
        }}}
    """.trimIndent()

    @Test
    fun referenceMapsMePeopleRoomsAndBoundsInTheAmPmAndUsaFormats() = runBlocking {
        respond(referenceJson("me"))
        val reference = api.reference()
        assertEquals(Me("p1", "Pat", "https://a/p1.jpg", TimeFormat.AmPm, DateFormat.Usa), reference.me)
        assertEquals(listOf(CachedPerson("p1", "Pat", "https://a/p1.jpg"), CachedPerson("p2", "Sam", null)), reference.people)
        assertEquals(
            listOf(CachedRoom("r1", "Atlas", 8, RoomColor.Green), CachedRoom("r2", "Birch", 4, null)),
            reference.rooms,
        )
        assertEquals(LocalDate.of(2026, 9, 28), reference.bounds!!.earliest)
        assertEquals(LocalDate.of(2027, 3, 26), reference.bounds!!.latest)
        assertEquals("token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun referenceMapsTwentyFourHourAndBritish() = runBlocking {
        respond(referenceJson("me", timeFormat = "TwentyFourHour", dateFormat = "British"))
        val me = api.reference().me!!
        assertEquals(TimeFormat.TwentyFourHour, me.timeFormat)
        assertEquals(DateFormat.British, me.dateFormat)
    }

    @Test
    fun anAccountWithNoPersonHasNullMe() = runBlocking {
        respond(referenceJson("null"))
        val reference = api.reference()
        assertNull(reference.me)
        assertEquals(2, reference.people.size)
    }

    @Test
    fun daysMapsEveryFieldAndKeepsTimesAsTheApiSentThem() = runBlocking {
        respond(
            """
            {"data":{"workspace":{"days":[
              {"date":"2026-10-07","meetings":[{
                "id":"m1","subject":"Planning","startTime":"2026-10-07T09:00:00","endTime":"2026-10-07T10:30:00","version":"v7",
                "room":{"id":"r1"},"organiser":{"id":"p1"},
                "attendees":[{"person":{"id":"p2"},"status":"Going"},{"person":{"id":"p3"},"status":"NotGoing"}]
              }]},
              {"date":"2026-10-08","meetings":[]}
            ]}}}
            """.trimIndent(),
        )
        val days = api.days(listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8)))
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2026, 10, 7), days[0].date)
        assertEquals(
            CachedMeeting(
                id = "m1",
                subject = "Planning",
                startTime = "2026-10-07T09:00:00",
                endTime = "2026-10-07T10:30:00",
                roomId = "r1",
                organiserId = "p1",
                attendees = listOf(CachedAttendee("p2", AttendeeStatus.Going), CachedAttendee("p3", AttendeeStatus.NotGoing)),
                version = "v7",
            ),
            days[0].meetings.single(),
        )
        assertEquals(LocalDate.of(2026, 10, 7), days[0].meetings.single().date)
        assertEquals(CachedDay(LocalDate.of(2026, 10, 8), emptyList()), days[1])
    }

    @Test
    fun anUnknownAttendeeStatusReadsAsNoResponse() = runBlocking {
        respond(
            """
            {"data":{"workspace":{"days":[{"date":"2026-10-07","meetings":[{
              "id":"m1","subject":"S","startTime":"2026-10-07T09:00:00","endTime":"2026-10-07T10:00:00","version":"v1",
              "room":{"id":"r1"},"organiser":{"id":"p1"},"attendees":[{"person":{"id":"p2"},"status":"Tentative"}]
            }]}]}}}
            """.trimIndent(),
        )
        val meeting = api.days(listOf(LocalDate.of(2026, 10, 7))).single().meetings.single()
        assertEquals(AttendeeStatus.NoResponse, meeting.attendees.single().status)
    }

    @Test
    fun daysSendsTheDatesItWasGiven() = runBlocking {
        respond("""{"data":{"workspace":{"days":[]}}}""")
        api.days(listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 9)))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"2026-10-07\"") && body.contains("\"2026-10-09\""))
        assertTrue(body, body.indexOf("2026-10-07") < body.indexOf("2026-10-09"))
    }

    @Test
    fun meetingByIdMapsTheMeeting() = runBlocking {
        respond(
            """
            {"data":{"meeting":{
              "id":"m9","subject":"Retro","startTime":"2026-10-09T15:00:00","endTime":"2026-10-09T16:00:00","version":"v2",
              "room":{"id":"r2"},"organiser":{"id":"p2"},"attendees":[{"person":{"id":"p1"},"status":"Maybe"}]
            }}}
            """.trimIndent(),
        )
        val meeting = api.meeting("m9")!!
        assertEquals("Retro", meeting.subject)
        assertEquals("2026-10-09T15:00:00", meeting.startTime)
        assertEquals("r2", meeting.roomId)
        assertEquals(listOf(CachedAttendee("p1", AttendeeStatus.Maybe)), meeting.attendees)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"m9\""))
    }

    @Test
    fun meetingByIdIsNullWhenTheApiReturnsNull() = runBlocking {
        respond("""{"data":{"meeting":null}}""")
        assertNull(api.meeting("gone"))
    }

    @Test
    fun aGraphQlErrorBecomesAnApiExceptionWithTheApisMessage() {
        respond("""{"data":null,"errors":[{"message":"Too many dates."}]}""")
        try {
            runBlocking { api.days(listOf(LocalDate.of(2026, 10, 7))) }
            fail("expected an ApiException")
        } catch (e: ApiException) {
            assertEquals("Too many dates.", e.message)
        }
    }
}

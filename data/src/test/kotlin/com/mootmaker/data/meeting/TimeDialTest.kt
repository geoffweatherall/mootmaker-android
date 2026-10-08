package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

// The time dial's corrections (issue #28, option 2): whatever the dial reads, the form books a quarter hour.
class TimeDialTest {
    @Test
    fun everyMinuteGoesToTheNearestQuarter() {
        val expected = (0..59).associateWith { minute ->
            when (minute) {
                in 0..7 -> 0
                in 8..22 -> 15
                in 23..37 -> 30
                in 38..52 -> 45
                else -> 0 // 53 to 59: the top of the same hour
            }
        }
        for (minute in 0..59) assertEquals("minute $minute", expected[minute], nearestQuarterMinute(minute))
    }

    @Test
    fun theNearestQuarterIsNeverMoreThanSevenAndAHalfMinutesAway() {
        for (minute in 0..59) {
            val quarter = nearestQuarterMinute(minute)
            val distance = minOf(Math.floorMod(minute - quarter, 60), Math.floorMod(quarter - minute, 60))
            assertTrue("minute $minute went to $quarter", distance <= 7)
        }
    }

    @Test
    fun aQuarterStaysWhereItIs() {
        listOf(0, 15, 30, 45).forEach { assertEquals(it, nearestQuarterMinute(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aMinuteOffTheClockIsRefused() {
        nearestQuarterMinute(60)
    }

    // The hour never changes, so a correction can't move a meeting into the next hour or past midnight.
    @Test
    fun theDialTimeKeepsItsHourForEveryHourAndMinute() {
        for (hour in 0..23) {
            for (minute in 0..59) {
                val time = dialTime(hour, minute)
                assertEquals("$hour:$minute", hour, time.hour)
                assertEquals("$hour:$minute", nearestQuarterMinute(minute), time.minute)
                assertTrue("$hour:$minute -> $time", time in quarterHours)
            }
        }
        assertEquals(LocalTime.of(23, 0), dialTime(23, 58))
        assertEquals(LocalTime.of(9, 15), dialTime(9, 10))
        assertEquals(LocalTime.of(9, 0), dialTime(9, 5))
    }

    // N.103: the dial follows the time format the form shows times in.
    @Test
    fun theDialFollowsTheTimeFormat() {
        assertTrue(dialIs24Hour(TimeFormat.TwentyFourHour))
        assertFalse(dialIs24Hour(TimeFormat.AmPm))
    }

    @Test
    fun twelveHourClockHours() {
        assertEquals(12, twelveHourClockHour(0))
        assertEquals(1, twelveHourClockHour(1))
        assertEquals(11, twelveHourClockHour(11))
        assertEquals(12, twelveHourClockHour(12))
        assertEquals(2, twelveHourClockHour(14))
        assertEquals(11, twelveHourClockHour(23))
    }

    @Test
    fun twelveHourClockRoundTripsForEveryHour() {
        for (hour in 0..23) assertEquals("hour $hour", hour, hourOfDay(twelveHourClockHour(hour), afternoon = hour >= 12))
        assertEquals(0, hourOfDay(12, afternoon = false))
        assertEquals(12, hourOfDay(12, afternoon = true))
        assertEquals(14, hourOfDay(2, afternoon = true))
        assertEquals(2, hourOfDay(2, afternoon = false))
    }
}

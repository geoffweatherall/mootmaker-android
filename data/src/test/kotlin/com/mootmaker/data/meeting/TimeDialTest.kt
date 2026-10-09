package com.mootmaker.data.meeting

import com.mootmaker.data.agenda.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// The "Select time" dialog's arithmetic (issue #28, option 3): rounding typed minutes, 12/24-hour
// conversion and the dial's geometry, checked for every minute, hour and whole degree, not a sample.
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

    // --- Geometry ---------------------------------------------------------------------------------

    private val everyDegree = (0 until 360).map { it.toDouble() }

    /** How far apart two angles are around the circle, 0 to 180 degrees. */
    private fun apart(a: Double, b: Double): Double = normalisedDegrees(a - b).let { minOf(it, 360 - it) }

    /**
     * The value on [ring] whose label is nearest [angle], worked out by brute force rather than the way
     * the dial does it: a tie (exactly between two labels) goes to the clockwise one.
     */
    private fun nearestByBruteForce(angle: Double, ring: DialRing): Int {
        val best = ring.values.minOf { apart(angle, labelAngle(it, ring)) }
        val tied = ring.values.filter { abs(apart(angle, labelAngle(it, ring)) - best) < 1e-9 }
        return tied.singleOrNull() ?: tied.first { normalisedDegrees(labelAngle(it, ring) - angle) < 180 }
    }

    @Test
    fun everyWholeDegreeGivesTheNearestQuarter() {
        for (degree in everyDegree) {
            val expected = when {
                degree >= 315 || degree < 45 -> 0
                degree < 135 -> 15
                degree < 225 -> 30
                else -> 45
            }
            assertEquals("$degree degrees", expected, quarterForAngle(degree))
            assertEquals("$degree degrees", nearestByBruteForce(degree, DialRing.Minute), quarterForAngle(degree))
        }
    }

    @Test
    fun justLeftOfTwelveIsTheTopOfTheHour() {
        assertEquals(0, quarterForAngle(359.0))
        assertEquals(0, quarterForAngle(359.999))
        assertEquals(0, quarterForAngle(-1.0))
        assertEquals(0, quarterForAngle(-44.0))
        assertEquals(45, quarterForAngle(-46.0))
        assertEquals(0, quarterForAngle(360.0))
        assertEquals(15, quarterForAngle(450.0))
        // Exactly between two quarters goes clockwise.
        assertEquals(15, quarterForAngle(45.0))
        assertEquals(0, quarterForAngle(315.0))
    }

    @Test
    fun aTwelveHourDialGivesTheNearestClockHourAtEveryDegreeAndDistance() {
        for (degree in everyDegree) {
            val expected = nearestByBruteForce(degree, DialRing.TwelveHour)
            for (distance in listOf(0.0, 0.2, innerRingThreshold - 0.01, innerRingThreshold, 0.8, 1.0)) {
                assertEquals("$degree degrees at $distance", expected, hourForTouch(degree, distance, is24Hour = false))
            }
        }
        assertEquals(12, hourForTouch(0.0, 0.8, is24Hour = false))
        assertEquals(12, hourForTouch(355.0, 0.8, is24Hour = false))
        assertEquals(3, hourForTouch(90.0, 0.8, is24Hour = false))
        assertEquals(1, hourForTouch(15.0, 0.8, is24Hour = false))
    }

    @Test
    fun aTwentyFourHourDialGivesTheOuterRingAtEveryDegree() {
        for (degree in everyDegree) {
            val expected = nearestByBruteForce(degree, DialRing.OuterHour)
            for (distance in listOf(innerRingThreshold, DialRing.OuterHour.radiusFraction, 1.0, 1.2)) {
                assertEquals("$degree degrees at $distance", expected, hourForTouch(degree, distance, is24Hour = true))
            }
        }
        assertEquals(0, hourForTouch(0.0, 0.8, is24Hour = true))
        assertEquals(9, hourForTouch(270.0, 0.8, is24Hour = true))
    }

    @Test
    fun aTwentyFourHourDialGivesTheInnerRingAtEveryDegree() {
        for (degree in everyDegree) {
            val expected = nearestByBruteForce(degree, DialRing.InnerHour)
            for (distance in listOf(0.0, 0.1, DialRing.InnerHour.radiusFraction, innerRingThreshold - 1e-9)) {
                assertEquals("$degree degrees at $distance", expected, hourForTouch(degree, distance, is24Hour = true))
            }
        }
        assertEquals(12, hourForTouch(0.0, 0.5, is24Hour = true))
        assertEquals(21, hourForTouch(270.0, 0.5, is24Hour = true))
    }

    @Test
    fun theInnerRingStartsHalfwayBetweenTheTwoRings() {
        assertTrue(DialRing.InnerHour.radiusFraction < innerRingThreshold)
        assertTrue(innerRingThreshold < DialRing.OuterHour.radiusFraction)
        assertEquals((101.0 + 69.0) / 2 / 128, innerRingThreshold, 1e-12)
        for (degree in everyDegree) {
            val outer = hourForTouch(degree, innerRingThreshold, is24Hour = true)
            val inner = hourForTouch(degree, innerRingThreshold - 1e-6, is24Hour = true)
            assertTrue("$degree degrees: $outer", outer in 0..11)
            assertEquals("$degree degrees", outer + 12, inner)
        }
    }

    @Test
    fun everyLabelIsOnTheUnitCircleAndReadsBackAsItself() {
        for (ring in DialRing.entries) {
            for (value in ring.values) {
                val point = labelPosition(value, ring)
                assertEquals("$value on $ring", 1.0, hypot(point.x, point.y), 1e-12)
                val x = point.x * ring.radiusFraction
                val y = point.y * ring.radiusFraction
                val angle = angleOfTouch(x, y)
                assertEquals("$value on $ring", 0.0, apart(angle, labelAngle(value, ring)), 1e-9)
                val readBack = when (ring) {
                    DialRing.Minute -> quarterForAngle(angle)
                    DialRing.TwelveHour -> hourForTouch(angle, hypot(x, y), is24Hour = false)
                    else -> hourForTouch(angle, hypot(x, y), is24Hour = true)
                }
                assertEquals("$value on $ring", value, readBack)
            }
        }
    }

    @Test
    fun labelsSitWhereAClockHasThem() {
        fun assertAt(expectedX: Double, expectedY: Double, point: DialPoint) {
            assertEquals(expectedX, point.x, 1e-12)
            assertEquals(expectedY, point.y, 1e-12)
        }
        assertAt(0.0, -1.0, labelPosition(12, DialRing.TwelveHour))
        assertAt(1.0, 0.0, labelPosition(3, DialRing.TwelveHour))
        assertAt(0.0, 1.0, labelPosition(6, DialRing.TwelveHour))
        assertAt(-1.0, 0.0, labelPosition(9, DialRing.TwelveHour))
        assertAt(0.0, -1.0, labelPosition(0, DialRing.OuterHour))
        assertAt(0.0, -1.0, labelPosition(12, DialRing.InnerHour))
        assertAt(1.0, 0.0, labelPosition(15, DialRing.InnerHour))
        assertAt(0.0, -1.0, labelPosition(0, DialRing.Minute))
        assertAt(1.0, 0.0, labelPosition(15, DialRing.Minute))
        assertAt(0.0, 1.0, labelPosition(30, DialRing.Minute))
        assertAt(-1.0, 0.0, labelPosition(45, DialRing.Minute))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aValueOffTheRingHasNoLabel() {
        labelPosition(5, DialRing.Minute)
    }

    @Test
    fun aTouchsAngleIsMeasuredClockwiseFromTwelveAtEveryDegree() {
        assertEquals(0.0, angleOfTouch(0.0, -1.0), 1e-12)
        assertEquals(90.0, angleOfTouch(1.0, 0.0), 1e-12)
        assertEquals(180.0, angleOfTouch(0.0, 1.0), 1e-12)
        assertEquals(270.0, angleOfTouch(-1.0, 0.0), 1e-12)
        assertEquals(0.0, angleOfTouch(0.0, 0.0), 1e-12)
        for (degree in everyDegree) {
            val radians = Math.toRadians(degree)
            val angle = angleOfTouch(50 * sin(radians), -50 * cos(radians))
            assertEquals("$degree degrees", 0.0, apart(angle, degree), 1e-9)
            assertTrue(angle >= 0 && angle < 360)
        }
    }

    @Test
    fun everyHourOfTheDayHasOneLabelOnEachKindOfDial() {
        for (hour in 0..23) {
            // 24-hour: the hour itself, outside in the morning and inside in the afternoon.
            assertEquals(hour, dialHourValue(hour, is24Hour = true))
            assertEquals(if (hour < 12) DialRing.OuterHour else DialRing.InnerHour, ringForHour(hour, is24Hour = true))
            assertTrue(dialHourValue(hour, true) in ringForHour(hour, true).values)
            assertEquals(hour, hourOfDayFromDial(dialHourValue(hour, true), is24Hour = true, current = (hour + 7) % 24))
            // 12-hour: the clock hour, and back again keeping the morning or afternoon.
            assertEquals(twelveHourClockHour(hour), dialHourValue(hour, is24Hour = false))
            assertEquals(DialRing.TwelveHour, ringForHour(hour, is24Hour = false))
            assertTrue(dialHourValue(hour, false) in DialRing.TwelveHour.values)
            assertEquals(hour, hourOfDayFromDial(dialHourValue(hour, false), is24Hour = false, current = hour))
        }
        // A 12-hour dial keeps the period it was in: 3 in the afternoon is 15:00.
        assertEquals(15, hourOfDayFromDial(3, is24Hour = false, current = 14))
        assertEquals(3, hourOfDayFromDial(3, is24Hour = false, current = 9))
        assertEquals(12, hourOfDayFromDial(12, is24Hour = false, current = 13))
        assertEquals(0, hourOfDayFromDial(12, is24Hour = false, current = 11))
    }

    @Test
    fun amAndPmMoveEveryHourTwelveHours() {
        for (hour in 0..23) {
            val morning = withPeriod(hour, afternoon = false)
            val afternoon = withPeriod(hour, afternoon = true)
            assertEquals("hour $hour", hour % 12, morning)
            assertEquals("hour $hour", hour % 12 + 12, afternoon)
            assertEquals(twelveHourClockHour(hour), twelveHourClockHour(morning))
            assertEquals(twelveHourClockHour(hour), twelveHourClockHour(afternoon))
        }
    }

    @Test
    fun labelsReadAsAClockShowsThem() {
        assertEquals("00", dialLabelText(0, DialRing.OuterHour))
        assertEquals("7", dialLabelText(7, DialRing.OuterHour))
        assertEquals("12", dialLabelText(12, DialRing.InnerHour))
        assertEquals("12", dialLabelText(12, DialRing.TwelveHour))
        assertEquals(listOf("00", "15", "30", "45"), DialRing.Minute.values.map { dialLabelText(it, DialRing.Minute) })
    }
}

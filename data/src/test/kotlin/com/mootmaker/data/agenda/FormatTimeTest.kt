package com.mootmaker.data.agenda

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases ported from the webapp's formatDateTime.test.ts (formatLocalTime), so both frontends agree. */
class FormatTimeTest {
    @Test
    fun twentyFourHourIsHoursAndMinutes() = assertEquals("14:30", formatTime("2026-07-01T14:30:00", TimeFormat.TwentyFourHour))

    @Test
    fun secondsAreDropped() = assertEquals("09:05", formatTime("2026-07-01T09:05:42", TimeFormat.TwentyFourHour))

    @Test
    fun unparsableInputIsShownAsIs() = assertEquals("not-a-date-time", formatTime("not-a-date-time", TimeFormat.TwentyFourHour))

    @Test
    fun afternoonIsPmAndZeroPadded() = assertEquals("02:30 PM", formatTime("2026-07-01T14:30:00", TimeFormat.AmPm))

    @Test
    fun morningIsAm() = assertEquals("10:15 AM", formatTime("2026-07-01T10:15:00", TimeFormat.AmPm))

    @Test
    fun midnightAndNoonAreTwelve() {
        assertEquals("12:30 AM", formatTime("2026-07-01T00:30:00", TimeFormat.AmPm))
        assertEquals("12:30 PM", formatTime("2026-07-01T12:30:00", TimeFormat.AmPm))
    }

    @Test
    fun timesNearMidnightAreNotShiftedByAZone() {
        assertEquals("23:45", formatTime("2026-07-01T23:45:00", TimeFormat.TwentyFourHour))
        assertEquals("00:15", formatTime("2026-07-01T00:15:00", TimeFormat.TwentyFourHour))
    }
}

package com.mootmaker.data.meeting

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// The geometry of the "Select time" dialog's clock dial (issue #28, option 3), kept free of Compose
// and Android so that every angle and every hour can be tested.
//
// Angles are in degrees, measured clockwise from twelve o'clock, as on a clock face. Positions are on
// a unit circle with x to the right and y downwards, as on a screen, so (0, -1) is twelve o'clock.
// The dial is never mirrored, right to left or otherwise: a clock face reads the same everywhere.

/**
 * A ring of labels on the dial. [radiusFraction] is how far out its labels sit, as a fraction of the
 * dial's radius; the values are from Material3's dial (labels at 101dp and 69dp on a 128dp radius).
 */
enum class DialRing(val values: List<Int>, val radiusFraction: Double) {
    /** A 12-hour clock's hours: 1 to 12, with 12 at the top. */
    TwelveHour((1..12).toList(), 101.0 / 128),

    /** A 24-hour clock's outer ring: 0 to 11, with 0 at the top, as the Android Clock app has it. */
    OuterHour((0..11).toList(), 101.0 / 128),

    /** A 24-hour clock's inner ring: 12 to 23, with 12 at the top. */
    InnerHour((12..23).toList(), 69.0 / 128),

    /** The minutes the API books: 00, 15, 30 and 45, at twelve, three, six and nine o'clock. */
    Minute(listOf(0, 15, 30, 45), 101.0 / 128),
}

/**
 * Where a touch on a 24-hour dial stops being the outer ring and becomes the inner one, as a fraction
 * of the dial's radius: halfway between the two rings' labels. A touch nearer the centre than this
 * picks from the inner ring (12 to 23).
 */
val innerRingThreshold: Double = (DialRing.OuterHour.radiusFraction + DialRing.InnerHour.radiusFraction) / 2

/** [angle] in degrees, brought into 0 (inclusive) to 360 (exclusive). */
fun normalisedDegrees(angle: Double): Double {
    val turned = angle % 360.0
    return if (turned < 0) turned + 360.0 else turned
}

/**
 * The angle of a touch [dx] to the right of the dial's centre and [dy] below it, in degrees clockwise
 * from twelve o'clock (0 up to 360). The centre itself reads as twelve o'clock.
 */
fun angleOfTouch(dx: Double, dy: Double): Double =
    // Not atan2(dx, -dy) alone: at the centre -dy is -0.0, which atan2 reads as six o'clock.
    if (dx == 0.0 && dy == 0.0) 0.0 else normalisedDegrees(Math.toDegrees(atan2(dx, -dy)))

/** Which of [slots] equal sectors, centred on twelve o'clock and going clockwise, [angle] falls in; a tie goes clockwise. */
private fun nearestSlot(angle: Double, slots: Int): Int = floor(normalisedDegrees(angle) / (360.0 / slots) + 0.5).toInt() % slots

/**
 * The quarter hour nearest [angle] on the minute dial: 0, 15, 30 or 45. Each owns the 90 degrees
 * centred on its label, so just left of twelve o'clock is 0, not 45. Exactly halfway goes clockwise.
 */
fun quarterForAngle(angle: Double): Int = nearestSlot(angle, 4) * 15

/**
 * The hour a touch at [angle] and [distanceFraction] of the dial's radius from the centre selects:
 *
 * - **12-hour** ([is24Hour] false): one ring; the clock hour 1 to 12, 12 at the top. The distance
 *   doesn't matter.
 * - **24-hour:** two rings, as the Android Clock app has them: 0 to 11 outside, 12 to 23 inside, the
 *   ring chosen by whether the touch is nearer the centre than [innerRingThreshold].
 *
 * Each hour owns the 30 degrees centred on its label; exactly halfway goes clockwise.
 */
fun hourForTouch(angle: Double, distanceFraction: Double, is24Hour: Boolean): Int {
    val position = nearestSlot(angle, 12) // 0 at twelve o'clock, 3 at three...
    return when {
        !is24Hour -> if (position == 0) 12 else position
        distanceFraction < innerRingThreshold -> position + 12
        else -> position
    }
}

/** The angle of [value]'s label on [ring], in degrees clockwise from twelve o'clock. */
fun labelAngle(value: Int, ring: DialRing): Double {
    require(value in ring.values) { "$value is not on the $ring ring" }
    return when (ring) {
        DialRing.Minute -> value * 6.0
        else -> (value % 12) * 30.0
    }
}

/** A point on the unit circle: [x] to the right of the centre, [y] below it. */
data class DialPoint(val x: Double, val y: Double)

/**
 * Where [value]'s label sits on [ring], on the unit circle (x right, y down). Multiply by the ring's
 * [DialRing.radiusFraction] and the dial's radius for the label's centre.
 */
fun labelPosition(value: Int, ring: DialRing): DialPoint {
    val radians = Math.toRadians(labelAngle(value, ring))
    return DialPoint(sin(radians), -cos(radians))
}

/** The ring an hour of the day (0 to 23) is shown on: one ring of 1 to 12, or the 24-hour outer or inner. */
fun ringForHour(hour: Int, is24Hour: Boolean): DialRing {
    require(hour in 0..23) { "hour $hour is not 0 to 23" }
    return when {
        !is24Hour -> DialRing.TwelveHour
        hour < 12 -> DialRing.OuterHour
        else -> DialRing.InnerHour
    }
}

/** The value an hour of the day (0 to 23) has on the dial: the clock hour 1 to 12 on a 12-hour dial, itself on a 24-hour one. */
fun dialHourValue(hour: Int, is24Hour: Boolean): Int = if (is24Hour) hour.also { require(it in 0..23) } else twelveHourClockHour(hour)

/**
 * The hour of the day (0 to 23) that a dial's [selected] value means. On a 24-hour dial it is that
 * hour; on a 12-hour dial the clock hour keeps the morning or afternoon of [current], which the
 * AM/PM toggle changes.
 */
fun hourOfDayFromDial(selected: Int, is24Hour: Boolean, current: Int): Int {
    require(current in 0..23) { "hour $current is not 0 to 23" }
    return if (is24Hour) selected.also { require(it in 0..23) } else hourOfDay(selected, afternoon = current >= 12)
}

/** [hour] (0 to 23) moved to the morning or [afternoon], keeping its clock hour: the AM/PM toggle. */
fun withPeriod(hour: Int, afternoon: Boolean): Int = hourOfDay(twelveHourClockHour(hour), afternoon)

/** How a dial label reads: "00" for midnight on a 24-hour dial and for the hour's top, otherwise the number. */
fun dialLabelText(value: Int, ring: DialRing): String = when (ring) {
    DialRing.Minute -> "%02d".format(value)
    DialRing.OuterHour -> if (value == 0) "00" else "$value"
    else -> "$value"
}

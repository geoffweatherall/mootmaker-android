package com.mootmaker.app.ui.addmeeting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.mootmaker.data.meeting.DialRing
import com.mootmaker.data.meeting.angleOfTouch
import com.mootmaker.data.meeting.dialHourValue
import com.mootmaker.data.meeting.dialLabelText
import com.mootmaker.data.meeting.hourForTouch
import com.mootmaker.data.meeting.hourOfDayFromDial
import com.mootmaker.data.meeting.labelPosition
import com.mootmaker.data.meeting.nearestQuarterMinute
import com.mootmaker.data.meeting.quarterForAngle
import com.mootmaker.data.meeting.ringForHour
import com.mootmaker.data.meeting.withPeriod
import java.time.LocalTime
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Which of the two dials is showing: the hours, or the minutes. */
internal enum class DialMode { Hour, Minute }

/**
 * What the dial holds: an hour of the day (0 to 23) and a quarter-hour minute (0, 15, 30 or 45), and
 * which dial is showing. The header, the hand and the selected label all draw from these, so they
 * always agree.
 */
@Stable
internal class QuarterHourDialState(hour: Int, minute: Int, val is24Hour: Boolean, mode: DialMode = DialMode.Hour) {
    var hour by mutableIntStateOf(hour.also { require(it in 0..23) })
    var minute by mutableIntStateOf(nearestQuarterMinute(minute))
    var mode by mutableStateOf(mode)

    val time: LocalTime get() = LocalTime.of(hour, minute)

    companion object {
        val Saver = listSaver<QuarterHourDialState, Int>(
            save = { listOf(it.hour, it.minute, if (it.is24Hour) 1 else 0, it.mode.ordinal) },
            restore = { QuarterHourDialState(it[0], it[1], it[2] == 1, DialMode.entries[it[3]]) },
        )
    }
}

@Composable
internal fun rememberQuarterHourDialState(initial: LocalTime, is24Hour: Boolean): QuarterHourDialState =
    rememberSaveable(saver = QuarterHourDialState.Saver) { QuarterHourDialState(initial.hour, initial.minute, is24Hour) }

// Sizes from Material3's time picker, so the dialog looks at home beside the date picker.
private val DialSize = 256.dp
private val SelectorSize = 48.dp
private val HandWidth = 2.dp
private val CentreDotSize = 8.dp
private val HeaderBoxWidth = 96.dp
private val HeaderBoxHeight = 80.dp
private val SeparatorWidth = 24.dp
private val PeriodWidth = 52.dp

/**
 * A clock dial that offers only what the API books (issue #28, option 3): a header of the hour and
 * minute, the hour dial (one ring of 1 to 12 with AM/PM, or two rings of 0 to 11 outside and 12 to 23
 * inside, as the Android Clock app has it), and a minute dial of 00, 15, 30 and 45 alone.
 *
 * A touch or drag selects the nearest allowed value, and the hand points at the selection, never at
 * the finger, so it jumps from value to value. Letting go of an hour moves on to the minutes.
 * Every label is also a selectable node ("9 hours", "45 minutes"), so TalkBack can pick a time.
 *
 * The dial is never mirrored right to left, and its numbers keep their size at any font scale, as
 * Material's do: a clock face has no room to grow.
 */
@Composable
internal fun QuarterHourDial(state: QuarterHourDialState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalDensity provides Density(density.density, fontScale = 1f),
    ) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            DialHeader(state)
            Spacer(Modifier.height(36.dp))
            ClockFace(state)
        }
    }
}

/** The large hour and minute boxes, as Material's: tap one to show its dial. AM and PM on a 12-hour clock. */
@Composable
private fun DialHeader(state: QuarterHourDialState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderBox(
            text = "%02d".format(dialHourValue(state.hour, state.is24Hour)),
            description = "Select hour",
            selected = state.mode == DialMode.Hour,
        ) { state.mode = DialMode.Hour }
        Box(Modifier.width(SeparatorWidth).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(":", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        HeaderBox(
            text = "%02d".format(state.minute),
            description = "Select minutes",
            selected = state.mode == DialMode.Minute,
        ) { state.mode = DialMode.Minute }
        if (!state.is24Hour) {
            Spacer(Modifier.width(12.dp))
            PeriodToggle(state)
        }
    }
}

@Composable
private fun HeaderBox(text: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(HeaderBoxWidth, HeaderBoxHeight)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.displayLarge,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
    }
}

/** AM above PM, outlined, as Material's vertical period selector. */
@Composable
private fun PeriodToggle(state: QuarterHourDialState) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Column(
        Modifier
            .size(PeriodWidth, HeaderBoxHeight)
            .border(1.dp, colors.outline, shape)
            .clip(shape)
            .selectableGroup()
            .semantics { contentDescription = "Select AM or PM" },
    ) {
        PeriodButton("AM", selected = state.hour < 12) { state.hour = withPeriod(state.hour, afternoon = false) }
        HorizontalDivider(color = colors.outline)
        PeriodButton("PM", selected = state.hour >= 12) { state.hour = withPeriod(state.hour, afternoon = true) }
    }
}

@Composable
private fun ColumnScope.PeriodButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(if (selected) colors.tertiaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.onTertiaryContainer else colors.onSurfaceVariant,
        )
    }
}

/** A value on the dial and the ring it sits on. */
private data class DialLabel(val value: Int, val ring: DialRing)

private fun labelsFor(mode: DialMode, is24Hour: Boolean): List<DialLabel> = when {
    mode == DialMode.Minute -> DialRing.Minute.values.map { DialLabel(it, DialRing.Minute) }
    is24Hour -> (DialRing.OuterHour.values.map { DialLabel(it, DialRing.OuterHour) } + DialRing.InnerHour.values.map { DialLabel(it, DialRing.InnerHour) })
    else -> DialRing.TwelveHour.values.map { DialLabel(it, DialRing.TwelveHour) }
}

private fun selectedLabel(state: QuarterHourDialState): DialLabel = when (state.mode) {
    DialMode.Minute -> DialLabel(state.minute, DialRing.Minute)
    DialMode.Hour -> DialLabel(dialHourValue(state.hour, state.is24Hour), ringForHour(state.hour, state.is24Hour))
}

/** What TalkBack reads for a label: "9 hours", "45 minutes". */
private fun spoken(label: DialLabel): String = if (label.ring == DialRing.Minute) "${label.value} minutes" else "${label.value} hours"

/** Selects [label] on [state]'s dial; the hour keeps its morning or afternoon on a 12-hour clock. */
private fun select(state: QuarterHourDialState, label: DialLabel) {
    if (label.ring == DialRing.Minute) {
        state.minute = label.value
    } else {
        state.hour = hourOfDayFromDial(label.value, state.is24Hour, state.hour)
    }
}

/** The label a touch at [position] on a dial of [size] selects. */
private fun labelAt(state: QuarterHourDialState, position: Offset, size: IntSize): DialLabel {
    val radius = size.width / 2.0
    val dx = position.x - radius
    val dy = position.y - size.height / 2.0
    val angle = angleOfTouch(dx, dy)
    return when (state.mode) {
        DialMode.Minute -> DialLabel(quarterForAngle(angle), DialRing.Minute)
        DialMode.Hour -> {
            val value = hourForTouch(angle, hypot(dx, dy) / radius, state.is24Hour)
            DialLabel(value, ringForHour(hourOfDayFromDial(value, state.is24Hour, state.hour), state.is24Hour))
        }
    }
}

/** The face: labels, the selector circle on the selected one, and the hand to it. */
@Composable
private fun ClockFace(state: QuarterHourDialState) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val dialSize = min(maxWidth, DialSize)
        val selected = selectedLabel(state)
        val labels = labelsFor(state.mode, state.is24Hour)
        Box(
            Modifier
                .size(dialSize)
                .clip(CircleShape)
                .background(colors.surfaceContainerHighest)
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun touch(position: Offset) {
                            val label = labelAt(state, position, size)
                            if (label != selectedLabel(state)) {
                                select(state, label)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        touch(down.position)
                        var released = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                released = true
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                touch(change.position)
                            }
                        }
                        // As the Clock app: once an hour is chosen, on to the minutes.
                        if (released && state.mode == DialMode.Hour) state.mode = DialMode.Minute
                    }
                }
                .selectableGroup(),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val centre = center
                val radius = size.width / 2
                val point = labelPosition(selected.value, selected.ring)
                val r = radius * selected.ring.radiusFraction.toFloat()
                val at = Offset(centre.x + r * point.x.toFloat(), centre.y + r * point.y.toFloat())
                drawLine(colors.primary, centre, at, strokeWidth = HandWidth.toPx())
                drawCircle(colors.primary, radius = SelectorSize.toPx() / 2, center = at)
                drawCircle(colors.primary, radius = CentreDotSize.toPx() / 2, center = centre)
            }
            val radiusPx = with(LocalDensity.current) { dialSize.toPx() } / 2
            val selectorPx = with(LocalDensity.current) { SelectorSize.toPx() }
            labels.forEach { label ->
                val isSelected = label == selected
                val point = labelPosition(label.value, label.ring)
                val r = radiusPx * label.ring.radiusFraction
                Box(
                    Modifier
                        .absoluteOffset {
                            IntOffset(
                                (radiusPx + r * point.x - selectorPx / 2).roundToInt(),
                                (radiusPx + r * point.y - selectorPx / 2).roundToInt(),
                            )
                        }
                        .size(SelectorSize)
                        // Not clickable: touches belong to the face, so a drag can start on a label.
                        // TalkBack and tests choose a label through its semantic click instead.
                        .clearAndSetSemantics {
                            contentDescription = spoken(label)
                            this.selected = isSelected
                            onClick {
                                select(state, label)
                                if (state.mode == DialMode.Hour) state.mode = DialMode.Minute
                                true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        dialLabelText(label.value, label.ring),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) colors.onPrimary else colors.onSurface,
                    )
                }
            }
        }
    }
}

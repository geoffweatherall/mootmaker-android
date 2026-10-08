package com.mootmaker.app.ui.addmeeting

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerSelectionMode
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.meeting.dialIs24Hour
import com.mootmaker.data.meeting.dialTime
import com.mootmaker.data.meeting.nearestQuarterMinute
import java.time.LocalTime
import androidx.compose.material3.R.string as M3Strings

/**
 * The "Select time" dialog for a meeting's start or end: Material3's clock dial, with a keyboard
 * toggle to type the time instead, as the Clock app has it. Material3 1.3 has no TimePickerDialog, so
 * this hosts the picker itself. The dial is 24-hour or AM/PM as the form shows times (N.103).
 *
 * The dial offers any minute, but the API books only quarter hours (F.41), so the minute is moved to
 * the nearest quarter as it changes ([QuarterDial], [SnapToQuarter]), and OK rounds once more, so
 * whatever the input mode OK never gives anything else (issue #28, option 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(initial: LocalTime, timeFormat: TimeFormat, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = dialIs24Hour(timeFormat))
    var typing by rememberSaveable { mutableStateOf(false) }
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TimeDialogContent(
            state = state,
            typing = typing,
            onToggleTyping = { typing = !typing },
            onOk = { onPick(dialTime(state.hour, state.minute)) },
            onCancel = onDismiss,
        )
    }
}

/** The dialog's surface, apart from the window it opens in, so a screenshot can draw it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialogContent(
    state: TimePickerState,
    typing: Boolean,
    onToggleTyping: () -> Unit,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        // Scrolls at the largest font sizes, where the dial and buttons outgrow a short screen.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Select time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            )
            if (typing) {
                SnapToQuarter(state)
                TimeInput(state)
            } else {
                QuarterDial(state)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleTyping) {
                    Icon(
                        if (typing) ClockIcon else KeyboardIcon,
                        contentDescription = if (typing) "Switch to clock input mode" else "Switch to text input mode",
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onOk) { Text("OK") }
                }
            }
        }
    }
}

/**
 * Typed input: whenever the minute is off a quarter hour, sets it to the nearest one, leaving the
 * hour as it is. The field keeps showing what was typed until it loses focus; the header, and OK,
 * show the quarter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnapToQuarter(state: TimePickerState) {
    LaunchedEffect(state) {
        snapshotFlow { state.minute }.collect { minute ->
            val quarter = nearestQuarterMinute(minute)
            if (quarter != minute) state.minute = quarter
        }
    }
}

/**
 * Material3's dial, held to quarter hours.
 *
 * Setting [TimePickerState.minute] moves the header and the highlighted label, but not the hand: the
 * dial keeps the hand's angle privately, and sets it only from its own taps and drags. Left at that,
 * a drag released at 38 leaves the hand pointing at 38 while the header says 45. So when the hand
 * comes to rest off a quarter, this taps the quarter's label on the dial for it, as a real touch, and
 * the dial animates its hand there as it would for any tap.
 *
 * - **Drag:** the hand follows the finger; the header and highlight jump between quarters as it
 *   goes. On release the dial's own end-of-drag animation is stopped (the lift is consumed before
 *   the dial sees it, which the dial treats as a cancelled drag) and the hand swings to the quarter.
 * - **Tap:** the dial rounds a tap to 5 minutes; a tap off a quarter then goes on to the nearest one.
 */
// PrivateResource: the label's description is Material3's own string ("%d minutes"), read so the
// match holds in any language. Like the rest of this workaround it leans on Material3 1.3's insides.
@SuppressLint("PrivateResource")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuarterDial(state: TimePickerState) {
    val view = LocalView.current
    val context = LocalContext.current
    // Not snapshot state: read and written only by the handlers below, never drawn.
    val pressed = remember { BooleanArray(1) }
    fun moveHandTo(minute: Int) {
        val description = context.getString(M3Strings.m3c_time_picker_minute_suffix, minute)
        view.post { dialLabel(view, description)?.let { tap(view, it.boundsInRoot.center) } }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.minute }.collect { minute ->
            val quarter = nearestQuarterMinute(minute)
            if (quarter != minute) {
                state.minute = quarter
                // A tap: its hand is on the way to the 5-minute mark; send it on to the quarter.
                if (!pressed[0] && state.selection == TimePickerSelectionMode.Minute) moveHandTo(quarter)
            }
        }
    }
    Box(
        Modifier.pointerInput(state) {
            awaitPointerEventScope {
                var downAt = Offset.Zero
                var dragging = false
                while (true) {
                    // The initial pass sees each event before the dial does.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when {
                        change.changedToDown() -> {
                            downAt = change.position
                            dragging = false
                            pressed[0] = true
                        }
                        change.changedToUp() -> {
                            pressed[0] = false
                            if (dragging && state.selection == TimePickerSelectionMode.Minute) {
                                change.consume()
                                moveHandTo(nearestQuarterMinute(state.minute))
                            }
                        }
                        (change.position - downAt).getDistance() > viewConfiguration.touchSlop -> dragging = true
                    }
                }
            }
        },
    ) { TimePicker(state) }
}

/** A tap at [at], in [view]'s own pixels, delivered as the system would deliver a finger's. */
private fun tap(view: View, at: Offset) {
    val time = SystemClock.uptimeMillis()
    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
        val event = MotionEvent.obtain(time, time, action, at.x, at.y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }
}

/**
 * The minute dial's label described as [description] ("45 minutes"): a clickable child of the dial's
 * selectable group, found in the dialog window's own semantics tree. Null if the dial isn't showing.
 */
private fun dialLabel(view: View, description: String): SemanticsNode? {
    val root = (view as? RootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
    fun search(node: SemanticsNode): SemanticsNode? {
        if (SemanticsProperties.SelectableGroup in node.config) {
            node.children.firstOrNull { child ->
                SemanticsActions.OnClick in child.config &&
                    child.children.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
            }?.let { return it }
        }
        return node.children.firstNotNullOfOrNull(::search)
    }
    return search(root)
}

// Material Design's "keyboard" and "schedule" icons, which material-icons-core leaves out.
private val KeyboardIcon: ImageVector by lazy {
    materialIcon(
        "Keyboard",
        "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8z" +
            "M11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2z" +
            "M16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z",
    )
}

private val ClockIcon: ImageVector by lazy {
    materialIcon(
        "Schedule",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8" +
            "s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
}

private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

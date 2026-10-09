package com.mootmaker.app.ui.addmeeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mootmaker.data.agenda.TimeFormat
import com.mootmaker.data.meeting.dialIs24Hour
import com.mootmaker.data.meeting.dialTime
import com.mootmaker.data.meeting.nearestQuarterMinute
import java.time.LocalTime

/**
 * The "Select time" dialog for a meeting's start or end: our own clock dial ([QuarterHourDial]),
 * which offers only quarter hours, with a keyboard toggle to type the time instead, as the Clock app
 * has it. Material3 1.3 has no TimePickerDialog, so this hosts the dial itself. The dial is 24-hour
 * or AM/PM as the form shows times (N.103).
 *
 * Typing uses Material's [TimeInput], whose minute field takes any minute; OK rounds it to the
 * nearest quarter, so whatever the input mode OK never gives anything the API refuses (F.41).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(initial: LocalTime, timeFormat: TimeFormat, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val dial = rememberQuarterHourDialState(initial, is24Hour = dialIs24Hour(timeFormat))
    var typing by rememberSaveable { mutableStateOf(false) }
    // Made afresh from the dial each time typing starts, so it always starts from what the dial shows.
    val input = remember(typing) { TimePickerState(dial.hour, dial.minute, dial.is24Hour) }
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TimeDialogContent(
            dial = dial,
            input = input,
            typing = typing,
            onToggleTyping = {
                if (typing) {
                    dial.hour = input.hour
                    dial.minute = nearestQuarterMinute(input.minute)
                }
                typing = !typing
            },
            onOk = { onPick(if (typing) dialTime(input.hour, input.minute) else dial.time) },
            onCancel = onDismiss,
        )
    }
}

/** The dialog's surface, apart from the window it opens in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialogContent(
    dial: QuarterHourDialState,
    input: TimePickerState,
    typing: Boolean,
    onToggleTyping: () -> Unit,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 400.dp),
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
                TimeInput(input)
            } else {
                QuarterHourDial(dial, Modifier.padding(bottom = 12.dp))
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

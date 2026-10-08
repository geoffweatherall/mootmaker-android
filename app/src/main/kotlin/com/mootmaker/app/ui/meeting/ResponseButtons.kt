package com.mootmaker.app.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mootmaker.data.meeting.AttendeeStatus

/** The three answers a person can give; "No response" is the unset default, not something to go back to. */
private val answers = listOf(AttendeeStatus.Going, AttendeeStatus.Maybe, AttendeeStatus.NotGoing)

/**
 * Going / Maybe / Not going. [selected] is the answer already given (shown filled), or null when
 * there is none yet. Tapping the answer already given does nothing. At a large font size a label
 * wraps onto a second line rather than being cut off.
 */
@Composable
fun ResponseButtons(
    selected: AttendeeStatus?,
    enabled: Boolean,
    onRespond: (AttendeeStatus) -> Unit,
    modifier: Modifier = Modifier,
    /** Set where several sets of buttons share a screen, so each is named for its meeting ("Going for Planning"). */
    forMeeting: String? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        answers.forEach { answer ->
            val buttonModifier = Modifier.weight(1f).let { base ->
                if (forMeeting == null) base else base.semantics { contentDescription = "${answer.label} for $forMeeting" }
            }
            val contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            if (answer == selected) {
                FilledTonalButton(onClick = {}, enabled = enabled, modifier = buttonModifier, contentPadding = contentPadding) {
                    Text(answer.label, textAlign = TextAlign.Center)
                }
            } else {
                OutlinedButton(onClick = { onRespond(answer) }, enabled = enabled, modifier = buttonModifier, contentPadding = contentPadding) {
                    Text(answer.label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

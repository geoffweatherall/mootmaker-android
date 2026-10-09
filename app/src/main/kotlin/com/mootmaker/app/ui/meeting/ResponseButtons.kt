package com.mootmaker.app.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
 * there is none yet. Tapping the answer already given does nothing. While an answer is being saved,
 * [pending] is that answer: it is drawn filled at once with a small progress indicator, and the
 * other two are disabled. At a large font size a label
 * wraps onto a second line rather than being cut off.
 */
@Composable
fun ResponseButtons(
    selected: AttendeeStatus?,
    enabled: Boolean,
    onRespond: (AttendeeStatus) -> Unit,
    modifier: Modifier = Modifier,
    pending: AttendeeStatus? = null,
    /** Set where several sets of buttons share a screen, so each is named for its meeting ("Going for Planning"). */
    forMeeting: String? = null,
) {
    val shown = pending ?: selected
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        answers.forEach { answer ->
            val buttonModifier = Modifier.weight(1f).let { base ->
                if (forMeeting == null) base else base.semantics { contentDescription = "${answer.label} for $forMeeting" }
            }
            val contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            if (answer == shown) {
                // The pending answer stays enabled so it keeps its selected colours; it does nothing when tapped.
                FilledTonalButton(onClick = {}, enabled = enabled || answer == pending, modifier = buttonModifier, contentPadding = contentPadding) {
                    if (answer == pending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).semantics { contentDescription = "Saving" },
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
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

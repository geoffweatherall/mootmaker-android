package com.mootmaker.app.ui.admin

import com.mootmaker.app.ui.ErrorBanner
import com.mootmaker.app.ui.FirstLoad
import com.mootmaker.app.ui.LoadFailed
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mootmaker.app.ui.Avatar
import com.mootmaker.app.ui.theme.roomColor
import com.mootmaker.data.admin.AdminPerson
import com.mootmaker.data.admin.AdminRoom
import com.mootmaker.data.admin.filterPeople
import com.mootmaker.data.agenda.RoomColor

data class RoomsActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (AdminRoom) -> Unit,
    val onRemove: (AdminRoom) -> Unit,
    val onName: (String) -> Unit,
    val onCapacity: (String) -> Unit,
    val onColor: (RoomColor?) -> Unit,
    val onSave: () -> Unit,
    val onCloseEditor: () -> Unit,
    val onConfirmRemove: () -> Unit,
    val onKeep: () -> Unit,
    val onDismissErrors: () -> Unit = {},
)

/** The admin Rooms screen (use cases P.125 to P.131), the webapp's `/rooms`. */
@Composable
fun RoomsScreen(state: RoomsState, actions: RoomsActions) {
    state.editor?.let { return RoomEditorPage(it, actions) }
    AdminScaffold(
        title = "Rooms",
        subtitle = "Manage the rooms available for booking.",
        addLabel = "Add room",
        canAdd = state.loaded,
        onBack = actions.onBack,
        onAdd = actions.onAdd,
    ) {
        when {
            state.loadError != null && !state.loaded -> LoadFailed(state.loadError, actions.onRetry)
            !state.loaded -> FirstLoad()
            state.rooms.isEmpty() -> Empty("No rooms exist yet.")
            else -> {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rooms, key = { it.id }) { room ->
                        ItemCard {
                            Box(Modifier.size(12.dp).background(roomColor(room.colorSlot, dark), CircleShape))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Capacity ${room.capacity}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { actions.onEdit(room) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${room.name}") }
                            IconButton(onClick = { actions.onRemove(room) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${room.name}") }
                        }
                    }
                }
            }
        }
    }
    state.removal?.let { removal ->
        ConfirmDialog(
            title = "Remove ${removal.room.name}?",
            body = "This room will no longer be offered for new meetings. Existing meetings booked in it are not affected.",
            confirm = "Remove room",
            busy = removal.removing,
            errors = removal.errors,
            onConfirm = actions.onConfirmRemove,
            onDismiss = actions.onKeep,
        )
    }
}

/**
 * Adding or editing a room, as a full-screen form: on a phone a form with a keyboard fits a page
 * better than a dialog. Back and Cancel close it without saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomEditorPage(editor: RoomEditor, actions: RoomsActions) {
    EditorScaffold(
        title = if (editor.roomId == null) "Add room" else "Edit room",
        saving = editor.saving,
        errors = editor.errors,
        onDismissErrors = actions.onDismissErrors,
        onCancel = actions.onCloseEditor,
        onSave = actions.onSave,
    ) {
        OutlinedTextField(
            value = editor.name,
            onValueChange = actions.onName,
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = editor.capacity,
            onValueChange = actions.onCapacity,
            label = { Text("Capacity") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Colour", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColourChoice("No colour", editor.color == null, onClick = { actions.onColor(null) }) {
                Text("None", style = MaterialTheme.typography.labelMedium)
            }
            RoomColor.entries.forEach { color ->
                ColourChoice(color.name, editor.color == color, onClick = { actions.onColor(color) }) {
                    Box(Modifier.size(20.dp).background(roomColor(color.ordinal, dark), CircleShape))
                }
            }
        }
    }
}

/** One choice in the colour palette: a radio-like tile, outlined when selected, named for TalkBack. */
@Composable
private fun ColourChoice(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .border(if (selected) 3.dp else 1.dp, outline, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
            },
    ) { content() }
}

data class PersonsActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onFilter: (String) -> Unit,
    val onAdd: () -> Unit,
    val onEdit: (AdminPerson) -> Unit,
    val onRemove: (AdminPerson) -> Unit,
    val onName: (String) -> Unit,
    val onAdmin: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onCloseEditor: () -> Unit,
    val onRetrySync: () -> Unit,
    val onDismissSync: () -> Unit,
    val onConfirmRemove: () -> Unit,
    val onKeep: () -> Unit,
    val onDismissErrors: () -> Unit = {},
)

/** The admin Persons screen (use cases Q.133 to Q.142), the webapp's `/persons`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonsScreen(state: PersonsState, actions: PersonsActions) {
    state.editor?.let { return PersonEditorPage(it, actions) }
    AdminScaffold(
        title = "Persons",
        subtitle = "Manage the people who can be booked into meetings.",
        addLabel = "Add person",
        canAdd = state.loaded,
        onBack = actions.onBack,
        onAdd = actions.onAdd,
    ) {
        when {
            state.loadError != null && !state.loaded -> LoadFailed(state.loadError, actions.onRetry)
            !state.loaded -> FirstLoad()
            state.people.isEmpty() -> Empty("No people exist yet.")
            else -> {
                OutlinedTextField(
                    value = state.filter,
                    onValueChange = actions.onFilter,
                    label = { Text("Filter") },
                    placeholder = { Text("Search by name or email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val shown = filterPeople(state.people, state.filter)
                if (shown.isEmpty()) {
                    Empty("No people match that filter.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.id }) { person ->
                            ItemCard {
                                Avatar(person.name, person.avatarUrl, size = 40.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                                        if (person.isAdmin) AdminBadge()
                                    }
                                    if (person.isGuest) {
                                        Text("Not signed up yet", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        person.linkedEmails.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                                IconButton(onClick = { actions.onEdit(person) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${person.name}") }
                                IconButton(onClick = { actions.onRemove(person) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${person.name}") }
                            }
                        }
                    }
                }
            }
        }
    }
    state.syncFailure?.let { failure ->
        ConfirmDialog(
            title = "Sync to sign-in failed",
            body = "${failure.name}'s admin access was saved, but couldn't be synced to their sign-in account yet - they won't be able to use it until this succeeds.",
            confirm = "Retry",
            busy = failure.retrying,
            errors = if (failure.stillFailing) listOf("Still not synced - you can try again or cancel.") else emptyList(),
            onConfirm = actions.onRetrySync,
            onDismiss = actions.onDismissSync,
            destructive = false,
        )
    }
    state.removal?.let { removal ->
        ConfirmDialog(
            title = "Remove ${removal.person.name}?",
            body = "All meetings they organise will be cancelled - other attendees will no longer see them. Meetings they only attend will just have them removed. This can't be undone.",
            confirm = "Remove person",
            busy = removal.removing,
            errors = removal.errors,
            onConfirm = actions.onConfirmRemove,
            onDismiss = actions.onKeep,
        )
    }
}

/** Adding or editing a person, as a full-screen form. Adding asks for a name only. */
@Composable
private fun PersonEditorPage(editor: PersonEditor, actions: PersonsActions) {
    val person = editor.person
    EditorScaffold(
        title = if (person == null) "Add person" else "Edit person",
        saving = editor.saving,
        errors = editor.errors,
        onDismissErrors = actions.onDismissErrors,
        onCancel = actions.onCloseEditor,
        onSave = actions.onSave,
    ) {
        OutlinedTextField(
            value = editor.name,
            onValueChange = actions.onName,
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        if (person == null) {
            Caption("Accounts link automatically the first time someone signs up using this exact name - there's nothing to enter here.")
        } else {
            if (!person.isGuest) {
                Text("Linked sign-in accounts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                person.linkedEmails.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = editor.isAdmin,
                    onCheckedChange = actions.onAdmin,
                    enabled = editor.adminSwitchEnabled,
                    modifier = Modifier.semantics { contentDescription = "Admin" },
                )
                Text("Admin", style = MaterialTheme.typography.bodyMedium)
            }
            Caption(
                when {
                    person.isGuest -> "This person hasn't signed in yet - admin access can only be granted once they've signed up."
                    person.isSelf -> "You can't change your own admin access here."
                    else -> "Can add, edit and remove rooms and people, and grant admin to others."
                },
            )
        }
    }
}

/** A full-screen form: Cancel (also Back) on the left, Save on the right, the fields scrolling under them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScaffold(
    title: String,
    saving: Boolean,
    errors: List<String>,
    onDismissErrors: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onCancel, enabled = !saving) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                actions = { TextButton(onClick = onSave, enabled = !saving) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            ErrorBanner(errors, onDismissErrors)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScaffold(
    title: String,
    subtitle: String,
    addLabel: String,
    canAdd: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            // Shown only once the list has loaded, so an add can't race the first load (as on the webapp).
            if (canAdd) FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = addLabel) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

@Composable
private fun ItemCard(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A confirmation, with any refusal shown inside it so the person can see why nothing happened. */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    busy: Boolean,
    errors: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Errors(errors)
                Text(body)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
    )
}

/** A label, not a control: outlined in the primary colour so it reads at a glance in light and dark. */
@Composable
private fun AdminBadge() {
    Text(
        "Admin",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun Errors(errors: List<String>) = errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Empty(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

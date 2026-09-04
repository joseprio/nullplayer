package com.nullplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.data.Group
import com.nullplayer.data.GroupSummary
import com.nullplayer.playback.PlayerUiState

/**
 * The vault, and the groups filed out of it.
 *
 * Tapping a row opens it. That is browsing, not selecting: the level-meter mark shows whichever
 * tile the player is drawing its queue from, and only the ribbon moves it, so looking inside a
 * group never stops music coming out of somewhere else.
 */
@Composable
fun LibraryScreen(
    state: PlayerUiState,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onUpdateGroup: (String, String, Int) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingGroup by remember { mutableStateOf<GroupSummary?>(null) }

    Box(modifier.fillMaxSize().background(BACKGROUND)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ScreenHeader(title = "library", onBack = onClose)
                Spacer(Modifier.height(22.dp))
            }

            // The vault itself: everything, and the one row that is not a tag.
            item {
                LibraryRow(
                    name = Group.VAULT_NAME,
                    colour = Color(Group.VAULT_COLOR),
                    count = state.vaultCount,
                    playing = state.activeGroupId.isEmpty(),
                    onOpen = { onOpenGroup(Group.VAULT_ID) },
                    onEdit = null,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (state.groups.size == 1) {
                            "1 GROUP"
                        } else {
                            "${state.groups.size} GROUPS"
                        },
                        color = MUTED,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    GlyphButton(
                        glyph = PlusGlyph,
                        contentDescription = "New group",
                        onClick = onCreateGroup,
                        size = 28.dp,
                        glyphFraction = 0.36f,
                        tint = TEXT,
                    )
                }
            }

            items(state.groups, key = { it.id }) { group ->
                LibraryRow(
                    name = group.name,
                    colour = Color(group.colorArgb),
                    count = group.itemCount,
                    playing = group.id == state.activeGroupId,
                    onOpen = { onOpenGroup(group.id) },
                    onEdit = { editingGroup = group },
                )
            }
        }
    }

    editingGroup?.let { existing ->
        EditGroupDialog(
            group = existing,
            onSave = { name, colour ->
                onUpdateGroup(existing.id, name, colour)
                editingGroup = null
            },
            onDelete = {
                onDeleteGroup(existing.id)
                editingGroup = null
            },
            onDismiss = { editingGroup = null },
        )
    }
}

/**
 * One row of the library: the vault, or a group.
 *
 * The vault gets no pencil — it has no name or colour to change and cannot be deleted — so that
 * slot carries a manage mark that opens it instead.
 */
@Composable
private fun LibraryRow(
    name: String,
    colour: Color,
    count: Int,
    playing: Boolean,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) colour.copy(alpha = 0.10f) else PANEL)
            .border(1.dp, if (playing) colour else LINE, RoundedCornerShape(10.dp))
            .clickable { onOpen() }
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = TEXT,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = itemCount(count), color = MUTED, fontSize = 12.sp)
        }
        if (playing) {
            PlayingMark(colour)
            Spacer(Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { if (onEdit != null) onEdit() else onOpen() },
            contentAlignment = Alignment.Center,
        ) {
            // The slot means "manage what this row is". For a group that is renaming and
            // recolouring it; the vault has neither, so it means opening the one screen where the
            // library itself is managed.
            if (onEdit != null) {
                Glyph(Icons.Filled.Edit, MUTED, contentDescription = "Edit this group", size = 18.dp)
            } else {
                Glyph(
                    Icons.Filled.Settings,
                    MUTED,
                    contentDescription = "Manage the vault",
                    size = 18.dp,
                )
            }
        }
        Glyph(Icons.AutoMirrored.Filled.KeyboardArrowRight, MUTED, contentDescription = null)
    }
}

/**
 * Renaming a group and recolouring it.
 *
 * There is no create counterpart: a new group is made straight from the + button, named by its
 * cardinal, and anyone who wants something else comes back here. Deleting is unguarded, because a
 * group is a tag — dropping it unfiles the tracks and nothing leaves the vault.
 */
@Composable
private fun EditGroupDialog(
    group: GroupSummary,
    onSave: (String, Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(group.name) }
    var colour by remember { mutableIntStateOf(group.colorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = { Text("Edit group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Name", color = MUTED) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT,
                        unfocusedTextColor = TEXT,
                        focusedBorderColor = Color(colour),
                        unfocusedBorderColor = LINE,
                        cursorColor = Color(colour),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                ColorPicker(color = colour, onColor = { colour = it })
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Delete this group",
                    color = DANGER,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDelete() }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
                Text(
                    text = "The tracks stay in the vault.",
                    color = MUTED,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, colour) }, enabled = name.isNotBlank()) {
                Text("Save", color = if (name.isNotBlank()) ACCENT else MUTED)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

/** A small text action, for the corners of section headers. */
@Composable
internal fun Action(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = ACCENT,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

internal fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"

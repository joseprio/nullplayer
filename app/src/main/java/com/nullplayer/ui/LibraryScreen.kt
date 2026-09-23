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
import androidx.compose.material.icons.filled.PlayArrow
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
import com.nullplayer.data.TileModes
import com.nullplayer.playback.PlayerUiState
import com.nullplayer.playback.RepeatMode

/**
 * The vault, and the groups filed out of it.
 *
 * Tapping a row opens it. That is browsing, not selecting: the level-meter mark shows whichever
 * tile the player is drawing its queue from, and looking inside a group never stops music coming
 * out of somewhere else. Moving the queue is the ribbon's job, or the play button on each row —
 * which is the only way to do it once the ribbon has been switched off.
 */
@Composable
fun LibraryScreen(
    state: PlayerUiState,
    onOpenGroup: (String) -> Unit,
    onPlayGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onUpdateGroup: (String, String, Int) -> Unit,
    onGroupModes: (String, TileModes) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onClose: () -> Unit,
    miniPlayer: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingGroup by remember { mutableStateOf<GroupSummary?>(null) }

    GlassScaffold(
        topBar = { glass ->
            ScreenHeader(title = "library", onBack = onClose, modifier = glass)
        },
        bottomBar = miniPlayer,
        modifier = modifier,
    ) { top, inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 20.dp + inset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The vault itself: everything, and the one row that is not a tag.
            item {
                LibraryRow(
                    name = Group.VAULT_NAME,
                    colour = Color(Group.VAULT_COLOR),
                    count = state.vaultCount,
                    playing = state.activeGroupId.isEmpty(),
                    onOpen = { onOpenGroup(Group.VAULT_ID) },
                    onPlay = { onPlayGroup(Group.VAULT_ID) },
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
                    onPlay = { onPlayGroup(group.id) },
                    onEdit = { editingGroup = group },
                )
            }
        }
    }

    editingGroup?.let { existing ->
        EditGroupDialog(
            group = existing,
            modes = state.settings.modesFor(existing.id),
            onSave = { name, colour, modes ->
                onUpdateGroup(existing.id, name, colour)
                onGroupModes(existing.id, modes)
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
 * The vault gets no pencil — it has no name or colour to change and cannot be deleted — and no
 * mark in its place either: the row itself opens it, and a button repeating that is one the user
 * has to press once to learn it does nothing new.
 */
@Composable
private fun LibraryRow(
    name: String,
    colour: Color,
    count: Int,
    playing: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
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
        // An empty row has nothing to start, so it offers nothing: a play button that did
        // nothing would be one the user has to press to find out.
        if (count > 0) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center,
            ) {
                // Accented, as the vault screen's is: the one button here that leaves the screen.
                Glyph(Icons.Filled.PlayArrow, ACCENT, contentDescription = "Play this", size = 21.dp)
            }
        }
        // The slot means "manage what this row is", which for a group is renaming and recolouring
        // it. The vault is neither named nor coloured by anyone, so it has no slot: what stood
        // here opened the vault's own list, which is what tapping the row does, under an icon
        // promising something else.
        if (onEdit != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Icons.Filled.Edit, MUTED, contentDescription = "Edit this group", size = 18.dp)
            }
        }
        Glyph(Icons.AutoMirrored.Filled.KeyboardArrowRight, MUTED, contentDescription = null)
    }
}

/**
 * Renaming a group, recolouring it, and saying how it plays.
 *
 * Shuffle and repeat are here as well as on the player because they belong to the tile rather than
 * to the app: the player's buttons answer for whatever the ribbon is on, and this is the one place
 * a group can be told how to play without first going and playing it.
 *
 * There is no create counterpart: a new group is made straight from the + button, named by its
 * cardinal, and anyone who wants something else comes back here. Deleting is unguarded, because a
 * group is a tag — dropping it unfiles the tracks and nothing leaves the vault.
 */
@Composable
private fun EditGroupDialog(
    group: GroupSummary,
    modes: TileModes,
    onSave: (String, Int, TileModes) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(group.name) }
    var colour by remember { mutableIntStateOf(group.colorArgb) }
    var shuffle by remember { mutableStateOf(modes.shuffle) }
    var repeat by remember { mutableStateOf(RepeatMode.ofOrdinal(modes.repeatOrdinal)) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shuffle", color = TEXT, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    NullSwitch(checked = shuffle, onCheckedChange = { shuffle = it })
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "REPEAT",
                    color = MUTED,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                RepeatMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = repeatLabel(mode),
                        selected = repeat == mode,
                        onSelect = { repeat = mode },
                    )
                }

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
            TextButton(
                // Copied from what the tile already has rather than built fresh, so the order
                // it is laid out in — which this dialog does not ask about — survives a rename.
                onClick = {
                    val edited = modes.copy(shuffle = shuffle, repeatOrdinal = repeat.ordinal)
                    onSave(name, colour, edited)
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Save", color = if (name.isNotBlank()) ACCENT else MUTED)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

/**
 * The repeat modes as the dialog names them.
 *
 * "The whole group" rather than the player's wordless icon, because a dialog has the room and a
 * user reading it is deciding rather than glancing.
 */
private fun repeatLabel(mode: RepeatMode): String = when (mode) {
    RepeatMode.OFF -> "Off"
    RepeatMode.ALL -> "The whole group"
    RepeatMode.ONE -> "This track"
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

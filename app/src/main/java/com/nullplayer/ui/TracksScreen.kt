package com.nullplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.nullplayer.data.Track
import com.nullplayer.playback.PlayerUiState

private val AUDIO_TYPES = arrayOf("audio/*", "application/ogg", "application/x-flac")

/**
 * One vault's contents: the only screen in the app that names anything.
 *
 * The rest stays anonymous — the player, the notification, a car head unit — but a library you
 * cannot read is a library you cannot manage, and this screen exists to be managed. Reaching it
 * goes through a biometric prompt by default.
 *
 * Adding music lives here rather than one level up, and only on the vault: a group is a tag over
 * the vault rather than a place of its own, so an import has exactly one destination and never
 * asks the user to remember which list they were standing in. It sits in the top bar rather than
 * in the list, because it acts on the vault as a whole and not on anything scrolled to.
 */
@Composable
fun TracksScreen(
    state: PlayerUiState,
    onImport: (List<android.net.Uri>) -> Unit,
    onPlay: (Track) -> Unit,
    onDelete: (List<Track>) -> Unit,
    onReadSharedGroups: (List<Track>) -> Unit,
    onSetTag: (List<Track>, String, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks = state.browseTracks

    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf(emptyList<Track>()) }
    var tagging by remember { mutableStateOf(false) }

    // A track deleted underneath us must not linger in the selection as a ghost id.
    val selected = tracks.filter { it.id in selection }

    fun leaveSelection() {
        selection = emptySet()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onImport(uris) }

    val colour = Color(state.browseColor)

    Box(modifier.fillMaxSize().background(BACKGROUND)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                // With a selection standing, the arrow drops it rather than leaving the screen, so
                // a mis-tap on a long list costs one tap instead of the whole way back in. The
                // system back gesture still leaves outright.
                ScreenHeader(
                    onBack = { if (selected.isNotEmpty()) leaveSelection() else onClose() }
                ) {
                    if (selected.isNotEmpty()) {
                        Text(
                            text = "${selected.size} selected",
                            color = TEXT,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(colour))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = state.browseName,
                            color = TEXT,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Importing belongs to the vault alone. A group is a tag over the vault, not
                    // a place to put a file, so a track arrives in the vault and is tagged into a
                    // group afterwards.
                    if (state.browseGroupId == Group.VAULT_ID) {
                        GlyphButton(
                            glyph = ImportGlyph,
                            contentDescription = "Add music from this device",
                            onClick = { picker.launch(AUDIO_TYPES) },
                            size = 34.dp,
                            glyphFraction = 0.30f,
                            tint = TEXT,
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
            }

            if (state.isImporting) {
                item {
                    Column {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = colour,
                            trackColor = LINE,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Importing ${state.importsInFlight}…",
                            color = MUTED,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = itemCount(tracks.size).uppercase(),
                        color = MUTED,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (tracks.isNotEmpty()) {
                        Action(
                            label = if (selected.size == tracks.size) "None" else "All",
                            onClick = {
                                selection = if (selected.size == tracks.size) {
                                    emptySet()
                                } else {
                                    tracks.map { it.id }.toSet()
                                }
                            },
                        )
                    }
                }
            }

            items(tracks, key = { track -> track.id }) { track ->
                TrackRow(
                    track = track,
                    accent = colour,
                    selected = track.id in selection,
                    onPlay = { onPlay(track) },
                    onToggle = {
                        selection = if (track.id in selection) {
                            selection - track.id
                        } else {
                            selection + track.id
                        }
                    },
                    onDelete = { pendingDelete = listOf(track) },
                )
            }

            if (tracks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionAction(
                            label = if (selected.isEmpty()) "Tag" else "Tag " + selected.size,
                            colour = ACCENT,
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onReadSharedGroups(selected)
                                tagging = true
                            },
                        )
                        SelectionAction(
                            label = if (selected.isEmpty()) {
                                "Nothing selected"
                            } else {
                                "Delete " + selected.size
                            },
                            colour = DANGER,
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = { pendingDelete = selected },
                        )
                    }
                }
            }
        }
    }

    if (tagging) {
        TagDialog(
            state = state,
            tracks = selected,
            onSetTag = { groupId, tagged -> onSetTag(selected, groupId, tagged) },
            onDismiss = { tagging = false },
        )
    }

    if (pendingDelete.isNotEmpty()) {
        val doomed = pendingDelete
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            containerColor = PANEL,
            titleContentColor = TEXT,
            textContentColor = MUTED,
            title = {
                Text(if (doomed.size == 1) "Delete this track?" else "Delete ${doomed.size} tracks?")
            },
            text = { Text(deletionSummary(doomed)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = emptyList()
                        leaveSelection()
                        onDelete(doomed)
                    }
                ) {
                    Text("Delete", color = DANGER)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) {
                    Text("Cancel", color = MUTED)
                }
            },
        )
    }
}

@Composable
private fun SelectionAction(
    label: String,
    colour: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) colour.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (enabled) colour else LINE, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) colour else MUTED,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

/**
 * Filing a selection into groups.
 *
 * A group is ticked when every selected track is already in it, so a tap reads as "put all of
 * these here" or "take all of these out" rather than as a per-track toggle, which would need a
 * third state to be honest about a mixed selection.
 */
@Composable
private fun TagDialog(
    state: PlayerUiState,
    tracks: List<Track>,
    onSetTag: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = {
            Text(if (tracks.size == 1) "Tag this track" else "Tag " + tracks.size + " tracks")
        },
        text = {
            if (state.groups.isEmpty()) {
                Text(
                    text = "There are no groups yet. Make one in the library first.",
                    color = MUTED,
                    fontSize = 13.sp,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.groups.forEach { group ->
                        val tagged = group.id in state.sharedGroupIds
                        val groupColour = Color(group.colorArgb)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSetTag(group.id, !tagged) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(groupColour))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = group.name,
                                color = TEXT,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // The row owns the click, so the box itself is not separately
                            // focusable — a tap anywhere on the line picks the group.
                            Checkbox(
                                checked = tagged,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = groupColour,
                                    checkmarkColor = readableOn(groupColour),
                                    uncheckedColor = LINE,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = ACCENT) }
        },
    )
}

/**
 * One track, named.
 *
 * The checkbox is always out because tagging and bulk deletion are the reasons this screen exists;
 * the row's own play and delete buttons are what keep a single track reachable without having to
 * select it first. Tapping the row body toggles the checkbox — the buttons carry everything else,
 * so the large target is spent on the common action.
 *
 * There is no VoiceOver button here. This is the one screen that already prints the title, the
 * artist, the album and the year, so reading them aloud would say what is on the row anyway; the
 * player's VoiceOver button remains the way to hear a track without looking.
 */
@Composable
private fun TrackRow(
    track: Track,
    accent: Color,
    selected: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else PANEL)
            .border(1.dp, if (selected) accent else LINE, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) accent else Color.Transparent)
                .border(1.dp, if (selected) accent else LINE, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Glyph(Icons.Filled.Check, BACKGROUND, contentDescription = null, size = 13.dp)
            }
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title ?: "Untitled",
                color = TEXT,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details(track),
                color = MUTED,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            // Accented, because it is the one button here that leaves the screen.
            Glyph(Icons.Filled.PlayArrow, accent, contentDescription = "Play", size = 21.dp)
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Icons.Filled.Delete, MUTED, contentDescription = "Delete", size = 19.dp)
        }
    }
}

/** The second line: whatever the tags actually had, and how long it runs. */
private fun details(track: Track): String = buildList {
    track.artist?.takeIf { it.isNotBlank() }?.let { add(it) }
    track.album?.takeIf { it.isNotBlank() }?.let { add(it) }
    track.year?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(formatDuration(track.durationMs))
}.joinToString(" · ")

/** Names up to three of the doomed, so a mis-tap is caught before it costs anything. */
private fun deletionSummary(tracks: List<Track>): String {
    val names = tracks.take(3).map { it.title ?: "Untitled" }
    val listed = when {
        tracks.size > 3 -> names.joinToString(", ") + " and ${tracks.size - 3} more"
        names.size > 1 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        else -> names.first()
    }
    return "$listed will be deleted from the vault. There is no copy anywhere else."
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

package com.nullplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.nullplayer.data.Track
import com.nullplayer.playback.PlayerUiState
import kotlin.math.pow

/**
 * The shared furniture of the two full-screen surfaces behind the front face — the dock and
 * settings. Both are deliberately plain: a list of dark panels on black, nothing that competes
 * with the device itself.
 */

internal val BACKGROUND = Color(0xFF0B0B0D)
internal val PANEL = Color(0xFF141519)
internal val LINE = Color(0xFF26272D)
internal val TEXT = Color(0xFFE8E8EA)
internal val MUTED = Color(0xFF8B8D94)
internal val ACCENT = Color(0xFF43B061)
internal val DANGER = Color(0xFFC8563F)

/**
 * Black or white, whichever is easier to read on [background].
 *
 * Group colours are the user's to pick, and a fixed light-on-dark rule breaks the moment someone
 * chooses amber. The two candidates are compared by WCAG contrast ratio rather than by a
 * brightness threshold, because the ratio is the thing that actually decides legibility.
 */
internal fun readableOn(background: Color): Color {
    val l = relativeLuminance(background)
    val againstWhite = 1.05f / (l + 0.05f)
    val againstBlack = (l + 0.05f) / 0.05f
    return if (againstBlack >= againstWhite) Color.Black else Color.White
}

/** WCAG relative luminance: the sRGB channels linearised, then weighted by how the eye sees them. */
private fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else ((channel + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * linear(color.red) +
        0.7152f * linear(color.green) +
        0.0722f * linear(color.blue)
}

@Composable
internal fun Panel(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

/**
 * The title bar every sub-screen carries: a back arrow, then the name.
 *
 * The arrow leads rather than a "Done" trailing the title, which is where Android users reach for
 * it and what the system back gesture mirrors. The bar sits above the scrolling content rather
 * than at the top of it, so the way back out is never scrolled off a long list; it carries its own
 * insets because it is the one thing on these screens that is not part of the list.
 */
@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenHeader(onBack, modifier) {
        Text(
            text = title,
            color = TEXT,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
    }
}

/** The same bar, for a title that is more than a word — a vault's colour and name, say. */
@Composable
internal fun ScreenHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        // The fill arrives in [modifier]: the bar floats over the list rather than sitting above
        // it, so what paints its background is frosted glass rather than a flat colour.
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                // Pulled left so the arrow lines up with the content below it rather than with
                // the touch target around it.
                .offset(x = (-10).dp)
                .size(42.dp)
                .clip(CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                Icons.AutoMirrored.Filled.ArrowBack,
                TEXT,
                contentDescription = "Back",
                size = 22.dp,
            )
        }
        content()
    }
}

/** [action] hangs a control off the right-hand end, for a section that can be acted on as a whole. */
@Composable
internal fun SectionHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Spacer(Modifier.height(22.dp))

    val label: @Composable () -> Unit = {
        Text(
            text = text.uppercase(),
            color = MUTED,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }

    if (action == null) {
        label()
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            label()
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) TEXT else MUTED, fontSize = 15.sp)
            Text(subtitle, color = MUTED, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        NullSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** The app's switch, wherever one is needed — in a row, or hung off a screen's title bar. */
@Composable
internal fun NullSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = ACCENT,
            uncheckedThumbColor = MUTED,
            uncheckedTrackColor = PANEL,
            uncheckedBorderColor = LINE,
        ),
    )
}

/** One entry in a pick-exactly-one list. */
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TEXT, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = MUTED, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        // An unselected ring used to be a hairline of [LINE] — the colour rows are divided with —
        // over a disc of [BACKGROUND]. On a panel that came to about 1.2:1 against what it sat on,
        // which is to say invisible: the one control on the screen whose entire job is to show you
        // which option you are on could only be found by knowing where to look. [MUTED] at two dp
        // is 5.5:1, and the same weight the platform's own radio is drawn at.
        //
        // Transparent rather than filled, too. These sit on dialogs and inline panels as often as
        // on the page, and a disc of the page colour punched a darker hole in both.
        Column(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) ACCENT else Color.Transparent)
                .border(2.dp, if (selected) ACCENT else MUTED, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White else Color.Transparent),
        ) {}
    }
}

/** The Wi-Fi upload address, in the one typeface where a PIN can be read back aloud safely. */
@Composable
internal fun Address(url: String, pin: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(BACKGROUND)
            .padding(14.dp)
    ) {
        Text(url, color = ACCENT, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PIN", color = MUTED, fontSize = 12.sp, letterSpacing = 1.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = pin,
                color = TEXT,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
        }
    }
}

/**
 * A Material icon at the size the panels use.
 *
 * Everything on these screens draws from the Material set rather than from typographic
 * lookalikes: a "×" is not a close button, it is whatever glyph the user's font happens to have
 * at that codepoint, and it changes size and weight with the typeface.
 */
@Composable
internal fun Glyph(
    icon: ImageVector,
    tint: Color,
    contentDescription: String? = null,
    size: Dp = 20.dp,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/**
 * Filing a selection into groups.
 *
 * It is reached from two places that have nothing else in common: the dock, filing a whole
 * selection at once, and the player, filing the one track that happens to be playing. The dock
 * is why it is written for a list rather than for a track.
 *
 * A group is ticked when every selected track is already in it, so a tap reads as "put all of
 * these here" or "take all of these out" rather than as a per-track toggle, which would need a
 * third state to be honest about a mixed selection.
 *
 * Nothing is written until Apply. The ticks are held here, against what the database said when
 * the sheet opened, and Cancel throws them away -- which is the only way a "new group" tick can be
 * offered as a tick: a group made the moment the row was tapped would outlive a cancelled sheet,
 * and the library would fill with empty groups nobody meant to keep.
 */
@Composable
internal fun TagDialog(
    state: PlayerUiState,
    tracks: List<Track>,
    onApply: (groupIds: Set<String>, newGroup: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the answer so that the ticks follow it in: the read is asked for as the sheet
    // opens and lands a moment later, and a set captured before then would be the last sheet's.
    var ticked by remember(state.sharedGroupIds) { mutableStateOf(state.sharedGroupIds) }
    var newGroup by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = {
            Text(if (tracks.size == 1) "Tag this track" else "Tag " + tracks.size + " tracks")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.groups.isEmpty()) {
                    // It no longer sends anyone to the library to make one, because the line
                    // underneath does it here. All that is left to say is that the list is empty.
                    Text(
                        text = "No groups yet.",
                        color = MUTED,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                }
                state.groups.forEach { group ->
                    val tagged = group.id in ticked
                    val groupColour = Color(group.colorArgb)
                    TagRow(
                        name = group.name,
                        nameColour = TEXT,
                        checked = tagged,
                        checkColour = groupColour,
                        onClick = { ticked = if (tagged) ticked - group.id else ticked + group.id },
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(groupColour))
                    }
                }

                // Last, under the groups, where a new one would appear anyway. Filing a track into
                // a group that does not exist yet is a normal thing to want -- it is often the
                // reason the sheet was opened -- and sending someone to the library to make it
                // first loses both the selection and the thought.
                //
                // Just "New group": the title above has already said whether this is one track
                // or five, and saying it again cost more width than the dialog has.
                TagRow(
                    name = "New group",
                    nameColour = ACCENT,
                    checked = newGroup,
                    checkColour = ACCENT,
                    onClick = { newGroup = !newGroup },
                ) {
                    // An outline where the groups have a filled dot: there is no colour to show
                    // until the group exists and the palette has handed it one.
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .border(1.dp, MUTED, CircleShape)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(ticked, newGroup)
                    onDismiss()
                },
            ) { Text("Apply", color = ACCENT) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

/** One line of the tag sheet: a dot, a name, and a box, with the whole line taking the tap. */
@Composable
private fun TagRow(
    name: String,
    nameColour: Color,
    checked: Boolean,
    checkColour: Color,
    onClick: () -> Unit,
    dot: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot()
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            color = nameColour,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // The row owns the click, so the box itself is not separately focusable — a tap
        // anywhere on the line picks the group.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = checkColour,
                checkmarkColor = readableOn(checkColour),
                uncheckedColor = LINE,
            ),
        )
    }
}

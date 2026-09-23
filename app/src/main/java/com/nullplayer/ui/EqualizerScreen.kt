package com.nullplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.playback.CrossfeedStrength
import com.nullplayer.playback.EqualizerSpec
import com.nullplayer.playback.ParametricEq
import com.nullplayer.playback.PlayerUiState
import com.nullplayer.playback.headphonesConnected

/**
 * The equalizer.
 *
 * The faders show what the audio engine actually holds, not what was asked for — picking a preset
 * moves the bands and only the engine knows where to, so the levels are read back rather than
 * predicted. Moving one fader by hand drops the preset, because the curve on screen is no longer
 * the one the preset named.
 *
 * An AutoEQ profile takes the bands' place while it is loaded. Its filters sit at centres and Qs
 * that ten fixed faders cannot represent, so rather than draw a curve that is not the one playing,
 * the profile is listed where the faders would be — under the same heading, because it is the same
 * question answered a different way.
 *
 * Crossfeed and volume normalisation sit under the curve. Neither is part of it — they need no
 * bands — but they are the rest of what the app does to the sound on its way out, so the title
 * bar's switch governs them too: off means a file is heard as it was mastered, with nothing to
 * remember beyond the one switch.
 */
@Composable
fun EqualizerScreen(
    state: PlayerUiState,
    onEnabled: (Boolean) -> Unit,
    onPreset: (Int) -> Unit,
    onBand: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onAutoEq: (String) -> Unit,
    onClearAutoEq: () -> Unit,
    onDismissAutoEqError: () -> Unit,
    onNormalizeVolume: (Boolean) -> Unit,
    onCrossfeed: (Boolean) -> Unit,
    onCrossfeedStrength: (CrossfeedStrength) -> Unit,
    onCrossfeedHeadphonesOnly: (Boolean) -> Unit,
    onClose: () -> Unit,
    miniPlayer: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = state.equalizerSpec
    val loaded = state.settings.equalizerAutoEq
    val profileLoaded = loaded.isNotBlank()
    val active = state.settings.equalizerEnabled
    val crossfeeding = active && state.settings.crossfeed
    // Anything there is to undo: a profile loaded, a preset chosen, or a fader moved off zero.
    val modified = profileLoaded ||
        state.settings.equalizerPreset >= 0 ||
        state.equalizerLevels.any { it != 0 }

    var importing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var submits by remember { mutableIntStateOf(0) }

    // Closes itself once the profile has actually landed. A config that failed to parse leaves the
    // error standing and the dialog open, so the paste is still there to be corrected.
    LaunchedEffect(submits, state.settings.equalizerAutoEq, state.autoEqError) {
        if (submits > 0 && state.autoEqError == null) importing = false
    }

    GlassScaffold(
        topBar = { glass ->
            // The switch rides the title bar rather than sitting in a panel of its own: it governs
            // the curve below it, and a screen whose first row is a control for the rest of the
            // screen reads as one more setting among them.
            ScreenHeader(onBack = onClose, modifier = glass) {
                Text(
                    text = "equalizer",
                    color = TEXT,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f),
                )
                NullSwitch(
                    checked = state.settings.equalizerEnabled,
                    onCheckedChange = onEnabled,
                )
            }
        },
        bottomBar = miniPlayer,
        modifier = modifier,
    ) { top, inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 20.dp + inset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionHeader("Bands") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // One reset, in one place, whichever source defined the curve — a profile
                        // is discarded the same way a moved fader is, and putting the control on
                        // the profile card instead would make the same act look like two.
                        if (modified) {
                            IconAction(
                                icon = Icons.Filled.Delete,
                                tint = DANGER,
                                description = if (profileLoaded) {
                                    "Remove the profile"
                                } else {
                                    "Flatten the bands"
                                },
                                enabled = active,
                                onClick = if (profileLoaded) onClearAutoEq else onReset,
                            )
                        }
                        GlyphButton(
                            glyph = ImportGlyph,
                            contentDescription = "Import an AutoEQ profile",
                            onClick = {
                                draft = loaded
                                onDismissAutoEqError()
                                importing = true
                            },
                            size = 34.dp,
                            glyphFraction = 0.30f,
                            enabled = active,
                            tint = TEXT,
                        )
                    }
                }
            }

            if (profileLoaded) {
                item { LoadedProfile(curve = state.equalizerCurve, enabled = active) }
            } else {
                item {
                    Panel {
                        Faders(
                            spec = spec,
                            levels = state.equalizerLevels,
                            enabled = active,
                            onBand = onBand,
                        )
                    }
                }
            }

            // Under the bands rather than over them: a preset is a shortcut to a curve, and the
            // curve is the thing this screen is about.
            if (spec.presets.isNotEmpty() && !profileLoaded) {
                item { SectionHeader("Presets") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(spec.presets.withIndex().toList()) { (index, name) ->
                            Chip(
                                label = name,
                                selected = state.settings.equalizerPreset == index,
                                enabled = active,
                                onClick = { onPreset(index) },
                            )
                        }
                    }
                }
            }

            // Between the curve and the level: crossfeed is about where the sound sits, which
            // is closer to the faders' question than to normalisation's.
            item { SectionHeader("Headphones") }
            item {
                Panel {
                    ToggleRow(
                        title = "Crossfeed",
                        subtitle = crossfeedStatus(
                            active = active,
                            enabled = state.settings.crossfeed,
                            waiting = state.settings.crossfeedHeadphonesOnly &&
                                !state.outputs.headphonesConnected,
                        ),
                        checked = state.settings.crossfeed,
                        onCheckedChange = onCrossfeed,
                        enabled = active,
                    )
                }
            }
            // The strength and the headphones rule only mean anything once crossfeed is on, so
            // like the presets under a loaded profile they step out of the way until it is.
            if (state.settings.crossfeed) {
                item {
                    val chosen = CrossfeedStrength.ofOrdinal(state.settings.crossfeedStrengthOrdinal)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CrossfeedStrength.entries) { strength ->
                            Chip(
                                label = strength.label,
                                selected = strength == chosen,
                                enabled = crossfeeding,
                                onClick = { onCrossfeedStrength(strength) },
                            )
                        }
                    }
                }
                item {
                    Panel {
                        ToggleRow(
                            title = "Only on headphones",
                            subtitle = "Bypassed while the phone's speaker is playing.",
                            checked = state.settings.crossfeedHeadphonesOnly,
                            onCheckedChange = onCrossfeedHeadphonesOnly,
                            enabled = crossfeeding,
                        )
                    }
                }
            }

            item { SectionHeader("Volume normalization") }
            item {
                Panel {
                    ToggleRow(
                        title = "Match track volume",
                        subtitle = normalizationStatus(
                            active = active,
                            enabled = state.settings.normalizeVolume,
                            unmeasured = state.unmeasuredTracks,
                        ),
                        checked = state.settings.normalizeVolume,
                        onCheckedChange = onNormalizeVolume,
                        enabled = active,
                    )
                }
            }
        }
    }

    if (importing) {
        ImportDialog(
            draft = draft,
            error = state.autoEqError,
            onDraft = { draft = it },
            onApply = {
                submits++
                onAutoEq(draft)
            },
            onDismiss = {
                importing = false
                onDismissAutoEqError()
            },
        )
    }
}

/**
 * The line under the switch, which is where the delay is explained.
 *
 * Levelling needs every track measured, and measuring means decoding, so a library switched on for
 * the first time is not levelled for the first few minutes. Saying how many are left turns that
 * into something visibly finishing rather than a setting that appears not to work yet.
 */
private fun normalizationStatus(active: Boolean, enabled: Boolean, unmeasured: Int): String = when {
    !active -> "Needs the equalizer switched on."
    enabled && unmeasured == 1 -> "Measuring the last track…"
    enabled && unmeasured > 0 -> "Measuring $unmeasured tracks…"
    enabled -> "Every track plays at the same loudness."
    else -> "Play every track at the same loudness."
}

/**
 * The line under the crossfeed switch.
 *
 * [waiting] is the case worth a line of its own: the switch is on and the sound is unchanged,
 * because the phone is playing through its speaker and was asked not to bother. Without saying
 * so, that reads as a setting that does nothing.
 */
private fun crossfeedStatus(active: Boolean, enabled: Boolean, waiting: Boolean): String = when {
    !active -> "Needs the equalizer switched on."
    enabled && waiting -> "Waiting for headphones."
    enabled -> "Each side is heard a little in the other, as speakers in a room would be."
    else -> "Soften the hard left-right split of headphones."
}

/** Where a profile is pasted. Text only — there is no picker for something that arrives copied. */
@Composable
private fun ImportDialog(
    draft: String,
    error: String?,
    onDraft: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = { Text("Import a profile") },
        text = {
            Column {
                Text(
                    text = "Paste a ParametricEQ config — from AutoEQ, from a headphone " +
                        "measurement, or written by hand. It takes the bands' place while it " +
                        "is loaded.",
                    color = MUTED,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    textStyle = TextStyle(
                        color = TEXT,
                        fontSize = 12.sp,
                        fontFamily = MonaSansMono,
                    ),
                    placeholder = {
                        Text(
                            text = PLACEHOLDER,
                            color = MUTED,
                            fontSize = 12.sp,
                            fontFamily = MonaSansMono,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ACCENT,
                        unfocusedBorderColor = LINE,
                        cursorColor = ACCENT,
                    ),
                )
                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(text = message, color = DANGER, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = draft.isNotBlank()) {
                Text("Apply", color = if (draft.isBlank()) MUTED else ACCENT)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

private const val PLACEHOLDER =
    "Preamp: -6.8 dB\nFilter 1: ON PK Fc 105 Hz Gain 4.7 dB Q 0.70"

/** A Material icon as a tappable square, matching the weight of the drawn glyph beside it. */
@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            icon,
            if (enabled) tint else MUTED.copy(alpha = 0.4f),
            contentDescription = description,
            size = 19.dp,
        )
    }
}

/** What the engine ended up with: proof the paste was understood, and how it was read. */
@Composable
private fun LoadedProfile(curve: ParametricEq, enabled: Boolean) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${curve.filters.size} " +
                        if (curve.filters.size == 1) "filter" else "filters",
                    color = if (enabled) TEXT else MUTED,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Preamp ${formatDb(curve.preampDb)} dB",
                    color = MUTED,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        curve.filters.take(MAX_LISTED).forEach { filter ->
            Text(
                text = "${filter.type.token}  ${filter.frequencyHz.toInt()} Hz   " +
                    "${formatDb(filter.gainDb)} dB   Q ${filter.q}",
                color = MUTED,
                fontSize = 11.sp,
                maxLines = 1,
                style = TABULAR,
            )
        }
        if (curve.filters.size > MAX_LISTED) {
            Text(
                text = "and ${curve.filters.size - MAX_LISTED} more",
                color = MUTED,
                fontSize = 11.sp,
            )
        }
    }
}

/** Enough to recognise the profile without turning the screen into a spreadsheet. */
private const val MAX_LISTED = 8

private fun formatDb(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded > 0) "+$rounded" else "$rounded"
}

/** One vertical fader per band, laid out to whatever width there is. */
@Composable
private fun Faders(
    spec: EqualizerSpec,
    levels: List<Int>,
    enabled: Boolean,
    onBand: (Int, Int) -> Unit,
) {
    val span = (spec.maxMillibel - spec.minMillibel).coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(spec.bandCount) { band ->
            val level = levels.getOrElse(band) { 0 }
            val fraction = ((level - spec.minMillibel).toFloat() / span).coerceIn(0f, 1f)

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = decibels(level),
                    color = if (enabled) ACCENT else MUTED,
                    fontSize = 10.sp,
                    maxLines = 1,
                    style = TABULAR,
                )
                Spacer(Modifier.height(6.dp))
                Fader(
                    fraction = fraction,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onFraction = { value ->
                        onBand(band, (spec.minMillibel + value * span).toInt())
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = frequency(spec.centreHz.getOrElse(band) { 0 }),
                    color = MUTED,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Fader(
    fraction: Float,
    enabled: Boolean,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            // Upwards is louder, so the reported value is inverted from the touch coordinate.
            detectVerticalDragGestures { change, _ ->
                onFraction((1f - change.position.y / size.height).coerceIn(0f, 1f))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackWidth = 4.dp.toPx()
            val x = size.width / 2f
            drawLine(
                color = LINE,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )

            // The zero line, so a flat band is visibly flat.
            drawLine(
                color = LINE,
                start = Offset(x - size.width * 0.3f, size.height / 2f),
                end = Offset(x + size.width * 0.3f, size.height / 2f),
                strokeWidth = 1.dp.toPx(),
            )

            val knobY = size.height * (1f - fraction)
            val zeroY = size.height / 2f
            drawLine(
                color = if (enabled) ACCENT else MUTED,
                start = Offset(x, zeroY),
                end = Offset(x, knobY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawRoundRect(
                color = if (enabled) ACCENT else MUTED,
                topLeft = Offset(x - size.width * 0.34f, knobY - 5.dp.toPx()),
                size = Size(size.width * 0.68f, 10.dp.toPx()),
                cornerRadius = CornerRadius(5.dp.toPx()),
            )
        }
    }
}

private fun decibels(millibel: Int): String {
    val db = millibel / 100
    return if (db > 0) "+$db" else "$db"
}

private fun frequency(hz: Int): String = when {
    hz >= 1000 -> "${hz / 1000}k"
    else -> "$hz"
}

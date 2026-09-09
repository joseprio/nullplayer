package com.nullplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.data.AppSettings
import com.nullplayer.playback.AudioOutput
import com.nullplayer.playback.AudioOutputs
import com.nullplayer.playback.VoiceOption
import com.nullplayer.playback.PlayerUiState

/** The stored value for "any headset will do", rather than one particular device. */
private const val ANY_HEADSET = ""

/** The stored value for "leave routing to the system". */
private const val SYSTEM_DEFAULT = ""

/**
 * Settings.
 *
 * Two of these switches can lock the user out of their own music — a biometric gate they cannot
 * pass, or a required device they have lost — so each one says, in its subtitle, exactly what it
 * will refuse and when.
 */
@Composable
fun SettingsScreen(
    state: PlayerUiState,
    onBlockScreenshots: (Boolean) -> Unit,
    onLockOnLaunch: (Boolean) -> Unit,
    onLockOnPlay: (Boolean) -> Unit,
    onLockOnDock: (Boolean) -> Unit,
    onShowSeeker: (Boolean) -> Unit,
    onShowVaultCounts: (Boolean) -> Unit,
    onVoice: (String) -> Unit,
    onRequireOutputDevice: (Boolean) -> Unit,
    onRequiredDevice: (String) -> Unit,
    onPreferredDevice: (String) -> Unit,
    onClose: () -> Unit,
    miniPlayer: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    var choosingVoice by remember { mutableStateOf(false) }

    GlassScaffold(
        topBar = { glass ->
            ScreenHeader(title = "settings", onBack = onClose, modifier = glass)
        },
        bottomBar = miniPlayer,
        modifier = modifier,
    ) { top, inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = 20.dp + inset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // -- Security ---------------------------------------------------------------------

            item { SectionHeader("Security") }

            if (!state.biometricsAvailable) {
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphMark(InfoGlyph, MUTED, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.biometricsUnavailableReason
                                    ?: "Biometrics are unavailable on this phone.",
                                color = MUTED,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            // Above the biometric switches, and not greyed out with them: this is the one lock
            // here that asks nothing of the phone's hardware, so it is the one still available
            // on a device with no enrolment.
            item {
                Panel {
                    ToggleRow(
                        title = "Block screenshots",
                        subtitle = "Refuse screen capture and recording, and blank the app in " +
                            "the recent-apps list.",
                        checked = settings.blockScreenshots,
                        onCheckedChange = onBlockScreenshots,
                    )
                }
            }

            item {
                Panel {
                    ToggleRow(
                        title = "Unlock the app",
                        subtitle = "Ask on launch, and after half a minute in the background.",
                        checked = settings.lockOnLaunch,
                        onCheckedChange = onLockOnLaunch,
                        enabled = state.biometricsAvailable,
                    )
                }
            }

            item {
                Panel {
                    ToggleRow(
                        title = "Unlock playback",
                        subtitle = "Ask every time the music starts or resumes, wherever the " +
                            "press came from.",
                        checked = settings.lockOnPlay,
                        onCheckedChange = onLockOnPlay,
                        enabled = state.biometricsAvailable,
                    )
                }
            }

            item {
                Panel {
                    ToggleRow(
                        title = "Unlock the library",
                        subtitle = "Ask before the dock opens. It is the only screen that names " +
                            "tracks and the only one that can delete them.",
                        checked = settings.lockOnDock,
                        onCheckedChange = onLockOnDock,
                        enabled = state.biometricsAvailable,
                    )
                }
            }

            // -- Player -----------------------------------------------------------------------

            item { SectionHeader("Player") }

            item {
                Panel {
                    ToggleRow(
                        title = "Show the seeker bar",
                        subtitle = "The scrub bar and the elapsed and remaining times.",
                        checked = settings.showSeeker,
                        onCheckedChange = onShowSeeker,
                    )
                }
            }

            item {
                Panel {
                    ToggleRow(
                        title = "Show item counts",
                        subtitle = "How much is on each vault, on the ribbon.",
                        checked = settings.showVaultCounts,
                        onCheckedChange = onShowVaultCounts,
                    )
                }
            }

            // -- VoiceOver --------------------------------------------------------------------

            item { SectionHeader("VoiceOver") }

            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Voice", color = TEXT, fontSize = 15.sp)
                            Text(
                                text = if (state.voices.isEmpty()) {
                                    "The speech engine has not offered a choice on this phone."
                                } else {
                                    voiceLabel(settings.voiceName, state.voices)
                                },
                                color = MUTED,
                                fontSize = 13.sp,
                            )
                        }
                        // The list is as long as the engine's voice catalogue, which on some
                        // phones is dozens. Behind a pencil it costs one row here instead of
                        // pushing every setting below it off the screen.
                        if (state.voices.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { choosingVoice = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph(
                                    Icons.Filled.Edit,
                                    MUTED,
                                    contentDescription = "Choose a voice",
                                    size = 18.dp,
                                )
                            }
                        }
                    }
                }
            }

            // -- Audio output -----------------------------------------------------------------

            item { SectionHeader("Audio output") }

            item {
                Panel {
                    ToggleRow(
                        title = "Only play to headphones",
                        subtitle = requirementSubtitle(settings, state.outputs),
                        checked = settings.requireOutputDevice,
                        onCheckedChange = onRequireOutputDevice,
                    )
                }
            }

            if (settings.requireOutputDevice) {
                item {
                    Panel {
                        Text("Required device", color = TEXT, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        ChoiceRow(
                            title = "Any headset",
                            subtitle = "Wired, USB, Bluetooth or a hearing aid.",
                            selected = settings.requiredDeviceKey == ANY_HEADSET,
                            onSelect = { onRequiredDevice(ANY_HEADSET) },
                        )
                        DeviceChoices(
                            devices = requirableDevices(state.outputs),
                            selectedKey = settings.requiredDeviceKey,
                            onSelect = onRequiredDevice,
                        )
                    }
                }
            }

            item {
                Panel {
                    Text("Preferred output", color = TEXT, fontSize = 15.sp)
                    Text(
                        text = "Where the music goes when more than one device is connected.",
                        color = MUTED,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChoiceRow(
                        title = "System default",
                        selected = settings.preferredDeviceKey == SYSTEM_DEFAULT,
                        onSelect = { onPreferredDevice(SYSTEM_DEFAULT) },
                    )
                    DeviceChoices(
                        devices = state.outputs,
                        selectedKey = settings.preferredDeviceKey,
                        onSelect = onPreferredDevice,
                    )
                }
            }
        }
    }

    if (choosingVoice) {
        VoiceDialog(
            voices = state.voices,
            selected = settings.voiceName,
            onSelect = onVoice,
            onDismiss = { choosingVoice = false },
        )
    }
}

/**
 * The whole catalogue, which is where a list this long belongs.
 *
 * It stays open while a voice is picked rather than closing on the first tap: choosing one reads a
 * line back, and the point is to try several against each other.
 */
@Composable
private fun VoiceDialog(
    voices: List<VoiceOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = { Text("Voice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Picking one reads a line back so you can hear it.",
                    color = MUTED,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    title = "System default",
                    selected = selected.isEmpty(),
                    onSelect = { onSelect("") },
                )
                voices.forEach { voice ->
                    ChoiceRow(
                        title = voice.label,
                        selected = selected == voice.id,
                        onSelect = { onSelect(voice.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = ACCENT) }
        },
    )
}

/** What the Voice row says underneath itself: the chosen voice, by name. */
private fun voiceLabel(stored: String, voices: List<VoiceOption>): String = when {
    stored.isEmpty() -> "System default"
    // A voice can vanish when the engine is swapped or a language pack is removed.
    else -> voices.firstOrNull { it.id == stored }?.label ?: "$stored (unavailable)"
}

/**
 * The connected devices, plus whatever is stored but currently unplugged.
 *
 * Dropping a disconnected choice out of the list would silently look like no choice at all, and
 * the next tap anywhere near it would quietly rewrite the setting.
 */
@Composable
private fun DeviceChoices(
    devices: List<AudioOutput>,
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    val stored = selectedKey
        .takeIf { it.isNotEmpty() && devices.none { device -> device.key == selectedKey } }
        ?.let {
            AudioOutput(
                key = it,
                label = AudioOutputs.labelForKey(it),
                isHeadset = true,
                isBuiltIn = false,
            )
        }

    Column {
        devices.forEach { device ->
            ChoiceRow(
                title = device.label,
                selected = device.key == selectedKey,
                onSelect = { onSelect(device.key) },
            )
        }
        if (stored != null) {
            ChoiceRow(
                title = stored.label,
                subtitle = "Not connected",
                selected = true,
                onSelect = { onSelect(stored.key) },
            )
        }
    }
}

/** Devices worth requiring: the built-in speaker is always there, so gating on it means nothing. */
private fun requirableDevices(outputs: List<AudioOutput>): List<AudioOutput> =
    outputs.filterNot { it.isBuiltIn }

private fun requirementSubtitle(settings: AppSettings, outputs: List<AudioOutput>): String {
    if (!settings.requireOutputDevice) {
        return "Refuse to play out loud through the phone's own speaker."
    }
    val connected = when {
        settings.requiredDeviceKey.isEmpty() -> outputs.any { it.isHeadset }
        else -> outputs.any { it.key == settings.requiredDeviceKey }
    }
    return if (connected) "Connected. Playback will stop when it is unplugged." else "Not connected."
}

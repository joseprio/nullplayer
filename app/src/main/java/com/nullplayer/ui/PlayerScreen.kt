package com.nullplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.data.Group
import com.nullplayer.data.GroupSummary
import com.nullplayer.data.Track
import com.nullplayer.playback.PlayerUiState
import com.nullplayer.playback.RepeatMode as PlayerRepeatMode
import com.nullplayer.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.sin

/** How far a held skip button moves. Shared with the mini player, whose buttons are the same. */
internal const val SCRUB_STEP_MS = 5_000L

/** How long the centred tile must hold still before the queue actually moves to it. */
private const val SELECT_COMMIT_MS = 90L

/**
 * How much of its button a skip mark takes, against the play triangle's 0.26.
 *
 * A skip runs the full width it is given where the triangle stops well short of its own, so the
 * two need different fractions to read as the same size. The transport is the one row where that
 * mismatch is obvious: three marks side by side, and the outer pair is what the eye measures the
 * middle one against.
 */
private const val SKIP_GLYPH = 0.25f

/** The screen's own margin. Named because the button row has to measure against it. */
private val SCREEN_PADDING = 20.dp

/**
 * Roughly the whitespace inside a button between its edge and the mark drawn in it.
 *
 * It is what stops evenly spaced *boxes* from looking evenly spaced: between two buttons the eye
 * sees the gap plus two of these, at the screen edge only one. Leaving this much margin outside
 * the row puts the two back on equal terms. Approximate on purpose — the marks are not all the
 * same width, so no single number squares every gap exactly.
 */
private val GLYPH_INSET = 7.dp

/** Chips are a fixed width so the carousel can centre any of them exactly. */
private val CHIP_WIDTH = 188.dp
private val CHIP_GAP = 12.dp

/**
 * The same fixed extent on the other axis, for the ribbon stood on its end in landscape.
 *
 * The horizontal ribbon gets this for free — every chip is [CHIP_WIDTH] wide whatever is written
 * in it. Stood upright the scroll axis is the one the text grows along, so the height has to be
 * pinned by hand, and it depends on whether there is a second line under the name.
 */
private fun chipHeight(showCounts: Boolean): Dp = if (showCounts) 72.dp else 52.dp

/** Which of the inline panels, if any, is open under the button row. */
private enum class Panel { NONE, VOLUME, TIMER }

/**
 * The player.
 *
 * It fills whatever it is given rather than drawing a fixed-size object in the middle of the
 * screen: the ribbon and the progress bar stretch, and the control rows stay put at the bottom.
 *
 * Turned on its side it does not simply squash. The bars still run the whole width, but between
 * them the screen splits: the ribbon takes the left half and stands upright, scrolling the way the
 * screen is now long, and the track and its controls take the right. Which is the same two things
 * the portrait layout stacks, put side by side instead of one above the other.
 *
 * Nothing here names a track. The hero readout is a queue position and a clock — the VoiceOver
 * button is still the only thing that will tell you what is playing.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onScrub: (Long) -> Unit,
    onSeek: (Float) -> Unit,
    onToggleTimeMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onGoToTrack: (Int) -> Unit,
    onVoiceOver: () -> Unit,
    onVoiceOverLong: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onSetSleepTimer: (Int?) -> Unit,
    onToggleWebServer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectVault: (String) -> Unit,
    onOpenVault: (String) -> Unit,
    onOpenDock: () -> Unit,
    onOpenSettings: () -> Unit,
    onReadSharedGroups: (List<Track>) -> Unit,
    onSetTag: (List<Track>, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by remember { mutableStateOf(Panel.NONE) }
    var showAddress by remember { mutableStateOf(false) }
    var goingToTrack by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Wider than it is tall, with enough width that two halves are each still worth having:
        // a landscape phone, a tablet, a freeform window. Below that the stack reads better than
        // two cramped columns would.
        val sideBySide = maxWidth > maxHeight && maxWidth >= 480.dp

        Column(
            Modifier.fillMaxSize().padding(
                start = SCREEN_PADDING,
                end = SCREEN_PADDING,
                top = if (sideBySide) 6.dp else 12.dp,
                // Height is the scarce direction on a landscape screen, and a margin under the
                // button row only pushes it up out of the thumb's reach. What is left below it
                // there is the system's own gesture area, which is as near the edge as anything
                // meant to be tapped should get.
                bottom = if (sideBySide) 0.dp else 12.dp,
            )
        ) {
            TopBar(
                state = state,
                onOpenEqualizer = onOpenEqualizer,
                onToggleWebServer = onToggleWebServer,
                onShowAddress = { showAddress = true },
                onOpenDock = onOpenDock,
                onOpenSettings = onOpenSettings,
            )

            if (sideBySide) {
                Row(Modifier.weight(1f)) {
                    // The ribbon keeps the whole left half to scroll through, so the tile in the
                    // middle of it is the one across from the controls rather than up in a corner.
                    VaultRibbon(
                        groups = state.groups,
                        vaultCount = state.vaultCount,
                        favoriteCount = state.favoriteCount,
                        showFavorites = state.hasFavorites,
                        activeId = state.activeGroupId,
                        showCounts = state.settings.showVaultCounts,
                        onSelect = onSelectVault,
                        onOpen = onOpenVault,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        upright = true,
                    )
                    Spacer(Modifier.width(24.dp))
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state.hasTracks) {
                            Hero(
                                state = state,
                                compact = true,
                                onToggleFavorite = onToggleFavorite,
                                onTag = { tagging = true },
                            ) { goingToTrack = true }
                            Spacer(Modifier.height(12.dp))
                        }
                        Controls(
                            state = state,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onScrub = onScrub,
                            onSeek = onSeek,
                            onToggleTimeMode = onToggleTimeMode,
                            compact = true,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                VaultRibbon(
                    groups = state.groups,
                    vaultCount = state.vaultCount,
                    favoriteCount = state.favoriteCount,
                    showFavorites = state.hasFavorites,
                    activeId = state.activeGroupId,
                    showCounts = state.settings.showVaultCounts,
                    onSelect = onSelectVault,
                    onOpen = onOpenVault,
                )

                Spacer(Modifier.weight(1f))
                if (state.hasTracks) {
                    Hero(
                        state = state,
                        onToggleFavorite = onToggleFavorite,
                        onTag = { tagging = true },
                    ) { goingToTrack = true }
                    Spacer(Modifier.height(30.dp))
                }
                Controls(
                    state = state,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onScrub = onScrub,
                    onSeek = onSeek,
                    onToggleTimeMode = onToggleTimeMode,
                )
                Spacer(Modifier.weight(1f))
            }

            // The button row sits on the bottom edge whatever else the screen is doing, so it is
            // always in the same place under the thumb, and both panels open directly above it.
            AnimatedVisibility(visible = panel != Panel.NONE) {
                InlinePanelBox(Modifier.padding(bottom = 12.dp)) {
                    when (panel) {
                        Panel.VOLUME -> VolumePanel(state, onSetVolume, onToggleMute)
                        Panel.TIMER -> TimerPanel(state) {
                            onSetSleepTimer(it)
                            panel = Panel.NONE
                        }
                        Panel.NONE -> Unit
                    }
                }
            }
            Utilities(
                state = state,
                panel = panel,
                onPanel = { panel = if (panel == it) Panel.NONE else it },
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onVoiceOver = onVoiceOver,
                onVoiceOverLong = onVoiceOverLong,
            )

            Caption(state, compact = sideBySide)
        }

        // Guarded on there being a queue as well as on the flag: the tile can be switched for
        // an empty one from the ribbon while the dialog is open, and "go to track 1 of 0" is not
        // a question worth leaving on the screen.
        TagDialogFor(
            state = state,
            showing = tagging,
            onReadSharedGroups = onReadSharedGroups,
            onSetTag = onSetTag,
            onDismiss = { tagging = false },
        )

        if (goingToTrack && state.hasTracks) {
            GoToTrackDialog(
                total = state.tracks.size,
                onGo = { number ->
                    onGoToTrack(number)
                    goingToTrack = false
                },
                onDismiss = { goingToTrack = false },
            )
        }

        val url = state.web.url
        if (showAddress && url != null) {
            AlertDialog(
                onDismissRequest = { showAddress = false },
                containerColor = PANEL,
                titleContentColor = TEXT,
                textContentColor = MUTED,
                title = { Text("Web management") },
                text = {
                    Column {
                        Text(
                            text = "Open this on a computer on the same network and enter the PIN.",
                            color = MUTED,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Address(url = url, pin = state.web.pin.orEmpty())
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.web.activeUsers > 0) {
                                Box(
                                    Modifier.size(7.dp).clip(CircleShape).background(DANGER)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = someoneConnected(state.web.activeUsers),
                                color = if (state.web.activeUsers > 0) TEXT else MUTED,
                                fontSize = 13.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddress = false }) { Text("Done", color = ACCENT) }
                },
            )
        }
    }
}

/**
 * Who is on the page, in words.
 *
 * "Connected" rather than "logged in": a session ages out once it stops being heard from, so this
 * counts browsers open now, not everyone who has ever typed the PIN.
 */
private fun someoneConnected(count: Int): String = when (count) {
    0 -> "Nobody connected."
    1 -> "1 person connected."
    else -> "$count people connected."
}

// -- Top bar ------------------------------------------------------------------------------------

@Composable
private fun TopBar(
    state: PlayerUiState,
    onOpenEqualizer: () -> Unit,
    onToggleWebServer: () -> Unit,
    onShowAddress: () -> Unit,
    onOpenDock: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot sits over the globe rather than beside it, so the row keeps its spacing whether
        // or not anyone is connected. It is not clickable, so the toggle underneath still takes
        // the whole touch target.
        Box(contentAlignment = Alignment.TopEnd) {
            GlyphButton(
                glyph = GlobeGlyph,
                contentDescription = "Upload server",
                onClick = onToggleWebServer,
                active = state.web.enabled,
                glyphFraction = 0.36f,
            )
            if (state.web.activeUsers > 0) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, end = 6.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        // Ringed in the page colour so the dot reads as a dot at this size
                        // instead of smearing into whatever it happens to overlap.
                        .background(BACKGROUND)
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(DANGER)
                        .semantics {
                            contentDescription = someoneConnected(state.web.activeUsers)
                        },
                )
            }
        }
        // Only once there is an address worth reading.
        if (state.web.enabled && state.web.url != null) {
            // Plain white rather than accented: the globe beside it is already green to say the
            // server is running, and two green marks made this look like a second toggle rather
            // than a way to read the address.
            GlyphButton(
                glyph = InfoGlyph,
                contentDescription = "Show the upload address",
                onClick = onShowAddress,
                glyphFraction = 0.34f,
            )
        }
        Spacer(Modifier.weight(1f))
        GlyphButton(
            glyph = LibraryGlyph,
            contentDescription = "Open the vaults",
            onClick = onOpenDock,
            glyphFraction = 0.34f,
        )
        GlyphButton(
            glyph = EqualizerGlyph,
            contentDescription = "Equalizer",
            onClick = onOpenEqualizer,
            active = state.settings.equalizerEnabled,
            glyphFraction = 0.36f,
        )
        GlyphButton(
            glyph = SettingsGlyph,
            contentDescription = "Open settings",
            onClick = onOpenSettings,
            glyphFraction = 0.36f,
        )
    }
}

// -- The hero -----------------------------------------------------------------------------------

/**
 * Where you are in the queue, and nothing else.
 *
 * [compact] is the landscape size. The readout is still the largest thing on the screen, but a
 * screen turned on its side has barely half the height to spend and the position is worth less
 * than the controls under it — so this is what gives way first.
 */
@Composable
private fun Hero(
    state: PlayerUiState,
    compact: Boolean = false,
    onToggleFavorite: () -> Unit,
    onTag: () -> Unit,
    onClick: () -> Unit,
) {
    // An empty vault has no position to report, and saying so twice — here and on the greyed-out
    // transport below — is one line of chrome more than it is worth.
    if (!state.hasTracks) return

    // The readout stays centred on the screen while the target around it is only as wide as the
    // mark: a press anywhere in the middle of the player should not move the queue.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // A number in a queue, so the one thing worth doing to it is typing a different one.
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Go to a track", onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Its own target inside the readout's: a press on the heart marks the track, a
                // press anywhere else on the readout still asks which track to go to.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            onClickLabel = if (state.currentIsFavorite) {
                                "Remove from favorites"
                            } else {
                                "Add to favorites"
                            },
                            onClick = onToggleFavorite,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(
                        if (state.currentIsFavorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Filled.FavoriteBorder
                        },
                        if (state.currentIsFavorite) ACCENT else MUTED,
                        contentDescription = null,
                        size = 20.dp,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "TRACK",
                    color = MUTED,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.width(6.dp))
                // The third target inside the readout, and the same bargain the heart makes: a
                // press here files the track, a press anywhere else still asks which track to go
                // to. Filing is the one thing the dock could do to a track that the player could
                // not, and it is most wanted exactly where the track is playing.
                GlyphButton(
                    glyph = TagGlyph,
                    contentDescription = "Choose groups for this track",
                    onClick = onTag,
                    size = 30.dp,
                    glyphFraction = 0.34f,
                    tint = MUTED,
                )
            }
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
            Text(
                text = queuePosition(state),
                color = TEXT,
                fontSize = if (compact) 34.sp else 46.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * The tag sheet, for the one track the player knows about.
 *
 * Which groups a track is already in is a question for the database, and it is asked here rather
 * than kept in the state for the same reason the dock asks it: the answer is only wanted while the
 * sheet is open, and it must be fresh when it opens. Keying the effect on the track means opening
 * the sheet, and a track changing underneath an open sheet, both ask again.
 */
@Composable
private fun TagDialogFor(
    state: PlayerUiState,
    showing: Boolean,
    onReadSharedGroups: (List<Track>) -> Unit,
    onSetTag: (List<Track>, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val track = state.currentTrack
    LaunchedEffect(showing, track?.id) {
        if (showing && track != null) onReadSharedGroups(listOf(track))
    }
    if (!showing) return
    // Nothing playing is nothing to file. The button cannot be reached in that state anyway --
    // the readout it sits in is not drawn on an empty vault -- but a queue can empty underneath an
    // open sheet.
    if (track == null) {
        // From an effect rather than straight from composition: closing it here would be a state
        // write while the tree is being built, which Compose is entitled to punish.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    TagDialog(
        state = state,
        tracks = listOf(track),
        onSetTag = { groupId, tagged -> onSetTag(listOf(track), groupId, tagged) },
        onDismiss = onDismiss,
    )
}

/**
 * Jumping straight to a track by number.
 *
 * A number that is not in the queue is answered where it was typed rather than by closing: the
 * dialog was opened to go somewhere, and shutting it on a typo would throw away the intent along
 * with the mistake. Which is also why the field keeps what was typed — the fix is usually a digit.
 */
@Composable
private fun GoToTrackDialog(
    total: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    // The dialog exists to take a number and nothing else, so the field is live and the keyboard
    // is up as it appears: one tap on the readout, then type.
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val number = typed.toIntOrNull()
        error = when {
            number == null -> "Type a track number."
            number < 1 || number > total -> "There ${if (total == 1) "is" else "are"} only " +
                "$total ${if (total == 1) "track" else "tracks"}."
            else -> null
        }
        if (error == null && number != null) onGo(number)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = { Text("Go to track") },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    // Filtered rather than merely validated. A number keyboard still offers a
                    // comma, a minus and a space on some phones, and none of them could mean
                    // anything here — so they never reach the field in the first place.
                    onValueChange = { entry ->
                        typed = entry.filter { it.isDigit() }.take(total.toString().length + 1)
                        error = null
                    },
                    singleLine = true,
                    isError = error != null,
                    placeholder = { Text("1 – $total", color = MUTED) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    // The keyboard's own key does the same as the button, so a number can be
                    // typed and gone to without the thumb ever leaving it.
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT,
                        unfocusedTextColor = TEXT,
                        focusedBorderColor = ACCENT,
                        unfocusedBorderColor = LINE,
                        errorBorderColor = DANGER,
                        cursorColor = ACCENT,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(text = message, color = DANGER, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("Go", color = ACCENT) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

/**
 * Where in the queue we are, as the player's hero readout and the mini player both say it.
 *
 * A queue with nothing chosen yet still knows how much it holds, so the total is stated and only
 * the position is withheld.
 *
 * [separator] is the one thing the two callers disagree on. Set in 46pt as the only number on the
 * player, the slash wants air around it or the three glyphs read as one; in the strip at 12pt it
 * is a label rather than a display, and the same air there just pulls the figure apart.
 */
internal fun queuePosition(state: PlayerUiState, separator: String = " / "): String {
    val here = if (state.trackIndex < 0) "--" else "${state.trackIndex + 1}"
    return "$here$separator${state.tracks.size}"
}

// -- Controls -----------------------------------------------------------------------------------

@Composable
private fun Controls(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onScrub: (Long) -> Unit,
    onSeek: (Float) -> Unit,
    onToggleTimeMode: () -> Unit,
    compact: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Transport(
            state = state,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onScrub = onScrub,
            compact = compact,
        )
        if (state.settings.showSeeker) {
            Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
            Progress(state = state, onSeek = onSeek, onToggleTimeMode = onToggleTimeMode)
        }
    }
}

/** What a tile stands for: the whole vault, or one group. */
private data class Tile(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val itemCount: Int,
)

/**
 * The ribbon under the top bar: the vault first, then a tile per group.
 *
 * The vault tile is always there and always leads — it is everything in the library, and the one
 * selection that cannot go stale when a group is deleted. Groups follow in their own colours.
 *
 * Laid out as a carousel rather than a left-aligned row: tiles are a fixed extent and the row is
 * padded by half a viewport either side, so the selected tile sits in the middle of the screen and
 * the first one is centred rather than pinned against the edge. Snapping means a flick lands on a
 * tile instead of between two.
 *
 * Swiping is what selects: whatever the snap settles on becomes the queue, so the tile in the
 * middle of the screen is always the one playing and choosing costs one gesture instead of a
 * gesture and a tap. That leaves the tap itself free to mean "manage this one", which is the only
 * other thing a tile can do.
 *
 * [upright] turns the carousel through ninety degrees for the landscape layout. Only the axis
 * changes: the same tiles, the same centre, the same swipe-to-select, scrolling down the left of
 * the screen instead of across the top of it.
 */
@OptIn(FlowPreview::class)
@Composable
private fun VaultRibbon(
    groups: List<GroupSummary>,
    vaultCount: Int,
    favoriteCount: Int,
    showFavorites: Boolean,
    activeId: String,
    showCounts: Boolean,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    upright: Boolean = false,
) {
    // Favorites sits second, between the vault and the groups: it is the other tile that is not a
    // group, and pinning it there keeps the pair of them in the same place however the groups come
    // and go.
    val tiles = remember(groups, vaultCount, favoriteCount, showFavorites) {
        buildList {
            add(Tile(Group.VAULT_ID, Group.VAULT_NAME, Group.VAULT_COLOR, vaultCount))
            if (showFavorites) {
                add(
                    Tile(
                        Group.FAVORITES_ID,
                        Group.FAVORITES_NAME,
                        Group.FAVORITES_COLOR,
                        favoriteCount,
                    )
                )
            }
            groups.forEach { add(Tile(it.id, it.name, it.colorArgb, it.itemCount)) }
        }
    }

    val listState = rememberLazyListState()

    BoxWithConstraints(modifier.then(if (upright) Modifier else Modifier.fillMaxWidth())) {
        // Half a row minus half a tile, which is what puts any tile — including the first and the
        // last — in the centre when it is scrolled to the start of the content area. Measured
        // along whichever axis the ribbon actually scrolls.
        val tileExtent = if (upright) chipHeight(showCounts) else CHIP_WIDTH
        val sidePadding = (((if (upright) maxHeight else maxWidth) - tileExtent) / 2)
            .coerceAtLeast(0.dp)

        // The tile under the middle of the screen, tracked every frame. This is what the chips
        // colour themselves from, so the highlight lands the instant a tile crosses the centre
        // rather than after the fling has run down and a preference has been written and read
        // back — that round trip is what made choosing feel like it lagged the thumb.
        val centred by remember(tiles) {
            derivedStateOf { listState.centredIndex()?.let(tiles::getOrNull)?.id }
        }
        val highlighted = centred ?: activeId

        // Only fight the user's scroll when the queue was moved from somewhere else — the dock, a
        // deleted group. Re-animating onto a tile the ribbon is already sitting on is a visible
        // twitch at the end of every swipe.
        // The first placement is a jump, not a scroll. A lazy row starts at its first item, so
        // coming back from another screen with a group selected would otherwise replay the whole
        // journey from the vault as an animation — the ribbon appearing to scroll on its own,
        // reporting a move that happened long ago on a screen the user was not looking at.
        var placed by remember { mutableStateOf(false) }

        LaunchedEffect(activeId, tiles.size) {
            val index = tiles.indexOfFirst { it.id == activeId }
            if (index < 0) return@LaunchedEffect
            when {
                !placed -> {
                    listState.scrollToItem(index)
                    placed = true
                }
                // Only worth animating when the queue moved from somewhere else — the dock, or a
                // group being deleted. Re-animating onto the tile the ribbon already sits on is a
                // visible twitch at the end of every swipe.
                index != listState.centredIndex() -> listState.animateScrollToItem(index)
            }
        }

        // Read through a holder rather than closing over the parameter: this effect deliberately
        // outlives a selection change, so a captured `activeId` would be compared for ever against
        // the value it had when the ribbon first composed.
        val current by rememberUpdatedState(activeId)

        // Committing the queue trails the highlight by a breath. Switching tiles rebuilds the
        // player's queue, so doing it for every tile a fling flies past would restart playback
        // several times on the way to the one the user actually meant; the debounce keeps a single
        // swipe feeling immediate while a long fling only commits where it lands.
        LaunchedEffect(listState, tiles) {
            snapshotFlow { listState.centredIndex()?.let(tiles::getOrNull)?.id }
                .distinctUntilChanged()
                .debounce(SELECT_COMMIT_MS)
                .collect { settled ->
                    if (settled != null && settled != current) onSelect(settled)
                }
        }

        // The two lists differ in their axis and nothing else — every decision above this point
        // is shared, and `centredIndex` reads the scroll axis whichever one that is.
        if (upright) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = sidePadding),
                verticalArrangement = Arrangement.spacedBy(CHIP_GAP),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(listState),
            ) {
                items(tiles, key = { it.id }) { tile ->
                    VaultChip(
                        tile = tile,
                        selected = tile.id == highlighted,
                        showCount = showCounts,
                        onClick = { onOpen(tile.id) },
                        height = tileExtent,
                    )
                }
            }
        } else {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                flingBehavior = rememberSnapFlingBehavior(listState),
            ) {
                items(tiles, key = { it.id }) { tile ->
                    VaultChip(
                        tile = tile,
                        selected = tile.id == highlighted,
                        showCount = showCounts,
                        onClick = { onOpen(tile.id) },
                    )
                }
            }
        }
    }
}

/**
 * The tile nearest the middle of the viewport, or null before the row has been laid out.
 *
 * Measured against the actual viewport centre rather than read off `firstVisibleItemIndex`: the
 * row is padded by half a screen either side, so the first visible item is normally the one
 * off-screen to the left, not the one under the user's eye.
 */
private fun LazyListState.centredIndex(): Int? {
    val centre = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    return layoutInfo.visibleItemsInfo
        .minByOrNull { abs(it.offset + it.size / 2f - centre) }
        ?.index
}

@Composable
private fun VaultChip(
    tile: Tile,
    selected: Boolean,
    showCount: Boolean,
    onClick: () -> Unit,
    /** Pinned only in the upright ribbon, where the scroll axis is the one the text grows along. */
    height: Dp? = null,
) {
    val colour = Color(tile.colorArgb)
    // Unselected tiles are the same colour laid over the page, so the text colour is decided
    // against what is actually behind the letters rather than against the pure swatch.
    val fill = if (selected) colour else colour.copy(alpha = 0.34f).compositeOver(BACKGROUND)
    val ink = readableOn(fill)

    Column(
        modifier = Modifier
            .width(CHIP_WIDTH)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colour else LINE,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = tile.name,
            color = ink,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showCount) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = itemCountLabel(tile.itemCount),
                color = ink.copy(alpha = 0.72f),
                fontSize = 12.sp,
            )
        }
    }
}

/** Named apart from the lazy-list `items` builder, which is in scope wherever a ribbon is. */
private fun itemCountLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

/** The progress bar. Draggable, and wide enough to be worth dragging. */
@Composable
private fun Progress(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
    onToggleTimeMode: () -> Unit,
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val seekable = state.durationMs > 0
    val shown = if (dragFraction >= 0f) dragFraction else state.progress
    val busy = rememberBusy(state)

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(seekable) {
                    if (!seekable) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (dragFraction >= 0f) onSeek(dragFraction)
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ProgressBar(
                fraction = shown,
                busy = busy,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                thumbRadius = when {
                    !seekable -> 0.dp
                    dragFraction >= 0f -> 9.dp
                    else -> 6.dp
                },
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clock((shown * state.durationMs).toLong()),
                color = MUTED,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = remainder(state, shown),
                color = MUTED,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(
                        // No ripple and no shape: this is a line of text that answers a second
                        // question when asked, not a button pretending to be one.
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleTimeMode,
                    )
                    // The target grows inward and downward, away from the two edges the label is
                    // pinned to, so it becomes worth hitting without the digits moving at all.
                    .padding(start = 24.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/** How long a wait has to last before the bar starts saying so. */
private const val BUSY_AFTER_MS = 350L

/** The share of the bar the travelling segment covers. */
private const val BUSY_SPAN = 0.3f

/**
 * Whether the bar should stop claiming to know how far along the track is.
 *
 * A track whose length is still unknown is one the player has not finished opening: there is no
 * position to draw and no duration to draw it against, which is the "--:--" the seeker shows.
 *
 * The answer is held back a moment before it turns true. Every track change passes through
 * buffering for a few dozen milliseconds, and reacting to that instantly would put a flash of
 * animation between every song — the same blink the play button used to have. Going back to
 * normal is immediate: an answer that has arrived should not be held.
 */
@Composable
internal fun rememberBusy(state: PlayerUiState): Boolean {
    val waiting = state.isBuffering && state.durationMs <= 0

    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        if (!waiting) {
            busy = false
        } else {
            delay(BUSY_AFTER_MS)
            busy = true
        }
    }
    return busy
}

/**
 * The bar itself, drawn the same way wherever it appears: the player's seeker and the hairline
 * over the mini player are one bar at two sizes, so a track that is still opening looks like it is
 * still opening on both.
 *
 * [thumbRadius] of zero leaves the handle off, which is what a bar that cannot be dragged wants.
 * The travelling segment replaces the fill rather than joining it — while [busy] there is no
 * position to show, and drawing one anyway would be inventing it.
 */
@Composable
internal fun ProgressBar(
    fraction: Float,
    busy: Boolean,
    modifier: Modifier = Modifier,
    thickness: Dp = 4.dp,
    thumbRadius: Dp = 0.dp,
    cap: StrokeCap = StrokeCap.Round,
) {
    val sweep by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
        ),
        label = "sweep",
    )

    Canvas(modifier) {
        val stroke = thickness.toPx()
        val y = size.height / 2f
        drawLine(
            color = LINE,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = stroke,
            cap = cap,
        )

        if (busy) {
            // A short piece of the bar crossing it and leaving, over and over. It says the same
            // thing a filled bar says — something is happening — without claiming to know how far
            // along it is, which is the one thing nobody knows yet.
            val head = sweep * (1f + BUSY_SPAN)
            val from = ((head - BUSY_SPAN) * size.width).coerceIn(0f, size.width)
            val to = (head * size.width).coerceIn(0f, size.width)
            if (to > from) {
                drawLine(
                    color = ACCENT,
                    start = Offset(from, y),
                    end = Offset(to, y),
                    strokeWidth = stroke,
                    cap = cap,
                )
            }
            return@Canvas
        }

        if (fraction > 0f) {
            drawLine(
                color = ACCENT,
                start = Offset(0f, y),
                end = Offset(size.width * fraction, y),
                strokeWidth = stroke,
                cap = cap,
            )
        }
        if (thumbRadius > 0.dp) {
            drawCircle(
                color = ACCENT,
                radius = thumbRadius.toPx(),
                center = Offset(size.width * fraction, y),
            )
        }
    }
}

/**
 * The right-hand label: how much of the track is left, or how long the whole thing is.
 *
 * Both are the same number seen from opposite ends, and which one is wanted depends entirely on
 * the moment — so it is a tap rather than a setting buried in a screen nobody would think to
 * open for it. The choice is remembered, because a preference expressed by tapping is still a
 * preference.
 */
private fun remainder(state: PlayerUiState, shown: Float): String = when {
    state.durationMs <= 0L -> "--:--"
    state.settings.showRemainingTime ->
        "-" + clock(state.durationMs - (shown * state.durationMs).toLong())
    else -> clock(state.durationMs)
}

@Composable
private fun Transport(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onScrub: (Long) -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(
            glyph = PreviousGlyph,
            contentDescription = "Previous track. Hold to rewind.",
            onClick = onPrevious,
            onLongPress = { onScrub(-SCRUB_STEP_MS) },
            enabled = state.hasTracks,
            size = if (compact) 48.dp else 56.dp,
            glyphFraction = SKIP_GLYPH,
        )
        // Greyed when play would be refused, but never disabled: the press is what produces the
        // line explaining what to fix. Nothing to play and no headset where one is required look
        // the same, because to the user they are the same — the button will not start music.
        val refused = state.playRefusal != null
        GlyphButton(
            glyph = if (state.isPlaying) PauseGlyph else PlayGlyph,
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            unavailable = refused,
            size = if (compact) 66.dp else 78.dp,
            glyphFraction = 0.26f,
            tint = BACKGROUND,
            background = if (refused) PANEL else ACCENT,
        )
        GlyphButton(
            glyph = NextGlyph,
            contentDescription = "Next track. Hold to fast-forward.",
            onClick = onNext,
            onLongPress = { onScrub(SCRUB_STEP_MS) },
            enabled = state.hasTracks,
            size = if (compact) 48.dp else 56.dp,
            glyphFraction = SKIP_GLYPH,
        )
    }
}

/**
 * Lets a row run to the screen edges, ignoring the margin its parent sits inside.
 *
 * `SpaceEvenly` already makes the gap before the first item equal to the gaps between them — but
 * only within the box it is given. Inside the screen's own margin, that margin is added to the two
 * outer gaps and to nothing else, so the end buttons look pushed away from the edges while the
 * ones between them sit closer together. Measuring against the full width puts every gap, edges
 * included, on the same footing.
 *
 * The original width is still reported upwards, so the parent lays out as though nothing happened
 * and only the drawing overflows.
 */
private fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val margin = horizontal.roundToPx()
    val widened = constraints.maxWidth + margin * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = widened, maxWidth = widened)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-margin, 0) }
}

@Composable
private fun Utilities(
    state: PlayerUiState,
    panel: Panel,
    onPanel: (Panel) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onVoiceOver: () -> Unit,
    onVoiceOverLong: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().bleed(SCREEN_PADDING - GLYPH_INSET),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(
            glyph = ShuffleGlyph,
            contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
            onClick = onToggleShuffle,
            active = state.shuffle,
            glyphFraction = 0.36f,
        )
        GlyphButton(
            glyph = repeatGlyph(one = state.repeat == PlayerRepeatMode.ONE),
            contentDescription = when (state.repeat) {
                PlayerRepeatMode.OFF -> "Repeat off"
                PlayerRepeatMode.ALL -> "Repeat the whole vault"
                PlayerRepeatMode.ONE -> "Repeat this track"
            },
            onClick = onCycleRepeat,
            active = state.repeat != PlayerRepeatMode.OFF,
            glyphFraction = 0.36f,
        )
        // Greyed on the same terms as play, and pressable for the same reason: saying a track's
        // name out of the phone's own speaker is the thing "only play to headphones" exists to
        // stop, and the press is what produces the line naming what to plug in.
        GlyphButton(
            glyph = VoiceOverGlyph,
            contentDescription = "Announce the current track",
            onClick = onVoiceOver,
            onLongPress = onVoiceOverLong,
            active = state.isSpeaking,
            unavailable = state.voiceOverRefusal != null,
            glyphFraction = 0.40f,
        )
        GlyphButton(
            glyph = volumeGlyph(state.volumeFraction),
            contentDescription = "Volume",
            onClick = { onPanel(Panel.VOLUME) },
            active = panel == Panel.VOLUME,
            glyphFraction = 0.36f,
        )
        // The badge sits over the button rather than beside it, so the row keeps its spacing
        // whether or not a timer is running. It is not clickable, so the button underneath still
        // takes the whole touch target.
        Box(contentAlignment = Alignment.TopEnd) {
            GlyphButton(
                glyph = TimerGlyph,
                contentDescription = "Sleep timer",
                onClick = { onPanel(Panel.TIMER) },
                active = state.sleepTimerMs != null || panel == Panel.TIMER,
                glyphFraction = 0.34f,
            )
            state.sleepTimerMs?.let { remaining ->
                Text(
                    text = minutesLeft(remaining),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(DANGER)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * Minutes left, rounded up, in the same `15m` shorthand the timer panel offers them in.
 *
 * Rounding up means a fifteen-minute timer reads "15m" the moment it is set rather than "14m", and
 * never reads "0m" while it is still running.
 */
private fun minutesLeft(remainingMs: Long): String =
    ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L).toString() + "m"

/**
 * The surface both inline panels share.
 *
 * They open in place rather than over the screen: a sheet would cover the transport controls, and
 * these are things you reach for while the music is playing and you still want to see what it is
 * doing. Each opens directly under the button that summoned it.
 */
@Composable
private fun InlinePanelBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        content()
    }
}

@Composable
private fun VolumePanel(
    state: PlayerUiState,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
) {
    val muted = state.volume == 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Shows what it will do as much as what is true: crossed out while muted, waves otherwise.
        GlyphButton(
            glyph = volumeGlyph(state.volumeFraction),
            contentDescription = if (muted) "Unmute" else "Mute",
            onClick = onToggleMute,
            active = muted,
            size = 34.dp,
            glyphFraction = 0.40f,
            tint = MUTED,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(state.maxVolume) {
                    fun report(x: Float) {
                        val fraction = (x / size.width).coerceIn(0f, 1f)
                        onSetVolume(Math.round(fraction * state.maxVolume))
                    }
                    detectTapGestures { report(it.x) }
                }
                .pointerInput(state.maxVolume) {
                    detectHorizontalDragGestures { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSetVolume(Math.round(fraction * state.maxVolume))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                val y = size.height / 2f
                val height = 4.dp.toPx()
                drawLine(LINE, Offset(0f, y), Offset(size.width, y), height, StrokeCap.Round)
                val filled = size.width * state.volumeFraction
                if (filled > 0f) {
                    drawLine(ACCENT, Offset(0f, y), Offset(filled, y), height, StrokeCap.Round)
                }
                drawCircle(ACCENT, 7.dp.toPx(), Offset(filled, y))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "${Math.round(state.volumeFraction * 100)}%",
            color = MUTED,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun TimerPanel(state: PlayerUiState, onSetSleepTimer: (Int?) -> Unit) {
    Column {
        Text(
            text = state.sleepTimerMs?.let { "Stops in ${countdown(it)}" } ?: "Stop playing after",
            color = TEXT,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTimer.CHOICES.forEach { minutes ->
                Chip(
                    label = "${minutes}m",
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onSetSleepTimer(minutes) },
                )
            }
            if (state.sleepTimerMs != null) {
                Chip(
                    label = "Off",
                    selected = false,
                    danger = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onSetSleepTimer(null) },
                )
            }
        }
    }
}

@Composable
internal fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val edge = when {
        !enabled -> LINE.copy(alpha = 0.5f)
        selected -> ACCENT
        danger -> DANGER
        else -> LINE
    }
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(if (selected && enabled) ACCENT.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, edge, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> MUTED.copy(alpha = 0.5f)
                danger -> DANGER
                selected -> ACCENT
                else -> TEXT
            },
            fontSize = 13.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// -- The caption --------------------------------------------------------------------------------

/**
 * One line at the very bottom, ordered by urgency: a refusal to play, then the upload address
 * while the server is up, then the hint that gets a first-time user into the dock.
 */
@Composable
private fun Caption(state: PlayerUiState, compact: Boolean = false) {
    val web = state.web
    val caption: Pair<String, Color>? = when {
        state.notice != null -> state.notice to Color(0xFFC8A046)
        state.isImporting -> "Importing ${state.importsInFlight}…" to MUTED
        web.error != null -> web.error to DANGER
        else -> null
    }

    // Portrait holds the strip open even when it has nothing to say, so a notice arriving does
    // not shove the button row up the screen.
    //
    // Landscape cannot afford the reservation: the row is already as near the bottom edge as the
    // gesture area allows, and 20dp held empty is 20dp taken off the only thing that is short of
    // it. So there the strip grows into place when there is something to read, which the animation
    // turns from a jump into a slide.
    val height by animateDpAsState(
        targetValue = when {
            !compact -> 30.dp
            caption != null -> 22.dp
            else -> 0.dp
        },
        label = "caption",
    )

    Box(
        modifier = Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        if (caption != null) {
            Text(
                text = caption.first,
                color = caption.second,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

// -- Formatting ---------------------------------------------------------------------------------

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun countdown(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 1) "$minutes min" else "under a minute"
}

package com.nullplayer.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.data.Group
import com.nullplayer.data.GroupSummary
import com.nullplayer.data.Track
import com.nullplayer.data.TrackOrder
import com.nullplayer.playback.PlayerUiState
import com.nullplayer.playback.RepeatMode as PlayerRepeatMode
import com.nullplayer.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * mismatch is obvious: the play mark with a skip on either side, and that pair is what the eye
 * measures the middle one against.
 */
private const val SKIP_GLYPH = 0.25f

/** The order toggles' share of their button, on the same terms as [SKIP_GLYPH]. */
private const val TOGGLE_GLYPH = 0.36f

/**
 * How wide each transport mark is, edge to edge, in half-extents — the number the layout needs
 * to line marks up rather than the boxes they sit in. The skip marks and the play disc fill
 * their extent exactly; the shuffle and repeat marks are strokes, and count their round caps.
 */
private const val SHUFFLE_SPAN = 2.1f
private const val REPEAT_SPAN = 1.93f

/** How wide the dial pad is in half-extents, on the same terms as [SHUFFLE_SPAN]. */
private const val DIAL_SPAN = 1.84f

/**
 * The bottom bar's marks, each as (share of its button, width in half-extents), on the same terms
 * again. The tag runs tip to tail across its whole extent; the others are strokes and count their
 * caps.
 */
private const val TAG_GLYPH = 0.34f
private const val TAG_SPAN = 2f
private const val ORDER_SPAN = 2.32f
private const val VOLUME_GLYPH = 0.36f
private const val VOLUME_SPAN = 1.9f
private const val TIMER_GLYPH = 0.34f
private const val TIMER_SPAN = 1.81f

/**
 * How much of its icon the Material heart fills, side to side: its path runs from 2 to 22 of a
 * 24-unit box. The one imported mark on the screen, so the one whose width is a fact about the
 * artwork rather than about the drawing code beside it.
 */
private const val HEART_SPAN = 20f / 24f

/** The heart's size as a share of its button on the bottom row, where it sits with the tag. */
private const val HEART_GLYPH = 0.7f

/** The seeker's line. Named because the transport lines its ends up with the line's round caps. */
private val SEEKER_THICKNESS = 4.dp

/**
 * The top bar's two outer marks, on the same terms as [SHUFFLE_SPAN]: the globe's ring counts its
 * stroke, the cog reaches its tooth tips.
 */
private const val GLOBE_GLYPH = 0.367f
private const val GLOBE_SPAN = 1.98f
private const val SETTINGS_GLYPH = 0.36f
private const val SETTINGS_SPAN = 2f

/** The top bar's buttons, which are the default size; named because the edges below measure it. */
private val TOP_BUTTON = 44.dp

/**
 * The box the seeker's line sits in, as an inset from the screen's margin at each end: in by the
 * air inside the globe's button on the left and the cog's on the right, so the line's round caps
 * -- and every mark squared to them, above and below -- stand exactly under the top bar's two
 * outer marks. The screen then has one pair of edges rather than two a few dp apart.
 */
private val LINE_START = (TOP_BUTTON - TOP_BUTTON * GLOBE_GLYPH * GLOBE_SPAN) / 2 + SEEKER_THICKNESS / 2
private val LINE_END = (TOP_BUTTON - TOP_BUTTON * SETTINGS_GLYPH * SETTINGS_SPAN) / 2 + SEEKER_THICKNESS / 2

/** [LINE_START] and [LINE_END] as the padding that puts a full-width thing in that box. */
private fun Modifier.lineEdges() = padding(start = LINE_START, end = LINE_END)

/** The screen's own margin. */
private val SCREEN_PADDING = 20.dp

/**
 * How wide a chip is, as a share of the screen's shorter side.
 *
 * Chips are a fixed width so the carousel can centre any of them exactly, and that width is the
 * same one whichever way the phone is held: the shorter side is the shorter side in either
 * orientation, so a tile is the size the eye learned it at rather than one size lying down and
 * another stood up. Taken off the window rather than the inset content area for the same reason
 * -- the status bar comes off the height in landscape and the width in neither, and a tile that
 * followed the inset area would be a bar's width narrower on its side.
 */
private const val CHIP_FRACTION = 0.75f
private val CHIP_GAP = 12.dp

/**
 * How much narrower the upright ribbon's tiles are when the sleeve stands beside them.
 *
 * The one exception to a tile being one width everywhere: a landscape screen with the sleeve in
 * the middle is three columns across a height's worth of width, and a full-width tile would
 * leave the controls on the right a strip too narrow to use.
 */
private const val CHIP_BESIDE_ART = 0.7f

/** The sleeve's corners, and how far its glow reaches past its edge. */
private val ART_CORNER = 14.dp
private val ART_GLOW = 18.dp

/** The tile a track with no sleeve gets, and the mark on it: grey, and a darker grey. */
private val ART_BLANK = Color(0xFF34353B)
private val ART_MARK = Color(0xFF1E1F24)

/**
 * How much smaller the readout's digits are when the track's name stands over them. With a title
 * on the screen the number is no longer the one thing there is to read, and at its full size the
 * two fight for the eye.
 */
private const val DIGITS_UNDER_TITLE = 0.65f

/** Which of the inline panels, if any, is open under the button row. */
private enum class Panel { NONE, VOLUME, TIMER }

/**
 * The player.
 *
 * It fills whatever it is given rather than drawing a fixed-size object in the middle of the
 * screen: the ribbon and the progress bar stretch, and the control rows stay put at the bottom.
 *
 * Turned on its side it does not simply squash. The bars still run the whole width, but between
 * them the screen splits: the ribbon stands upright down the left edge, one tile wide, scrolling
 * the way the screen is now long, and the track and its controls take everything to the right of
 * it. Which is the same two things the portrait layout stacks, put side by side instead of one
 * above the other.
 *
 * Nothing here names a track unless asked to. The hero readout is a queue position and a clock,
 * and a tap on it — which is VoiceOver — is the only thing that will tell you what is playing;
 * until the "show track info" setting is turned on, which puts the sleeve, the title and the
 * artist above the readout: stacked over it in portrait, and in a column of their own between
 * the ribbon and the controls on a screen turned on its side.
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
    onSetOrder: (TrackOrder, Boolean) -> Unit,
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
    onApplyTags: (List<Track>, Set<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var panel by remember { mutableStateOf(Panel.NONE) }
    var showAddress by remember { mutableStateOf(false) }
    var goingToTrack by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }

    // Measured in two steps: the outer box is the whole window, which is what the chips take
    // their width from, and the inner one is what is left inside the system bars, which is what
    // everything else lays out in.
    BoxWithConstraints(modifier.fillMaxSize().background(BACKGROUND)) {
        val chipWidth = minOf(maxWidth, maxHeight) * CHIP_FRACTION

        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Wider than it is tall, with enough width that two halves are each still worth having:
            // a landscape phone, a tablet, a freeform window. Below that the stack reads better
            // than two cramped columns would.
            val sideBySide = maxWidth > maxHeight && maxWidth >= 480.dp
            val showInfo = state.settings.showTrackInfo && state.hasTracks

            // The two bars float over the content on glass, as they do on every other screen,
            // rather than boxing it in from above and below. On a phone on its side that is what
            // lets the upright ribbon run the full height of the screen and pass under both,
            // instead of stopping short of them with its end tiles cut off at a hard edge.
            GlassScaffold(
                topBar = { glass ->
                    TopBar(
                        state = state,
                        onOpenEqualizer = onOpenEqualizer,
                        onToggleWebServer = onToggleWebServer,
                        onShowAddress = { showAddress = true },
                        onOpenDock = onOpenDock,
                        onOpenSettings = onOpenSettings,
                        modifier = glass.fillMaxWidth().padding(
                            start = SCREEN_PADDING,
                            end = SCREEN_PADDING,
                            top = if (sideBySide) 6.dp else 12.dp,
                        ),
                    )
                },
                bottomBar = { glass ->
                    Column(
                        glass.fillMaxWidth().padding(
                            start = SCREEN_PADDING,
                            end = SCREEN_PADDING,
                            // Height is the scarce direction on a landscape screen, and a margin
                            // under the button row only pushes it up out of the thumb's reach. What
                            // is left below it there is the system's own gesture area, which is as
                            // near the edge as anything meant to be tapped should get. A portrait
                            // screen with a sleeve on it is short of height in the same way, and
                            // keeps a sliver.
                            bottom = if (sideBySide) 0.dp else if (showInfo) 4.dp else 12.dp,
                        )
                    ) {
                        // Both panels open directly above the button row -- over whatever is there,
                        // rather than in a slot of their own. Given a slot, a panel took its height
                        // out of the bar, and everything above jumped every time the volume was
                        // touched. The panel hangs outside the bar's box, so it draws over the page
                        // as a panel of its own rather than as part of the glass.
                        //
                        // A column of one rather than a box, because it is the column-scoped
                        // AnimatedVisibility that grows the panel vertically, and inside a box that
                        // name would reach for the outer column's scope, which the layout DSL
                        // forbids.
                        Column(Modifier.fillMaxWidth().floatAbove(12.dp)) {
                            AnimatedVisibility(visible = panel != Panel.NONE) {
                                InlinePanelBox {
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
                        }
                        Utilities(
                            state = state,
                            panel = panel,
                            onPanel = { panel = if (panel == it) Panel.NONE else it },
                            onTag = { tagging = true },
                            onToggleFavorite = onToggleFavorite,
                        )
                        Caption(state, compact = sideBySide, reserve = !sideBySide && !showInfo)
                    }
                },
            ) { top, bottom ->
                val showRibbon = state.settings.showRibbon
                if (sideBySide) {
                    Row(Modifier.fillMaxSize().padding(horizontal = SCREEN_PADDING)) {
                        // As wide as a tile and no wider. The ribbon used to take half the row, and
                        // a tile is a fixed width whatever it is given, so the other half of that
                        // half was margin -- taken off the transport and the seeker, which are the
                        // two things here that do grow into what they are given.
                        //
                        // The full height, bars included: the tiles centre on the screen and the
                        // ones at either end slide away under the glass.
                        if (showRibbon) {
                            val ribbonChip = if (showInfo) chipWidth * CHIP_BESIDE_ART else chipWidth
                            VaultRibbon(
                                groups = state.groups,
                                vaultCount = state.vaultCount,
                                favoriteCount = state.favoriteCount,
                                showFavorites = state.hasFavorites,
                                activeId = state.activeGroupId,
                                showCounts = state.settings.showVaultCounts,
                                onSelect = onSelectVault,
                                onOpen = onOpenVault,
                                chipWidth = ribbonChip,
                                modifier = Modifier.width(ribbonChip).fillMaxHeight(),
                                upright = true,
                            )
                            Spacer(Modifier.width(24.dp))
                        }
                        if (showInfo) {
                            // The sleeve is the middle column, as tall as the space between the
                            // bars and as wide as that makes it. The words about it go over the
                            // readout on the right rather than under the picture here, where
                            // they would take height from the one thing on this screen that has
                            // none to spare.
                            //
                            // The glow is let out of the square's box here: the box is as tall as
                            // the space between the bars, and an inset the glow's full reach would
                            // take a quarter of the picture's height. There is air on every side
                            // for it to spill into -- the gaps to the columns either side, and the
                            // glass of the bars above and below, which blurs whatever runs under.
                            BoxWithConstraints(
                                Modifier.fillMaxHeight().padding(top = top, bottom = bottom),
                                contentAlignment = Alignment.Center,
                            ) {
                                Artwork(
                                    state = state,
                                    modifier = Modifier.size(minOf(maxWidth, maxHeight)),
                                    inset = 4.dp,
                                )
                            }
                            Spacer(Modifier.width(24.dp))
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = top, bottom = bottom),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (showInfo) {
                                TrackInfo(state, compact = true)
                                Spacer(Modifier.height(2.dp))
                            }
                            if (state.hasTracks) {
                                // Weighted, so it is measured last and takes what is left rather
                                // than what it wants. A column measures its children in order, and
                                // the readout coming first meant it took its full height out of a
                                // column that did not have it -- leaving the controls underneath a
                                // few pixels to live in. The transport and the seeker survived that
                                // by drawing outside their bounds; the seeker's digits, which clip
                                // to theirs, simply vanished. The controls are the part that must
                                // not be squeezed, so they are the part measured first.
                                Box(Modifier.weight(1f, fill = false)) {
                                    Hero(
                                        state = state,
                                        compact = true,
                                        underTitle = showInfo,
                                        onOrder = { ordering = true },
                                        onGoToTrack = { goingToTrack = true },
                                        onVoiceOver = onVoiceOver,
                                        onVoiceOverLong = onVoiceOverLong,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            Controls(
                                state = state,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                onPrevious = onPrevious,
                                onScrub = onScrub,
                                onSeek = onSeek,
                                onToggleTimeMode = onToggleTimeMode,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                compact = true,
                            )
                        }
                    }
                } else {
                    // With the sleeve on the screen every gap in this column is taken in, and the
                    // ribbon's tiles drop to a line: the picture is the thing that grows into
                    // what is left, and each dp of air above and below it is a dp off its side.
                    Column(
                        Modifier.fillMaxSize().padding(
                            start = SCREEN_PADDING,
                            end = SCREEN_PADDING,
                            top = top + if (showInfo) 4.dp else 12.dp,
                            bottom = bottom,
                        )
                    ) {
                        // Edge to edge: the ribbon is a carousel, and a tile sliding in from the
                        // side is meant to come from the side, not to appear at the margin the rest
                        // of the screen keeps. Inside that margin the row clipped every tile that
                        // crossed it, which showed the moment tiles started to move.
                        if (showRibbon) {
                            VaultRibbon(
                                groups = state.groups,
                                vaultCount = state.vaultCount,
                                favoriteCount = state.favoriteCount,
                                showFavorites = state.hasFavorites,
                                activeId = state.activeGroupId,
                                showCounts = state.settings.showVaultCounts,
                                onSelect = onSelectVault,
                                onOpen = onOpenVault,
                                chipWidth = chipWidth,
                                modifier = Modifier.bleed(SCREEN_PADDING),
                                slim = showInfo,
                            )
                        }

                        val readout: @Composable ColumnScope.() -> Unit = {
                            if (state.hasTracks) {
                                Hero(
                                    state = state,
                                    underTitle = showInfo,
                                    onOrder = { ordering = true },
                                    onGoToTrack = { goingToTrack = true },
                                    onVoiceOver = onVoiceOver,
                                    onVoiceOverLong = onVoiceOverLong,
                                )
                                Spacer(Modifier.height(if (showInfo) 0.dp else 10.dp))
                            }
                            Controls(
                                state = state,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                onPrevious = onPrevious,
                                onScrub = onScrub,
                                onSeek = onSeek,
                                onToggleTimeMode = onToggleTimeMode,
                                onToggleShuffle = onToggleShuffle,
                                onCycleRepeat = onCycleRepeat,
                                tight = showInfo,
                            )
                        }

                        if (showInfo) {
                            // The sleeve, the words and the controls are a column of their own,
                            // filling what the ribbon leaves and centring its contents in it. A
                            // picture bounded by the width rather than the height leaves spare
                            // height, and this is what puts it above and below the group evenly
                            // rather than in one lump -- while leaving the ribbon where it is.
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                // The sleeve takes at most everything between the ribbon -- or
                                // the top bar, with the ribbon off -- and the words about it, as
                                // the largest square that fits there.
                                //
                                // Sized by hand from the box rather than with `aspectRatio`,
                                // which is handed fixed constraints here that no square can
                                // satisfy, and overflows rather than shrinks when that happens.
                                // Weighted without filling, so that it is measured last, with
                                // what the words and the controls leave, and wraps the square
                                // rather than the space. The glow is let all the way out of the
                                // box: the square's edges then stand on the screen's margin, a
                                // shade past the outer marks of the top bar, and the glow runs
                                // on into the margin -- which is what a margin is for, and the
                                // one place on this screen with nothing else in it. Above and
                                // below, the box keeps enough air for the glow to fade before it
                                // reaches the ribbon's edge or the title.
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Artwork(
                                        state = state,
                                        modifier = Modifier.size(minOf(maxWidth, maxHeight)),
                                        inset = 0.dp,
                                    )
                                }
                                TrackInfo(state)
                                Spacer(Modifier.height(2.dp))
                                readout()
                                Spacer(Modifier.height(6.dp))
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                            readout()
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // Guarded on there being a queue as well as on the flag: the tile can be switched for
            // an empty one from the ribbon while the dialog is open, and "go to track 1 of 0" is
            // not a question worth leaving on the screen.
            TagDialogFor(
                state = state,
                showing = tagging,
                onReadSharedGroups = onReadSharedGroups,
                onApplyTags = onApplyTags,
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

            // Unguarded on there being a queue, unlike the two above: an order is a property of the
            // tile rather than of what happens to be in it, and an empty group is exactly where you
            // might set one before filling it.
            if (ordering) {
                OrderDialog(
                    tileName = state.activeName,
                    order = state.order,
                    reversed = state.orderReversed,
                    onSave = { chosen, backwards ->
                        onSetOrder(chosen, backwards)
                        ordering = false
                    },
                    onDismiss = { ordering = false },
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
    modifier: Modifier = Modifier,
) {
    // Every mark in this row is drawn to the same height, and each one needs a fraction of its own
    // to get there: a fraction is a share of the button, and what each glyph then does with the box
    // it is handed differs. The cog fills its box top to bottom, the globe's circle stops just
    // short, and the shelf is shorter still. The four numbers below are measured rather than
    // reasoned — they come from the rendered marks — because the stroke a glyph is drawn with sits
    // astride its edge and so adds half its width to the height, which is easy to forget and
    // invisible until the row is next to itself. Matching the fractions instead would leave four
    // icons of four different sizes, which is the first thing the eye reads in a row like this.
    Row(
        modifier = modifier,
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
                glyphFraction = GLOBE_GLYPH,
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
            glyphFraction = 0.41f,
        )
        GlyphButton(
            glyph = EqualizerGlyph,
            contentDescription = "Equalizer",
            onClick = onOpenEqualizer,
            active = state.settings.equalizerEnabled,
            glyphFraction = 0.367f,
        )
        GlyphButton(
            glyph = SettingsGlyph,
            contentDescription = "Open settings",
            onClick = onOpenSettings,
            glyphFraction = SETTINGS_GLYPH,
        )
    }
}

// -- The hero -----------------------------------------------------------------------------------

/**
 * The sleeve: the picture in the track's tags, in a rounded square with a glow of the accent
 * around it — the play button's halo, dimmed, so the two green things on the screen are plainly
 * kin and plainly not the same thing.
 *
 * A track with no picture gets the same square in grey with the app's own mark on it -- the
 * launcher's empty set, in a darker grey -- so the layout stands still as the queue moves through
 * tracks that have one and tracks that do not, and the blank reads as a blank rather than as a
 * picture that has not loaded. And the picture is only drawn once it is the current track's: for
 * the beat between a track change and its sleeve being read, the square is the empty one rather
 * than the last track's.
 *
 * The glow is a blurred copy of the square drawn under it. A radial gradient, which is what the
 * play button uses, is the wrong shape for corners; a blur is the right shape for anything.
 */
@Composable
private fun Artwork(state: PlayerUiState, modifier: Modifier = Modifier, inset: Dp = ART_GLOW) {
    val picture = state.artwork?.takeIf { state.artworkTrackId == state.currentTrack?.id }
    val glow = ACCENT.copy(alpha = 0.6f)
    val reach = with(LocalDensity.current) { ART_GLOW.toPx() }
    val halo = remember(reach) {
        Paint().apply {
            color = glow.toArgb()
            maskFilter = BlurMaskFilter(reach, BlurMaskFilter.Blur.NORMAL)
        }
    }
    Box(
        modifier = modifier
            // Inset by the glow's reach by default, so the glow lives inside the square's own
            // box rather than over whatever is laid out next to it; a caller with air around
            // the box can ask for less and let the glow spill into it.
            .padding(inset)
            .drawBehind {
                val corner = ART_CORNER.toPx()
                drawIntoCanvas {
                    it.nativeCanvas.drawRoundRect(
                        0f, 0f, size.width, size.height, corner, corner, halo,
                    )
                }
            }
            .clip(RoundedCornerShape(ART_CORNER))
            .background(ART_BLANK),
    ) {
        if (picture != null) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = "Album art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            NullMark(Modifier.fillMaxSize())
        }
    }
}

/**
 * The launcher's mark, drawn rather than loaded: a ring, and a slash through it that runs on past
 * the ring by the same share of its radius as the launcher's does. The launcher's ring is 22 of a
 * 108 canvas with an 8 stroke, and its slash reaches 37.5 from the centre; those are the ratios
 * here, on a ring a fifth of the tile's side.
 */
@Composable
private fun NullMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension
        val radius = side * 0.2f
        val stroke = radius * (8f / 22f)
        val reach = radius * (37.5f / 22f) / 1.41421356f
        drawCircle(color = ART_MARK, radius = radius, center = center, style = Stroke(stroke))
        drawLine(
            color = ART_MARK,
            start = Offset(center.x - reach, center.y + reach),
            end = Offset(center.x + reach, center.y - reach),
            strokeWidth = stroke,
        )
    }
}

/**
 * The title and the artist, one line each, the title bold and the artist quieter under it.
 *
 * Each is a single line cut with an ellipsis rather than wrapped: the words sit in a column
 * whose height is spoken for by the readout and the controls, and a long title that wrapped to
 * three lines would take it from them. [compact] is the landscape size, as with [Hero] -- and
 * the landscape alignment: centred under the sleeve in portrait, where the two share an axis,
 * and set against the left edge beside it in landscape, where the words start where the
 * picture they belong to ends.
 */
@Composable
private fun TrackInfo(state: PlayerUiState, compact: Boolean = false) {
    val track = state.currentTrack ?: return
    val align = if (compact) TextAlign.Start else TextAlign.Center
    Column(
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().lineEdges(),
    ) {
        Text(
            text = track.title ?: "Untitled",
            color = TEXT,
            fontSize = if (compact) 18.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        val artist = track.artist
        if (artist != null) {
            Spacer(Modifier.height(if (compact) 1.dp else 2.dp))
            Text(
                text = artist,
                color = MUTED,
                fontSize = if (compact) 12.sp else 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Where you are in the queue, and nothing else — until it is pressed.
 *
 * The figure is VoiceOver's button: a tap says what is playing, a hold says where in the queue it
 * is and how long it runs. The thing you look at to wonder what is playing is the thing you press
 * to be told, and there is no separate face on the bottom row to learn. The digits stay white
 * whatever it is doing: they are the position first, and a button second.
 *
 * [compact] is the landscape size. The readout is still the largest thing on the screen, but a
 * screen turned on its side has barely half the height to spend and the position is worth less
 * than the controls under it — so this is what gives way first.
 *
 * [underTitle] says the track's name is standing over this, and takes the digits down by
 * [DIGITS_UNDER_TITLE]. The buttons at the ends of the row keep their size: they are targets,
 * and a target does not get smaller because there is more to look at.
 */
@Composable
private fun Hero(
    state: PlayerUiState,
    compact: Boolean = false,
    underTitle: Boolean = false,
    onOrder: () -> Unit,
    onGoToTrack: () -> Unit,
    onVoiceOver: () -> Unit,
    onVoiceOverLong: () -> Unit,
) {
    // An empty vault has no position to report, and saying so twice — here and on the greyed-out
    // transport below — is one line of chrome more than it is worth.
    if (!state.hasTracks) return

    val target = if (compact) 38.dp else 46.dp
    val scale = if (underTitle) DIGITS_UNDER_TITLE else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = if (underTitle) 0.dp else if (compact) 2.dp else 6.dp),
    ) {
        // The figure on the centre line, and a button at each end of the row, standing where the
        // seeker's line ends: the dial pad asks where in the queue to go, the order button which
        // way the queue runs. The two marks are drawn to one width, so they weigh the same at
        // the two ends; and each is pushed out by the air inside its button so that it is the
        // mark, not the box around it, that meets the line's end -- and by the half-thickness
        // the line's round caps reach past their own box.
        val mark = if (compact) 15.dp else 17.dp
        val overhang = (target - mark) / 2 + SEEKER_THICKNESS / 2
        Box(Modifier.fillMaxWidth().lineEdges()) {
            GlyphButton(
                glyph = DialPadGlyph,
                contentDescription = "Go to a track",
                onClick = onGoToTrack,
                size = target,
                glyphFraction = mark / DIAL_SPAN / target,
                tint = MUTED,
                modifier = Modifier.align(Alignment.CenterStart).offset(x = -overhang),
            )
            // Pressable even when VoiceOver would be refused: saying a track's name out of the
            // phone's own speaker is the thing "only play to headphones" exists to stop, and the
            // press is what produces the line naming what to plug in.
            //
            // Three pieces rather than one string: where we are is set bold and the total light,
            // so the figure that changes is the one that is read, and the slash between them is
            // drawn rather than typed -- a hairline taller than the digits, standing between
            // them with a few dp of air. Set in the sans with its tabular figures, like every
            // other number, so the pair holds its width as the position counts up.
            val digits = (if (compact) 34.sp else 46.sp) * scale
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    // Tap gestures by hand rather than `combinedClickable`, which is still
                    // experimental in this Foundation, and to match how [GlyphButton] does it.
                    .pointerInput(onVoiceOver, onVoiceOverLong) {
                        detectTapGestures(
                            onTap = { onVoiceOver() },
                            onLongPress = { onVoiceOverLong() },
                        )
                    }
                    .semantics {
                        contentDescription = "Announce the current track"
                        onClick(label = "Announce the current track") { onVoiceOver(); true }
                        onLongClick(label = "Announce the queue position") {
                            onVoiceOverLong()
                            true
                        }
                    }
                    // The target reaches past the digits on every side, for the press that
                    // lands beside them rather than on them.
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // Digits and a slash, and nothing that descends below the baseline -- so the
                // leading a line box reserves by default is height held for characters this
                // text cannot contain. Portrait can afford to leave it; landscape cannot.
                val lineHeight = if (compact) 36.sp * scale else TextUnit.Unspecified
                Text(
                    text = queueHere(state),
                    color = TEXT,
                    fontSize = digits,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    style = TABULAR,
                )
                Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                Slash(
                    height = (if (compact) 42.dp else 58.dp) * scale,
                    stroke = if (compact) 1.5.dp else 2.dp,
                )
                Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                Text(
                    text = queueTotal(state),
                    color = TEXT,
                    fontSize = digits,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    style = TABULAR,
                )
            }
            // Deliberately not lit for a non-default order: this is a door into a dialog, and lit
            // it read as a toggle that was on. Which order is in force is said in the dialog.
            GlyphButton(
                glyph = OrderByGlyph,
                contentDescription = if (state.orderReversed) {
                    "Order this group, currently reversed"
                } else {
                    "Order this group"
                },
                onClick = onOrder,
                size = target,
                glyphFraction = mark / ORDER_SPAN / target,
                tint = MUTED,
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = overhang),
            )
        }
    }
}

/**
 * What the file is, set like the clocks it sits between: worth a glance when you wonder what you
 * are listening to, and no more than that the rest of the time. It arrives a beat after the
 * track starts, so nothing is drawn until it does.
 *
 * Gold for a high-resolution file, which is the one fact in the line worth catching at a glance:
 * the line says what the file is, and the tint says it is rare.
 */
@Composable
private fun Quality(state: PlayerUiState, compact: Boolean, modifier: Modifier = Modifier) {
    val profile = state.audioProfile ?: return
    Text(
        text = profile.summary,
        color = if (profile.hiRes) HI_RES else MUTED,
        // The clocks' size and line box, so that beside them it sits on the same line they do.
        fontSize = if (compact) 10.sp else 12.sp,
        lineHeight = if (compact) TIME_LINE_COMPACT else TIME_LINE,
        letterSpacing = 0.5.sp,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
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
    onApplyTags: (List<Track>, Set<String>, Boolean) -> Unit,
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
        onApply = { groupIds, newGroup -> onApplyTags(listOf(track), groupIds, newGroup) },
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
 * How the tile on the ribbon lays its tracks out.
 *
 * Named after the tile rather than titled "Sort", because this is the one dialog in the app that
 * changes something about a group instead of about the app, and the group it changes is whichever
 * one the ribbon happens to be on. "Order Group #3" says that; "Sort" does not.
 *
 * Both answers are taken on Save rather than applied as they are touched. Each one rebuilds the
 * queue, and a user reading four options would otherwise have the numbers under the dialog
 * reshuffle three times on the way to the one they meant.
 */
@Composable
private fun OrderDialog(
    tileName: String,
    order: TrackOrder,
    reversed: Boolean,
    onSave: (TrackOrder, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(order) }
    var backwards by remember { mutableStateOf(reversed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL,
        titleContentColor = TEXT,
        textContentColor = MUTED,
        title = { Text("Order $tileName") },
        text = {
            Column {
                TrackOrder.entries.forEach { entry ->
                    ChoiceRow(
                        title = entry.label,
                        selected = chosen == entry,
                        onSelect = { chosen = entry },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reverse", color = TEXT, fontSize = 15.sp)
                        Text(orderHint(chosen, backwards), color = MUTED, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    NullSwitch(checked = backwards, onCheckedChange = { backwards = it })
                }
                // Worth saying once here rather than leaving it to be discovered: the two are not
                // in competition, and a user who has just picked "Artist" and still hears the
                // library jumping about would reasonably think this dialog had not worked.
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Shuffle still decides what plays next. This is the order the tracks " +
                        "are numbered in.",
                    color = MUTED,
                    // Smaller than the rows above, and smaller than their subtitles: it is a
                    // footnote about the whole dialog rather than a label on anything in it, and
                    // at the same size it competed with the choices for the first read.
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen, backwards) }) { Text("Save", color = ACCENT) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) }
        },
    )
}

/** Which end the chosen order starts at, in the words that order makes sense in. */
private fun orderHint(order: TrackOrder, reversed: Boolean): String = when (order) {
    TrackOrder.ADDED -> if (reversed) "Newest first." else "Oldest first."
    TrackOrder.RELEASED -> if (reversed) "Latest first." else "Earliest first."
    else -> if (reversed) "Z to A." else "A to Z."
}

/**
 * The readout's slash: one thin line leaning the way a typed one does, at [height] and [stroke].
 *
 * Its lean is fixed at [SLASH_LEAN] of its height, which is about the angle the digits' own face
 * would strike it at, so it reads as punctuation between them and not as a rule.
 */
@Composable
private fun Slash(height: Dp, stroke: Dp) {
    Canvas(Modifier.size(width = height * SLASH_LEAN + stroke, height = height)) {
        val inset = stroke.toPx() / 2f
        drawLine(
            color = TEXT,
            start = Offset(inset, size.height - inset),
            end = Offset(size.width - inset, inset),
            strokeWidth = stroke.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** How far the slash leans, as a share of its height. */
private const val SLASH_LEAN = 0.36f

/** How many tracks the queue holds, as the readout states it. */
internal fun queueTotal(state: PlayerUiState): String = state.tracks.size.toString()

/**
 * Where in the queue we are, padded with zeros to the width of the total — `08` of `99`, `008` of
 * `999` — so the figure keeps its shape from one track to the next instead of jumping a digit at
 * ten and at a hundred, and the two numbers line up as the pair they are. A queue of fewer than
 * ten needs no padding, and gets none.
 *
 * A queue with nothing chosen yet still knows how much it holds, so only the position is
 * withheld — with a dash for each digit it would have had.
 */
internal fun queueHere(state: PlayerUiState): String {
    val width = queueTotal(state).length
    return if (state.trackIndex < 0) {
        "-".repeat(width.coerceAtLeast(2))
    } else {
        (state.trackIndex + 1).toString().padStart(width, '0')
    }
}

/**
 * [queueHere] and [queueTotal] as one string, for the mini player, where the figure is a label
 * set at 12pt rather than a display and the slash wants no air around it.
 */
internal fun queuePosition(state: PlayerUiState): String =
    queueHere(state) + "/" + queueTotal(state)

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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    compact: Boolean = false,
    tight: Boolean = false,
) {
    // The seeker sits over the transport, with the clocks and the small print over that: read
    // from the top down it goes where you are, how far through, and only then the buttons —
    // which are also what the thumb reaches first from the bottom of the screen.
    //
    // [tight] is the portrait screen with a sleeve on it, which wants its height back: the
    // gap under the seeker closes to about the landscape one, at the landscape's full size.
    Column(Modifier.fillMaxWidth().lineEdges()) {
        if (state.settings.showSeeker) {
            Progress(
                state = state,
                onSeek = onSeek,
                onToggleTimeMode = onToggleTimeMode,
                compact = compact,
                tight = tight,
            )
            Spacer(Modifier.height(if (compact) 5.dp else if (tight) 8.dp else 18.dp))
        } else if (state.audioProfile != null) {
            // With no seeker there are no clocks to sit between, but the small print still has
            // something to say, and the middle of the line is where it would have been.
            Quality(state, compact, Modifier.fillMaxWidth())
            Spacer(Modifier.height(if (compact) 5.dp else if (tight) 6.dp else 12.dp))
        }
        Transport(
            state = state,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onScrub = onScrub,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            compact = compact,
        )
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
 * the screen instead of across the top of it. [chipWidth] is the one width every tile is, either
 * way up; the screen works it out from the window so that it is the same in both.
 *
 * [slim] is the portrait ribbon with a sleeve under it, which wants the height: the tiles put
 * their count beside their name rather than under it, and come down to one line.
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
    chipWidth: Dp,
    modifier: Modifier = Modifier,
    upright: Boolean = false,
    slim: Boolean = false,
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
        // How big a tile is along the axis this ribbon scrolls. Lying down that is [chipWidth],
        // which every chip is whatever is written in it. Stood upright it is the height, and the
        // height is the axis the text grows along — so it is measured off the list rather than
        // stated here. It used to be a constant, 72dp, which is what a name and a count come to at
        // the default font scale and nowhere near it above: a reader who had turned their text up
        // got the count cropped off the bottom of every tile in landscape, and the name shoved off
        // centre by the overflow doing it.
        val density = LocalDensity.current
        var uprightExtent by remember { mutableStateOf<Dp?>(null) }
        LaunchedEffect(listState, density) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size }
                .collect { size ->
                    if (size != null && size > 0) uprightExtent = with(density) { size.toDp() }
                }
        }

        // Half a row minus half a tile, which is what puts any tile — including the first and the
        // last — in the centre when it is scrolled to the start of the content area. Measured
        // along whichever axis the ribbon actually scrolls.
        val tileExtent = if (upright) uprightExtent ?: 0.dp else chipWidth
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

        // Keyed on the extent as well, so the first placement waits for the measurement: putting
        // a tile in the middle of a list still padded for a tile of no height would land it
        // somewhere else and then count itself done.
        LaunchedEffect(activeId, tiles.size, tileExtent) {
            if (upright && uprightExtent == null) return@LaunchedEffect
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

        // Whether a finger has ever moved this ribbon. Until one has, whatever tile sits in the
        // middle was put there by the placement above, not chosen, and is not to be committed.
        // The case that matters is arriving here with a selection still in flight — a play button
        // on the library or the dock moves the queue through settings and switches to this
        // screen in the same breath, and the ribbon composes on the old tile before the new one
        // has been read back. Committing what it happened to compose on undid the selection
        // just made.
        var dragged by remember { mutableStateOf(false) }
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) dragged = true
            }
        }

        // Committing the queue trails the highlight by a breath. Switching tiles rebuilds the
        // player's queue, so doing it for every tile a fling flies past would restart playback
        // several times on the way to the one the user actually meant; the debounce keeps a single
        // swipe feeling immediate while a long fling only commits where it lands.
        LaunchedEffect(listState, tiles) {
            snapshotFlow { listState.centredIndex()?.let(tiles::getOrNull)?.id }
                .distinctUntilChanged()
                .debounce(SELECT_COMMIT_MS)
                .collect { settled ->
                    if (dragged && settled != null && settled != current) onSelect(settled)
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
                        slim = slim,
                        width = chipWidth,
                        onClick = { onOpen(tile.id) },
                        modifier = Modifier.animateItem(),
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
                        slim = slim,
                        width = chipWidth,
                        onClick = { onOpen(tile.id) },
                        modifier = Modifier.animateItem(),
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

/**
 * One tile of the ribbon.
 *
 * [modifier] is where the list hands in its item animation: the Favorites tile appears the
 * first time a track is marked and goes when the last mark is taken off, and without it the
 * tile simply popped into the row and the groups after it jumped a slot. With it the tile fades
 * in and the groups slide over to make room -- and back, the other way.
 */
@Composable
private fun VaultChip(
    tile: Tile,
    selected: Boolean,
    showCount: Boolean,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slim: Boolean = false,
) {
    val colour = Color(tile.colorArgb)
    // Unselected tiles are the same colour laid over the page, so the text colour is decided
    // against what is actually behind the letters rather than against the pure swatch.
    //
    // Everything that changes with the highlight is animated, so a tile crossing the centre of
    // the ribbon lights up over a beat rather than snapping: the highlight follows the scroll
    // every frame, and a snap on every crossing made the ribbon flicker under a slow drag. The
    // ink is animated too, because it flips between black and white with the fill and would
    // otherwise jump while the fill it is read against is still on its way.
    val target = if (selected) colour else colour.copy(alpha = 0.34f).compositeOver(BACKGROUND)
    val fill by animateColorAsState(target, tween(CHIP_FADE_MS), label = "fill")
    val ink by animateColorAsState(readableOn(target), tween(CHIP_FADE_MS), label = "ink")
    val edge by animateColorAsState(if (selected) colour else LINE, tween(CHIP_FADE_MS), label = "edge")
    val edgeWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CHIP_FADE_MS), label = "edgeWidth")

    Column(
        modifier = modifier
            // Wide by rule, tall by what is in it. Pinning the height as well is what cropped the
            // upright ribbon's tiles; the carousel gets the fixed extent it needs by measuring.
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .border(width = edgeWidth, color = edge, shape = RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = if (slim) 9.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val count = if (showCount) itemCountLabel(tile.itemCount) else null
        if (slim && count != null) {
            // One line: the name, and the count after it on the same baseline, in the size and
            // shade it has under the name on a full-height tile. The name is the part that
            // gives way, so a long one is cut rather than the count pushed off the tile.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    text = tile.name,
                    color = ink,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                Text(
                    text = "  ·  $count",
                    color = ink.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        } else {
            Text(
                text = tile.name,
                color = ink,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = count,
                    color = ink.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** How long a ribbon tile takes to light up or go out as the highlight passes over it. */
private const val CHIP_FADE_MS = 220

/** Named apart from the lazy-list `items` builder, which is in scope wherever a ribbon is. */
private fun itemCountLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

/** The progress bar. Draggable, and wide enough to be worth dragging. */
@Composable
private fun Progress(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
    onToggleTimeMode: () -> Unit,
    compact: Boolean = false,
    tight: Boolean = false,
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val seekable = state.durationMs > 0
    val shown = if (dragFraction >= 0f) dragFraction else state.progress
    val busy = rememberBusy(state)

    // The seeker is the tallest thing here that is mostly air: a 28dp band around a 2dp line, so
    // that it can be dragged without precision. Landscape cannot afford all of it -- the whole
    // column is shorter than the pieces want -- and a 22dp band is still a comfortable target.
    val band = if (compact) 22.dp else 28.dp

    val bar: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier
                .height(band)
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
                modifier = Modifier.fillMaxWidth().height(band),
                thickness = SEEKER_THICKNESS,
                thumbRadius = when {
                    !seekable -> 0.dp
                    dragFraction >= 0f -> 9.dp
                    else -> 6.dp
                },
            )
        }
    }

    val elapsed: @Composable () -> Unit = {
        Text(
            text = clock((shown * state.durationMs).toLong()),
            color = MUTED,
            fontSize = if (compact) 10.sp else 12.sp,
            lineHeight = if (compact) TIME_LINE_COMPACT else TIME_LINE,
            style = TABULAR,
        )
    }

    // The padding is the touch target. It grows inward and upward, away from the edge the label
    // is pinned to and away from the bar under it; landscape has no height to give it, and a
    // portrait screen with a sleeve on it gives a third of it.
    val remaining: @Composable () -> Unit = {
        Text(
            text = remainder(state, shown),
            color = MUTED,
            fontSize = if (compact) 10.sp else 12.sp,
            lineHeight = if (compact) TIME_LINE_COMPACT else TIME_LINE,
            style = TABULAR,
            modifier = Modifier
                .clickable(
                    // No ripple and no shape: this is a line of text that answers a second
                    // question when asked, not a button pretending to be one.
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleTimeMode,
                )
                .padding(
                    start = if (compact) 0.dp else 24.dp,
                    top = if (compact) 0.dp else if (tight) 4.dp else 12.dp,
                ),
        )
    }

    // The clocks over the bar, pinned to its two ends, with what the file is between them: the
    // three facts about the track that change together, on one line, and the bar they describe
    // directly under it. The small print is centred on the screen rather than between the
    // clocks, which are not the same width, so it stays on the line the readout and the play
    // button share.
    //
    // The band is mostly air above and below its line, and the air above it is tucked up under
    // the clocks: the line then sits close beneath them, and the band is still all there to be
    // dragged, it just overlaps the foot of the row it follows.
    Column(Modifier.fillMaxWidth()) {
        // Aligned by their feet: the remaining clock carries its touch target as padding above
        // it, and centring the three would drop that one clock by half of it.
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.BottomStart)) { elapsed() }
            Quality(state, compact, Modifier.align(Alignment.BottomCenter))
            Box(Modifier.align(Alignment.BottomEnd)) { remaining() }
        }
        bar(Modifier.fillMaxWidth().tuckUnder(if (compact) 3.dp else 6.dp))
    }
}

/**
 * A tight line box for the seeker's digits.
 *
 * Left at its default, a 12sp line reserves around 25dp for ascenders and descenders that "0:00"
 * does not have -- taller than the 22dp band the digits sit beside in landscape, so they overflowed
 * their own bounds and the bottom of them was cut off by the edge of the column.
 */
private val TIME_LINE = 14.sp

/** The same again for landscape, where the digits are a size smaller. */
private val TIME_LINE_COMPACT = 12.sp

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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    compact: Boolean = false,
) {
    // The two order toggles bracket the transport, smaller than the steps they sit outside of:
    // the row reads as a hierarchy from its middle out, and what each toggle does is a matter of
    // which way the queue runs from here, which is what its neighbour is for.
    val toggle = if (compact) 40.dp else 44.dp
    val skip = if (compact) 48.dp else 56.dp
    val play = if (compact) 56.dp else 78.dp

    // Laid out by the marks, not the buttons: the outer two sit flush with the ends of the seeker
    // under them, and the air between any two neighbours is the same. The buttons are all
    // different sizes and their marks fill them by different amounts, so spacing the boxes
    // evenly would put the marks anywhere but.
    MarkRow(
        markWidths = listOf(
            toggle * TOGGLE_GLYPH * SHUFFLE_SPAN,
            skip * SKIP_GLYPH * 2f,
            play,
            skip * SKIP_GLYPH * 2f,
            toggle * TOGGLE_GLYPH * REPEAT_SPAN,
        ),
        // The seeker's line ends in round caps that reach half its thickness past its box.
        modifier = Modifier.fillMaxWidth().bleed(SEEKER_THICKNESS / 2),
    ) {
        GlyphButton(
            glyph = ShuffleGlyph,
            contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
            onClick = onToggleShuffle,
            active = state.shuffle,
            size = toggle,
            glyphFraction = TOGGLE_GLYPH,
        )
        GlyphButton(
            glyph = PreviousGlyph,
            contentDescription = "Previous track. Hold to rewind.",
            onClick = onPrevious,
            onLongPress = { onScrub(-SCRUB_STEP_MS) },
            enabled = state.hasTracks,
            size = skip,
            glyphFraction = SKIP_GLYPH,
        )
        // Greyed when play would be refused, but never disabled: the press is what produces the
        // line explaining what to fix. Nothing to play and no headset where one is required look
        // the same, because to the user they are the same — the button will not start music.
        //
        // The accent goes on the mark and the halo, not the disc: a black button with a green
        // glow is found in the dark by the glow, and the mark inside it stays the colour that
        // means "go" everywhere else on the screen.
        val refused = state.playRefusal != null
        GlyphButton(
            glyph = rememberPlayPauseGlyph(state.isPlaying),
            contentDescription = if (state.isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            unavailable = refused,
            size = play,
            glyphFraction = 0.26f,
            tint = ACCENT,
            background = if (refused) PANEL else BACKGROUND,
            glow = if (refused) Color.Transparent else ACCENT.copy(alpha = 0.9f),
        )
        GlyphButton(
            glyph = NextGlyph,
            contentDescription = "Next track. Hold to fast-forward.",
            onClick = onNext,
            onLongPress = { onScrub(SCRUB_STEP_MS) },
            enabled = state.hasTracks,
            size = skip,
            glyphFraction = SKIP_GLYPH,
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
            size = toggle,
            glyphFraction = TOGGLE_GLYPH,
        )
    }
}

/**
 * A row spaced by what is drawn in its children rather than by the children themselves.
 *
 * [markWidths] gives, for each child in order, how wide the visible mark at its centre is. The
 * first mark starts at the row's left edge, the last ends at its right, and the gaps between
 * marks are all equal; each child's box is then centred on its mark, which puts the outer boxes
 * partly outside the row. Nothing here clips, so they draw and take touches all the same.
 */
@Composable
private fun MarkRow(
    markWidths: List<Dp>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val marks = markWidths.map { it.toPx() }
        val width = constraints.maxWidth
        val height = placeables.maxOf { it.height }
        val gap = (width - marks.sum()) / (placeables.size - 1).coerceAtLeast(1)
        layout(width, height) {
            var cursor = 0f
            placeables.forEachIndexed { i, placeable ->
                val x = cursor - (placeable.width - marks[i]) / 2f
                placeable.place(x.roundToInt(), (height - placeable.height) / 2)
                cursor += marks[i] + gap
            }
        }
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

/**
 * Lets a panel hang over whatever comes before it in a column, instead of taking a slot of its own.
 *
 * Measured at its full size, reported as taking none, and placed with its foot [gap] above the
 * line it was given. So the row that follows it starts exactly where it would have with no panel
 * at all, and the panel reaches up over what came before. Nothing in the column clips, so the
 * overhang is drawn -- and, being later in the column, drawn on top -- and takes the touch there
 * ahead of what it covers.
 */
private fun Modifier.floatAbove(gap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    layout(placeable.width, 0) {
        placeable.place(0, -placeable.height - gap.roundToPx())
    }
}

/**
 * Lets something sit [overlap] higher than the column would put it, over the foot of whatever
 * came before. Measured in full, reported that much shorter, and placed that much higher: the
 * column closes up by [overlap], and the part that now overhangs is drawn and takes touches all
 * the same, since nothing in the column clips.
 */
private fun Modifier.tuckUnder(overlap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trimmed = overlap.roundToPx()
    layout(placeable.width, (placeable.height - trimmed).coerceAtLeast(0)) {
        placeable.place(0, -trimmed)
    }
}

@Composable
private fun Utilities(
    state: PlayerUiState,
    panel: Panel,
    onPanel: (Panel) -> Unit,
    onTag: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    // Laid out by its marks like the transport above it, against the same two ends: the heart
    // meets the seeker's left end, the clock its right, and the air between neighbours is equal.
    val size = 44.dp
    // The marks here fill most of their buttons, so the wash under a press reaches past the rim
    // to be seen around them.
    val reach = 1.3f
    MarkRow(
        markWidths = listOf(
            size * HEART_GLYPH * HEART_SPAN,
            size * TAG_GLYPH * TAG_SPAN,
            size * VOLUME_GLYPH * VOLUME_SPAN,
            size * TIMER_GLYPH * TIMER_SPAN,
        ),
        modifier = Modifier.fillMaxWidth().lineEdges().bleed(SEEKER_THICKNESS / 2),
    ) {
        // The Favorites magenta rather than the accent, so the heart matches the tile it puts the
        // track in.
        Box(
            modifier = Modifier
                .size(size)
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
                if (state.currentIsFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (state.currentIsFavorite) Color(Group.FAVORITES_COLOR) else TEXT,
                contentDescription = null,
                size = size * HEART_GLYPH,
            )
        }
        // A press here files the track. Filing is the one thing the dock could do to a track
        // that the player could not, and it is most wanted where the track is playing.
        GlyphButton(
            glyph = TagGlyph,
            contentDescription = "Choose groups for this track",
            onClick = onTag,
            size = size,
            glyphFraction = TAG_GLYPH,
            pressReach = reach,
        )
        GlyphButton(
            glyph = volumeGlyph(state.volumeFraction),
            contentDescription = "Volume",
            onClick = { onPanel(Panel.VOLUME) },
            active = panel == Panel.VOLUME,
            size = size,
            glyphFraction = VOLUME_GLYPH,
            pressReach = reach,
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
                size = size,
                glyphFraction = TIMER_GLYPH,
                pressReach = reach,
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
 * They open over the strip just above the button row rather than as a sheet: a sheet would cover
 * the transport controls, and these are things you reach for while the music is playing and you
 * still want to see what it is doing. Each opens directly over the button that summoned it, and
 * nothing else on the screen moves to make room.
 */
@Composable
private fun InlinePanelBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL)
            .border(1.dp, LINE, RoundedCornerShape(12.dp))
            // A panel that floats over the seeker must take the touches it covers, or a press on
            // its own blank surface would land on the bar underneath and seek. Nothing to do
            // with the tap beyond having been the thing that caught it.
            .pointerInput(Unit) { detectTapGestures { } }
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
            // A tight line box. The default leading makes a 12sp line about 25dp tall, which is
            // taller than the 22dp band it sits beside -- so the digits overflowed their own
            // bounds and the column's edge took the bottom off them.
            lineHeight = 14.sp,
            style = TABULAR,
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
private fun Caption(state: PlayerUiState, compact: Boolean = false, reserve: Boolean = !compact) {
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
    // turns from a jump into a slide. A portrait screen with a sleeve on it does the same, for
    // the same reason: there the height taken off the strip goes to the picture, and a notice
    // arriving shrinks the picture by a line rather than moving the buttons under the thumb.
    val height by animateDpAsState(
        targetValue = when {
            reserve -> 30.dp
            caption != null -> if (compact) 22.dp else 30.dp
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

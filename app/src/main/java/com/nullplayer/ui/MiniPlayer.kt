package com.nullplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplayer.playback.PlayerUiState

/** Long enough to press without aiming, short enough that four of them clear the pill. */
private val MINI_BUTTON = 40.dp

/**
 * The skip marks get less of their button than the other glyphs get of theirs.
 *
 * A skip runs the full width it is given, where the play triangle stops well short of it, so at a
 * matching fraction the two sit side by side with the skips looking the larger pair. This is the
 * strip's own value: on the player screen the buttons are big enough for the difference not to
 * show, and they keep theirs.
 */
private const val MINI_SKIP_GLYPH = 0.27f

/**
 * Air between the scrub bar and the row beneath it.
 *
 * The bar is drawn hard against the top edge of the strip because it doubles as the dividing line;
 * without a gap the pill and the buttons sit right up against it and read as hanging off it rather
 * than as a row of their own.
 */
private val SCRUB_GAP = 6.dp

/**
 * Type measured from the font's own ascent and descent, and centred on those.
 *
 * Android's legacy font padding is not symmetric — it leaves more room above the ascent than below
 * the descent — so a label centred by its box sits visibly low inside it. The error is a fraction
 * of the font size rather than a fixed number of pixels, so it is barely visible at the default
 * text size and obvious at the 1.8x scale someone who wants big text is running. That matters more
 * here than anywhere else in the app: this is the one label with a shape drawn tightly around it,
 * where being a little low is something to see rather than something to measure.
 *
 * Both labels take it, not just the one in the pill — trimming one and not the other would leave
 * the two sitting on different lines.
 */
private val TRIMMED = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

/**
 * The strip along the foot of every sub-screen: what is playing, how far into the queue, and
 * enough of the transport to act on it without going back.
 *
 * It says which *tile* is playing rather than which track, in that tile's own colour — the same
 * pill the ribbon draws, so the thing you swiped to on the player is the thing you recognise here.
 * A track name would say more, and is the one thing this app never puts on a screen.
 *
 * The buttons are the player's own, on the player's terms: the same wrap-around skip, the same
 * refusals. Where the player greys a refused button and still takes the press — it has the caption
 * underneath to print the reason in — these are dead instead, because a sub-screen has nowhere to
 * print it and a button that answers a press with nothing at all is worse than one that plainly
 * cannot be pressed.
 *
 * Everything that is not a button opens the player, which is the other half of what a strip like
 * this is for.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onScrub: (Long) -> Unit,
    onVoiceOver: () -> Unit,
    onVoiceOverLong: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refused = state.playRefusal != null

    // The fill arrives in [modifier]: this strip floats over the list rather than sitting below
    // it, so what paints its background is frosted glass rather than a flat colour.
    Column(modifier.fillMaxWidth()) {
        // Edge to edge and unpadded: it doubles as the line dividing the strip from the list
        // above it, which is a job a bar inset from both margins could not do. Square caps for
        // the same reason — a rounded end would lift off the screen edge.
        ProgressBar(
            fraction = state.progress,
            busy = rememberBusy(state),
            modifier = Modifier.fillMaxWidth().height(3.dp),
            thickness = 3.dp,
            cap = StrokeCap.Butt,
        )

        Spacer(Modifier.height(SCRUB_GAP))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The panel's colour runs on under the gesture bar; only its contents stop short.
                .windowInsetsPadding(WindowInsets.navigationBars)
                // A minimum rather than a height: at a large font scale the pill is taller than
                // this, and a strip that cannot grow would crop it.
                .heightIn(min = 58.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Open the player") { onOpenPlayer() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The pill is the half that gives way: a long group name ellipsizes rather than
                // pushing the position out of the strip, because the position is the shorter and
                // the more precise of the two.
                NowPlayingPill(
                    name = state.activeName,
                    colour = Color(state.activeColor),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = queuePosition(state, separator = "/"),
                    color = MUTED,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    style = TRIMMED,
                )
            }

            Spacer(Modifier.width(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton(
                    glyph = PreviousGlyph,
                    contentDescription = "Previous track. Hold to rewind.",
                    onClick = onPrevious,
                    onLongPress = { onScrub(-SCRUB_STEP_MS) },
                    enabled = state.hasTracks,
                    size = MINI_BUTTON,
                    glyphFraction = MINI_SKIP_GLYPH,
                )
                GlyphButton(
                    glyph = if (state.isPlaying) PauseGlyph else PlayGlyph,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    enabled = !refused,
                    size = MINI_BUTTON,
                    glyphFraction = 0.30f,
                    tint = if (refused) TEXT else BACKGROUND,
                    background = if (refused) Color.Transparent else ACCENT,
                )
                GlyphButton(
                    glyph = NextGlyph,
                    contentDescription = "Next track. Hold to fast-forward.",
                    onClick = onNext,
                    onLongPress = { onScrub(SCRUB_STEP_MS) },
                    enabled = state.hasTracks,
                    size = MINI_BUTTON,
                    glyphFraction = MINI_SKIP_GLYPH,
                )
                GlyphButton(
                    glyph = VoiceOverGlyph,
                    contentDescription = "Announce the current track",
                    onClick = onVoiceOver,
                    onLongPress = onVoiceOverLong,
                    active = state.isSpeaking,
                    enabled = state.voiceOverRefusal == null,
                    size = MINI_BUTTON,
                    glyphFraction = 0.42f,
                )
            }
        }
    }
}

/** The same fill and the same ink as the ribbon chip this stands for, at a quarter the size. */
@Composable
private fun NowPlayingPill(name: String, colour: Color, modifier: Modifier = Modifier) {
    Text(
        text = name,
        color = readableOn(colour),
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TRIMMED,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(colour)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

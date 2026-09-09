package com.nullplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource

/**
 * How far the glass carries what is behind it.
 *
 * Judged against this app's own palette rather than copied from anywhere. The page is very nearly
 * black and its text is dim grey, so there is little contrast for a blur to work with: too wide a
 * radius averages the lot to flat black and the strip stops looking like glass at all, and too
 * narrow leaves the words under it legible. This is the middle.
 */
private val GLASS_BLUR = 24.dp

/**
 * The panel colour, thinned, laid over the blur.
 *
 * Thin on purpose: every point of alpha spent here is a point of the blur behind it going unseen,
 * and on a near-black page there is little enough of it to show.
 */
private val GLASS_TINT = PANEL.copy(alpha = 0.45f)

/**
 * A page whose bar floats over its content rather than sitting below it.
 *
 * A bar has to overlap something for the glass to have anything to show, so the body is given the
 * whole height and told how much of its head and foot the two bars are covering. It passes those
 * on as padding at either end of its list, which is what lets the first and last rows scroll clear
 * of the bars instead of ending underneath them. Both heights are measured rather than declared,
 * because the bars grow with the font scale and a number written here would be wrong on any phone
 * whose text is not at 1x.
 *
 * The blur itself is Haze's. Doing it by hand is a trap: the capture has to be recorded once and
 * then sampled from a *second* place, and a `GraphicsLayer` belongs to one parent at a time, so
 * every arrangement of record-here-draw-there ends with the bar sampling an empty layer and coming
 * out merely translucent. Haze owns both ends of that and is the reason this is a dozen lines.
 *
 * The page colour is painted inside the capture rather than behind it — [hazeSource] records what
 * the node draws, and a capture without the background is transparent wherever the content does
 * not reach, which lets the sharp page read straight through the strip.
 */
@Composable
fun GlassScaffold(
    topBar: @Composable (Modifier) -> Unit,
    bottomBar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topInset: Dp, bottomInset: Dp) -> Unit,
) {
    val haze = remember { HazeState() }
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    var barHeight by remember { mutableStateOf(0.dp) }

    val style = HazeDefaults.style(
        backgroundColor = BACKGROUND,
        tint = HazeTint(GLASS_TINT),
        blurRadius = GLASS_BLUR,
        // Grain over a near-black page reads as noise on the screen rather than as texture in
        // the glass, so there is none.
        noiseFactor = 0f,
    )

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze).background(BACKGROUND)) {
            content(headerHeight, barHeight)
        }
        topBar(
            Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .hazeChild(state = haze, style = style),
        )
        bottomBar(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                .hazeChild(state = haze, style = style),
        )
    }
}

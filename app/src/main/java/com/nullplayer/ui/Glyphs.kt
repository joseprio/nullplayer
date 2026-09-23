package com.nullplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The icon set, drawn rather than imported.
 *
 * Every glyph is a function of a half-extent and a colour, so one button composable can host any
 * of them and they all scale off the same number. Hand-drawing them is cheaper than the extended
 * Material icon artifact, and it keeps the marks that have no Material equivalent from looking
 * like the odd ones out. The exception is the VoiceOver mark, which is traced artwork living in
 * [VoiceOverArt] — it reaches the same signature, so nothing else has to know.
 */
typealias Glyph = DrawScope.(extent: Float, color: Color) -> Unit

/** How far a button's glow reaches past its rim, as a share of the radius. */
private const val GLOW_REACH = 0.25f

/** How much a button shrinks under the finger. */
private const val PRESSED_SCALE = 0.86f

/** How long the play mark takes to become the pause mark, and back. */
private const val MORPH_MS = 240

/**
 * A round, tappable icon. [active] tints it with the accent, for the toggles that latch on.
 *
 * [enabled] false is a button that cannot be pressed at all. [unavailable] looks exactly the same
 * but still takes the press — for a control that is refused for a reason worth saying out loud,
 * where a dead button would leave the user guessing at what to fix.
 *
 * [glow] is a halo of that colour outside the rim, for the one button on a screen that ought to be
 * found without looking. It reaches [GLOW_REACH] of the radius past the edge and is drawn before
 * the clip, so it is the only part of a button that spills out of its circle.
 *
 * Under the finger the whole button shrinks to [PRESSED_SCALE] and a wash of its own colour comes
 * up behind the mark, then both let go with the press. These buttons take their taps through a
 * gesture detector rather than `clickable`, so nothing gives them a ripple for free. [pressReach]
 * is the wash's radius as a share of the button's: more than one for a button whose mark nearly
 * fills it, where a wash held inside the rim is lost behind the mark.
 *
 * [glowPulse] breathes the glow: read as it is drawn, so it can change every frame without the
 * button being composed again. Half is the glow as it is without it; nothing dims it to a faint
 * ring held close to the rim, and a full one sends it half as far again. It is the colour that
 * saturates first and the reach that carries the rest, so a hit reads as the glow throwing
 * itself outwards rather than as a lamp turned up.
 */
@Composable
fun GlyphButton(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    glyphFraction: Float = 0.42f,
    active: Boolean = false,
    enabled: Boolean = true,
    unavailable: Boolean = false,
    tint: Color = TEXT,
    background: Color = Color.Transparent,
    glow: Color = Color.Transparent,
    pressReach: Float = 1f,
    onLongPress: (() -> Unit)? = null,
    glowPulse: (() -> Float)? = null,
) {
    val colour by animateColorAsState(
        targetValue = when {
            !enabled || unavailable -> MUTED.copy(alpha = 0.4f)
            active -> ACCENT
            else -> tint
        },
        label = "glyph",
    )

    val halo by animateColorAsState(targetValue = glow, label = "glow")

    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = tween(if (pressed) 80 else 160, easing = FastOutSlowInEasing),
        label = "press",
    )
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.18f else 0f,
        animationSpec = tween(if (pressed) 80 else 220),
        label = "wash",
    )

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                val rim = this.size.minDimension / 2f
                val breath = glowPulse?.invoke() ?: 0.5f
                val lit = halo.copy(alpha = halo.alpha * (0.4f + 1.2f * breath).coerceAtMost(1f))
                if (lit.alpha > 0f) {
                    val out = GLOW_REACH * (0.5f + breath)
                    val reach = rim * (1f + out)
                    val edge = 1f / (1f + out)
                    // Solid out to the rim, where the disc covers it anyway, and for a band
                    // beyond it, then falling away over what is left: the stop at the rim is
                    // what keeps the fade from starting somewhere inside the button and reaching
                    // the edge already half gone, and the band is what makes the glow read as a
                    // ring with a short soft edge rather than a haze the button sits in.
                    fun past(share: Float) = edge + (1f - edge) * share
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to lit,
                                past(0.2f) to lit,
                                past(0.5f) to lit.copy(alpha = lit.alpha * 0.3f),
                                1f to lit.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = reach,
                        ),
                        radius = reach,
                        center = center,
                    )
                }
                if (wash > 0f) {
                    drawCircle(colour.copy(alpha = wash), radius = rim * pressReach, center = center)
                }
            }
            .clip(CircleShape)
            .background(background)
            .pointerInput(enabled, onClick, onLongPress) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = onLongPress?.let { press -> { press() } },
                )
            }
            .semantics {
                this.contentDescription = contentDescription
                onClick(label = contentDescription) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val extent = min(this.size.width, this.size.height) * glyphFraction
            translate(this.size.width / 2f, this.size.height / 2f) {
                glyph(extent, colour)
            }
        }
    }
}

// -- Transport ----------------------------------------------------------------------------------

val PlayGlyph: Glyph = { extent, color ->
    drawPath(
        path = Path().apply {
            moveTo(extent * 0.95f, 0f)
            lineTo(-extent * 0.7f, -extent)
            lineTo(-extent * 0.7f, extent)
            close()
        },
        color = color,
    )
}

/**
 * The play mark on its way to the pause mark: 0 is [PlayGlyph], 1 is the two bars.
 *
 * The triangle is cut along its middle into two halves, and each half is a bar in waiting: as
 * [progress] runs, the apex splits and the halves straighten into a pair of stripes while the
 * whole mark turns a quarter turn, so the stripes that were lying across the triangle end up
 * standing. Each half is a four-cornered shape throughout — the apex simply counts twice at the
 * start — which is what lets the corners slide rather than jump.
 *
 * The halves overlap by a hair at the start and are drawn as one path, so the cut never shows
 * as a seam through the triangle.
 */
fun playPauseGlyph(progress: Float): Glyph = { extent, color ->
    val p = progress.coerceIn(0f, 1f)
    val bar = extent * 0.3f
    val gap = extent * 0.26f
    val apex = extent * 0.95f
    val base = -extent * 0.7f
    val overlap = extent * 0.02f * (1f - p)

    // Each corner given as (start, end); the bars here lie flat, the turn below stands them up.
    fun corner(sx: Float, sy: Float, ex: Float, ey: Float) =
        Offset(lerp(sx, ex, p), lerp(sy, ey, p))

    val path = Path().apply {
        // Top half: base corner, apex, apex, cut edge -> the bar that ends on the right.
        moveTo(corner(base, -extent, -extent, -gap - bar))
        lineTo(corner(apex, 0f, extent, -gap - bar))
        lineTo(corner(apex, 0f, extent, -gap))
        lineTo(corner(base, overlap, -extent, -gap))
        close()
        // Bottom half: cut edge, apex, apex, base corner -> the bar that ends on the left.
        moveTo(corner(base, -overlap, -extent, gap))
        lineTo(corner(apex, 0f, extent, gap))
        lineTo(corner(apex, 0f, extent, gap + bar))
        lineTo(corner(base, extent, -extent, gap + bar))
        close()
    }
    rotate(degrees = 90f * p, pivot = Offset.Zero) {
        drawPath(path, color)
    }
}

private fun Path.moveTo(point: Offset) = moveTo(point.x, point.y)
private fun Path.lineTo(point: Offset) = lineTo(point.x, point.y)

/**
 * The play/pause mark for a button, animating between the two whenever [playing] changes.
 *
 * The state is read in the draw pass rather than here, so a frame of the morph costs a redraw of
 * the button and not a recomposition of whatever is holding it.
 */
@Composable
fun rememberPlayPauseGlyph(playing: Boolean): Glyph {
    val progress = animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(MORPH_MS, easing = FastOutSlowInEasing),
        label = "playPause",
    )
    return { extent, color -> playPauseGlyph(progress.value)(extent, color) }
}

val NextGlyph: Glyph = { extent, color -> drawSkip(extent, color, forward = true) }

val PreviousGlyph: Glyph = { extent, color -> drawSkip(extent, color, forward = false) }

/**
 * A bar plus one triangle, mirrored for the backward case.
 *
 * One triangle rather than two. Two of them stacked point-to-base is the mark for a *speed* —
 * fast-forward and rewind — and these buttons do not wind through a track, they step to the next
 * one. A single triangle run up against a bar is the mark for that step, and it is what a headset,
 * a lock screen and every other player put on the same control.
 *
 * The point lands exactly on the bar's inner edge rather than stopping short of it, so the whole
 * mark is one connected run from `-extent` to `+extent` with no floating parts. Taller than the
 * pair it replaces, because a lone triangle has the height to spare and reads better filling it.
 */
private fun DrawScope.drawSkip(extent: Float, color: Color, forward: Boolean) {
    val direction = if (forward) 1f else -1f
    val height = extent * 1.6f
    val barThickness = extent * 0.22f

    // The face of the bar the triangle points at.
    val barFace = direction * (extent - barThickness)

    drawRect(
        color = color,
        topLeft = Offset(if (forward) barFace else -extent, -height / 2),
        size = Size(barThickness, height),
    )

    // The base sits on the far edge, so the triangle spans everything the bar does not.
    val back = -direction * extent
    drawPath(
        path = Path().apply {
            moveTo(barFace, 0f)
            lineTo(back, -height / 2)
            lineTo(back, height / 2)
            close()
        },
        color = color,
    )
}

// -- Modes --------------------------------------------------------------------------------------

/**
 * Two arrows crossing: the queue is not in the order it was written down.
 *
 * Each arrow runs in from the left edge, turns diagonally through the middle, and levels out
 * again before its head, so the two cross once in the centre instead of forming a bare X.
 */
val ShuffleGlyph: Glyph = { extent, color ->
    val stroke = Stroke(width = extent * 0.18f, cap = StrokeCap.Round)
    val rise = extent * 0.58f
    val headBase = extent * 0.44f

    listOf(1f, -1f).forEach { direction ->
        drawPath(
            path = Path().apply {
                moveTo(-extent, -rise * direction)
                lineTo(-extent * 0.52f, -rise * direction)
                lineTo(extent * 0.30f, rise * direction)
                lineTo(headBase, rise * direction)
            },
            color = color,
            style = stroke,
        )
        drawRightArrowHead(
            tipX = extent,
            baseX = headBase,
            y = rise * direction,
            halfHeight = extent * 0.32f,
            color = color,
        )
    }
}

/**
 * A closed loop with an arrow riding the top edge; [one] adds the bar that means "this track,
 * forever".
 *
 * The arrow sits on the edge rather than in a gap cut out of it: a stroked corner and a filled
 * triangle never quite meet cleanly at icon sizes, and the overlap reads better than the seam.
 */
fun repeatGlyph(one: Boolean): Glyph = { extent, color ->
    val stroke = Stroke(width = extent * 0.17f, cap = StrokeCap.Round)
    val w = extent * 0.88f
    val h = extent * 0.64f
    val r = extent * 0.3f

    drawPath(
        path = Path().apply {
            moveTo(-w + r, -h)
            lineTo(w - r, -h)
            quadraticTo(w, -h, w, -h + r)
            lineTo(w, h - r)
            quadraticTo(w, h, w - r, h)
            lineTo(-w + r, h)
            quadraticTo(-w, h, -w, h - r)
            lineTo(-w, -h + r)
            quadraticTo(-w, -h, -w + r, -h)
            close()
        },
        color = color,
        style = stroke,
    )

    // Travelling clockwise, so the arrow on the top edge points right.
    drawRightArrowHead(
        tipX = w * 0.62f,
        baseX = w * 0.06f,
        y = -h,
        halfHeight = extent * 0.3f,
        color = color,
    )

    if (one) {
        drawLine(
            color = color,
            start = Offset(0f, -extent * 0.24f),
            end = Offset(0f, extent * 0.24f),
            strokeWidth = extent * 0.17f,
            cap = StrokeCap.Round,
        )
    }
}

/** A filled triangle pointing right, from [baseX] to [tipX] at height [y]. */
private fun DrawScope.drawRightArrowHead(
    tipX: Float,
    baseX: Float,
    y: Float,
    halfHeight: Float,
    color: Color,
) {
    drawPath(
        path = Path().apply {
            moveTo(tipX, y)
            lineTo(baseX, y - halfHeight)
            lineTo(baseX, y + halfHeight)
            close()
        },
        color = color,
    )
}

// -- Utilities ----------------------------------------------------------------------------------

/** A clock face. Used for the sleep timer. */
val TimerGlyph: Glyph = { extent, color ->
    val radius = extent * 0.82f
    drawCircle(color, radius, Offset.Zero, style = Stroke(width = extent * 0.17f))
    // The winder on top, so it reads as a timer rather than a plain circle.
    drawLine(
        color = color,
        start = Offset(-extent * 0.24f, -radius - extent * 0.26f),
        end = Offset(extent * 0.24f, -radius - extent * 0.26f),
        strokeWidth = extent * 0.17f,
        cap = StrokeCap.Round,
    )
    drawLine(color, Offset.Zero, Offset(0f, -radius * 0.55f), extent * 0.16f, StrokeCap.Round)
    drawLine(color, Offset.Zero, Offset(radius * 0.45f, 0f), extent * 0.16f, StrokeCap.Round)
}

/** A speaker cone, with as many waves as there is volume. */
fun volumeGlyph(level: Float): Glyph = { extent, color ->
    val bodyHeight = extent * 0.46f
    val neckWidth = extent * 0.36f
    drawRect(
        color = color,
        topLeft = Offset(-extent * 0.92f, -bodyHeight),
        size = Size(neckWidth, bodyHeight * 2),
    )
    drawPath(
        path = Path().apply {
            moveTo(-extent * 0.92f + neckWidth, -bodyHeight)
            lineTo(-extent * 0.06f, -extent)
            lineTo(-extent * 0.06f, extent)
            lineTo(-extent * 0.92f + neckWidth, bodyHeight)
            close()
        },
        color = color,
    )

    if (level <= 0f) {
        val cross = extent * 0.42f
        val centre = Offset(extent * 0.52f, 0f)
        drawLine(
            color, Offset(centre.x - cross, -cross), Offset(centre.x + cross, cross),
            extent * 0.16f, StrokeCap.Round,
        )
        drawLine(
            color, Offset(centre.x - cross, cross), Offset(centre.x + cross, -cross),
            extent * 0.16f, StrokeCap.Round,
        )
    } else {
        drawSoundWaves(
            extent = extent,
            color = color,
            centre = Offset(extent * 0.12f, 0f),
            count = if (level > 0.55f) FULL_WAVES else 1,
        )
    }
}

/** As many arcs as the speaker shows when nothing is being held back. */
const val FULL_WAVES = 2

/**
 * Sound leaving something: concentric arcs opening to the right.
 *
 * Shared by the volume mark and the VoiceOver mark rather than drawn twice. They say the same
 * thing about the same audio, and two different sets of waves in one button row would read as two
 * different ideas — keeping one function means they cannot drift apart later either.
 */
private fun DrawScope.drawSoundWaves(
    extent: Float,
    color: Color,
    centre: Offset,
    count: Int,
) {
    repeat(count) { index ->
        val radius = extent * (0.42f + index * 0.36f)
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = extent * 0.15f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Three faders at three different settings, each knob a ring threaded onto its shaft.
 *
 * The shaft is drawn as two lines with the knob between them rather than one line with the knob
 * on top, so the hole in the ring is a real hole — the page shows through it, over glass or over
 * the flat background alike, where a disc of the page colour would show its seam on one of the
 * two. The two halves stop at the ring's outer edge and their round caps reach exactly its inner
 * one, which is what makes the three marks read as one fader rather than as a bar and a circle.
 */
val EqualizerGlyph: Glyph = { extent, color ->
    val stroke = extent * 0.15f
    val radius = extent * 0.24f
    val top = -extent * 0.92f
    val bottom = extent * 0.92f
    // Outer pair low, middle one high: the same asymmetry the old three-bar mark had, which is
    // what says "set by hand" rather than "reset".
    val faders = listOf(
        -extent * 0.7f to extent * 0.26f,
        0f to -extent * 0.26f,
        extent * 0.7f to extent * 0.26f,
    )

    faders.forEach { (x, knob) ->
        drawLine(
            color = color,
            start = Offset(x, top),
            end = Offset(x, knob - radius),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(x, knob + radius),
            end = Offset(x, bottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color, radius, Offset(x, knob), style = Stroke(width = stroke))
    }
}

/**
 * A cogwheel: eight teeth on a solid body, with the hub punched out.
 *
 * One path rather than a ring with eight bars laid on top of it. The bars version had to leave a
 * gap between each tooth and the ring or the joins showed as seams, which is what made it read as
 * a spoked wheel rather than a cog — and it could not have a hub at all, because a hole in the
 * middle of a stroked ring is just the ring.
 *
 * Every number below is lifted from the drawing this is a copy of, rather than chosen: a cog is a
 * shape everyone has seen ten thousand times, and proportions that are merely close read as wrong
 * without the reader being able to say why. Corners are left sharp for the same reason.
 *
 * The hub is a second subpath under an even-odd fill, so it is a real hole: the page shows through
 * it over the glass and over the flat background alike, where a disc in the background colour
 * would show its seam on one of the two.
 */
val SettingsGlyph: Glyph = { extent, color ->
    val root = extent * 0.806f
    val hub = extent * 0.383f
    // Each tooth covers close to sixteen degrees of the root circle and a little over ten of the
    // rim, so its flanks lean in as they climb. That taper is what makes the mark a cog: teeth
    // with parallel sides read as an asterisk with the corners filled in.
    val rootHalf = 15.9f
    val valley = 45f - 2f * rootHalf
    val tipHalf = extent * 0.183f

    fun turned(x: Float, y: Float, degrees: Float): Offset {
        val a = degrees * (PI.toFloat() / 180f)
        return Offset(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
    }

    val rootCircle = Rect(Offset.Zero, root)
    val cog = Path().apply {
        fillType = PathFillType.EvenOdd
        val start = turned(root, 0f, -rootHalf)
        moveTo(start.x, start.y)
        repeat(8) { index ->
            val centre = index * 45f
            // The tip is a flat face standing off the middle at the full extent — so it is the
            // faces, not the corners, that set how big the mark is, and the corners sit a shade
            // further out than the face they belong to.
            val lead = turned(extent, -tipHalf, centre)
            val trail = turned(extent, tipHalf, centre)
            lineTo(lead.x, lead.y)
            lineTo(trail.x, trail.y)
            // Round the valley to the foot of the next tooth. The arc starts exactly where the
            // flank above ended, so it joins on without a seam and leaves the pen in the right
            // place for the next one.
            arcTo(rootCircle, centre + rootHalf, valley, false)
        }
        close()
        addOval(Rect(Offset.Zero, hub))
    }
    drawPath(cog, color)
}

/** Books on a shelf: the library, which is the only place a list of anything exists. */
val LibraryGlyph: Glyph = { extent, color -> drawShelf(extent, color) }

/**
 * Three spines, the last one leaning on the others.
 *
 * Drawn as solids rather than outlines because a spine at this size has no room for a stroke and
 * a gap both — outlined, the three of them read as a fence. The leaning one is what makes the
 * mark a shelf instead of three bars: upright it would be indistinguishable from a level meter,
 * and the app already has one of those in [PlayingGlyph].
 */
private fun DrawScope.drawShelf(extent: Float, color: Color) {
    val width = extent * 0.39f
    val top = -extent * 0.88f
    val height = extent * 1.77f
    val radius = CornerRadius(width * 0.44f)

    listOf(-extent * 0.88f, -extent * 0.4f).forEach { left ->
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = radius,
        )
    }
    // Tipped about its own middle, so it keeps the height of the two it is resting against
    // instead of sliding out of the box at the foot.
    val leaning = extent * 0.3f
    rotate(degrees = -13.5f, pivot = Offset(leaning + width / 2f, top + height / 2f)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(leaning, top),
            size = Size(width, height),
            cornerRadius = radius,
        )
    }
}

/**
 * How far [drawListLines] reaches above and below its middle, per unit of the extent it is given.
 *
 * 0.62 out to the outer bar, plus the 0.11 its round cap adds on top of that. Stated rather than
 * measured by eye, because the order mark's arrow is measured against it.
 */
private const val LIST_HALF_HEIGHT = 0.73f

/** How big the list is drawn inside the mark that badges it, which is exactly as tall as its list. */
private const val LIST_SCALE = 0.7f

/**
 * Three stacked bars, each shorter than the last.
 *
 * This was the library mark before the shelf took that button, and it is kept because it is the
 * better host for a badge: the shelf is a solid block of ink with no corner to spare, where a
 * list of lines is mostly air and shortens towards the bottom right — which is exactly where
 * something has to go. The order mark below is the only caller.
 */
private fun DrawScope.drawListLines(extent: Float, color: Color) {
    val stroke = extent * 0.22f
    listOf(-extent * 0.62f, 0f, extent * 0.62f).forEachIndexed { index, y ->
        drawLine(
            color = color,
            start = Offset(-extent, y),
            end = Offset(extent - index * extent * 0.35f, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * A dial pad: go to a track by its number.
 *
 * The ten digits as a phone lays them out — three rows of three and the zero alone beneath — with
 * the star and hash left off. Ten dots in that shape read as a keypad where a full grid of twelve
 * read as a pattern, and it is the digits the button is for. Drawn taller than it is wide; the
 * button is round and has the height to spare.
 */
val DialPadGlyph: Glyph = { extent, color ->
    val dot = extent * 0.2f
    val across = extent * 0.72f
    val down = extent * 0.62f
    for (row in 0..3) {
        val y = (row - 1.5f) * down
        val columns = if (row == 3) 0..0 else -1..1
        for (column in columns) drawCircle(color, dot, Offset(column * across, y))
    }
}

/**
 * A list with a two-headed arrow standing beside it: lay the queue out in some other order.
 *
 * The arrow is drawn a little taller than the list, top and bottom, and struck at the bars' own
 * weight: the two are a pair being read together, and an arrow that overshot the thing it is
 * sorting by much stopped looking like a mark *about* the list and started looking like a second
 * mark that happened to be standing next to one. Its heads are only wide down at the ends, where
 * the bars have already run out, so the list gives up less width to it than the drawing suggests.
 *
 * A head at each end rather than one: the button opens a dialog where the order is chosen, and
 * the mark says "this can be sorted either way" rather than reporting which way it currently is.
 * *Which* order was chosen takes four words to say and belongs in the dialog.
 */
val OrderByGlyph: Glyph = { extent, color ->
    // The list is pushed left by as much as the arrow is pushed right, so the air between the
    // two opens up without the whole mark sliding off-centre in its button.
    translate(-extent * 0.38f, 0f) {
        drawListLines(extent * LIST_SCALE, color)
    }
    val stroke = extent * 0.22f * LIST_SCALE
    // The shaft stops half a stroke short at each end, because its round caps spend that half
    // getting to the arrow's own edge.
    val reach = extent * ORDER_ARROW_HALF_HEIGHT - stroke / 2f
    val x = extent * 0.86f
    // The head is set off the shaft rather than off the arrow's length, so it stays a head on a
    // line instead of growing back into a triangle with a tail when the list is drawn larger.
    val wing = extent * 0.32f * LIST_SCALE
    drawLine(
        color = color,
        start = Offset(x, -reach),
        end = Offset(x, reach),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    listOf(-1f, 1f).forEach { direction ->
        val tip = reach * direction
        val back = wing * direction
        drawPath(
            path = Path().apply {
                moveTo(x - wing, tip - back)
                lineTo(x, tip)
                lineTo(x + wing, tip - back)
            },
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * How far the order mark's arrow reaches above and below its middle, per unit of extent: past
 * the list's own [LIST_HALF_HEIGHT] (scaled by [LIST_SCALE]) by enough to be seen standing
 * taller, and not by so much that it stops belonging to the list.
 */
private const val ORDER_ARROW_HALF_HEIGHT = 0.68f

/**
 * Level meter bars: the tile the queue is being drawn from.
 *
 * A tick was the obvious mark and the wrong one — it reads as "selected" or "done", which is what
 * the row's own checkbox-shaped affordances mean everywhere else in the app. Bars at different
 * heights say "sound is coming out of this one" without claiming it is ticked.
 *
 * Heights are fixed rather than animated: this sits in a list that scrolls, and a row that never
 * settles is harder to read than one that does.
 */
val PlayingGlyph: Glyph = { extent, color ->
    val bar = extent * 0.3f
    val gap = extent * 0.14f
    // Tallest in the middle, so the mark is symmetrical whichever way it is read.
    val heights = listOf(0.52f, 1f, 0.72f)
    heights.forEachIndexed { index, fraction ->
        val height = extent * fraction
        val x = (index - 1) * (bar + gap) - bar / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(x, extent - height * 2),
            size = Size(bar, height * 2),
            cornerRadius = CornerRadius(bar / 2f),
        )
    }
}

/** [PlayingGlyph] on its own, for a list row that has no button to hang it on. */
@Composable
fun PlayingMark(tint: Color, size: Dp = 18.dp) = GlyphMark(PlayingGlyph, tint, size)

/**
 * Any glyph drawn on its own, for the places that want the mark without a button around it.
 *
 * [GlyphButton] is the same drawing with a touch target and a tint animation wrapped round it; a
 * label that merely points at something needs neither.
 */
@Composable
fun GlyphMark(
    glyph: Glyph,
    tint: Color,
    size: Dp = 18.dp,
    glyphFraction: Float = 0.42f,
) {
    Canvas(Modifier.size(size)) {
        val extent = min(this.size.width, this.size.height) * glyphFraction
        translate(this.size.width / 2f, this.size.height / 2f) {
            glyph(extent, tint)
        }
    }
}

/**
 * An arrow dropping into an open tray: bring a curve in from outside.
 *
 * The tray is drawn as one open stroke rather than a closed rectangle with something painted over
 * it — the gap in the top edge is where the arrow passes through, so it has to be a real gap. At
 * this size a "cut out" faked with a background-coloured rectangle would show its seam against the
 * panel it sits on.
 */
val ImportGlyph: Glyph = { extent, color ->
    val stroke = Stroke(width = extent * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val left = -extent
    val right = extent
    val top = -extent * 0.43f
    val bottom = extent * 0.67f
    val radius = extent * 0.30f
    val gap = extent * 0.33f

    drawPath(
        path = Path().apply {
            // Anticlockwise from the left lip, all the way round to the right one.
            moveTo(-gap, top)
            lineTo(left + radius, top)
            quadraticTo(left, top, left, top + radius)
            lineTo(left, bottom - radius)
            quadraticTo(left, bottom, left + radius, bottom)
            lineTo(right - radius, bottom)
            quadraticTo(right, bottom, right, bottom - radius)
            lineTo(right, top + radius)
            quadraticTo(right, top, right - radius, top)
            lineTo(gap, top)
        },
        color = color,
        style = stroke,
    )

    val tip = extent * 0.31f
    val wing = extent * 0.40f
    drawLine(
        color = color,
        start = Offset(0f, -extent * 0.67f),
        end = Offset(0f, tip),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )
    drawPath(
        path = Path().apply {
            moveTo(-wing, -extent * 0.09f)
            lineTo(0f, tip)
            lineTo(wing, -extent * 0.09f)
        },
        color = color,
        style = stroke,
    )
}

/** Two crossed bars: the "add one" action next to a section heading. */
/**
 * A luggage tag: a five-sided label with a punched hole, pointing left.
 *
 * Drawn rather than taken from the icon set, because the set shipped with the app has no tag in
 * it, and this sits in the same bar as [ImportGlyph] where a heavier icon from elsewhere would
 * read as borrowed. The hole is a second subpath under an even-odd fill rather than a dot in the
 * page colour, so it stays a hole over the glass rather than a disc of the wrong background.
 */
val TagGlyph: Glyph = { extent, color ->
    drawPath(
        path = Path().apply {
            fillType = PathFillType.EvenOdd
            moveTo(-extent, 0f)
            lineTo(-extent * 0.34f, -extent * 0.76f)
            lineTo(extent, -extent * 0.76f)
            lineTo(extent, extent * 0.76f)
            lineTo(-extent * 0.34f, extent * 0.76f)
            close()
            addOval(Rect(Offset(-extent * 0.5f, 0f), extent * 0.17f))
        },
        color = color,
    )
}

val PlusGlyph: Glyph = { extent, color ->
    val stroke = extent * 0.22f
    // Short of the full extent, so the arms clear the round button edge at every size.
    val arm = extent * 0.78f
    drawLine(
        color = color,
        start = Offset(-arm, 0f),
        end = Offset(arm, 0f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(0f, -arm),
        end = Offset(0f, arm),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/**
 * A speaking profile with the sound leaving it: the face from [VoiceOverArt], the waves from the
 * volume mark.
 *
 * Traced artwork rather than drawn here like the rest of the set — a face in profile is the one
 * mark in this app that a few circles and arcs cannot carry, and the VoiceOver button is the
 * feature the whole design hangs off.
 *
 * The traced line is drawn filled *and* stroked. Its outline is a fill rather than a stroke, so
 * there is no width to turn up; laying a stroke over the same path widens it from both sides, and
 * without that it sits noticeably lighter than every hand-drawn mark beside it.
 */
val VoiceOverGlyph: Glyph = { extent, color ->
    withTransform({ scale(extent, extent, pivot = Offset.Zero) }) {
        drawPath(VoiceOverArt.path, color)
        drawPath(
            path = VoiceOverArt.path,
            color = color,
            // Normalised units: the transform above puts it back in step with `extent`.
            style = Stroke(width = 0.05f, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
    drawSoundWaves(
        extent = extent,
        color = color,
        centre = VoiceOverArt.MOUTH * extent,
        count = FULL_WAVES,
    )
}

/** A lower-case i in a ring: there is something to read behind this. */
val InfoGlyph: Glyph = { extent, color ->
    drawCircle(color, radius = extent * 0.92f, center = Offset.Zero, style = Stroke(extent * 0.14f))

    // The tittle sits right of the stem's waist, where an italic hand would have left it.
    drawCircle(color, radius = extent * 0.13f, center = Offset(extent * 0.09f, -extent * 0.44f))

    // A serif italic 'i' drawn as one filled outline rather than a stroked bar: the letterform is
    // the whole point of the reference, and it is carried by three things a stroke cannot do —
    // the flat entry serif at the top left, the stem's lean, and the foot hooking up to the right.
    drawPath(
        path = Path().apply {
            // Top edge, sloping up to the right with the italic.
            moveTo(-extent * 0.34f, -extent * 0.12f)
            lineTo(extent * 0.16f, -extent * 0.20f)
            // Right flank of the stem, leaning left as it falls.
            quadraticTo(extent * 0.04f, extent * 0.06f, -extent * 0.02f, extent * 0.24f)
            // The foot turns out of the stem and hooks up to the right.
            quadraticTo(-extent * 0.06f, extent * 0.40f, extent * 0.10f, extent * 0.34f)
            quadraticTo(extent * 0.20f, extent * 0.28f, extent * 0.27f, extent * 0.13f)
            lineTo(extent * 0.38f, extent * 0.20f)
            // Underside of the hook, coming back left.
            quadraticTo(extent * 0.22f, extent * 0.52f, -extent * 0.02f, extent * 0.52f)
            quadraticTo(-extent * 0.26f, extent * 0.52f, -extent * 0.18f, extent * 0.24f)
            // Left flank of the stem, rising back to the serif.
            quadraticTo(-extent * 0.12f, extent * 0.04f, -extent * 0.08f, -extent * 0.08f)
            close()
        },
        color = color,
    )
}

/**
 * A globe with meridians: the upload server.
 *
 * A Wi-Fi fan would say "wireless", which is not the point — what the button turns on is a web
 * server other machines can open in a browser.
 */
val GlobeGlyph: Glyph = { extent, color ->
    val stroke = Stroke(width = extent * 0.14f, cap = StrokeCap.Round)
    val radius = extent * 0.92f

    drawCircle(color, radius = radius, center = Offset.Zero, style = stroke)

    // The equator, plus a parallel either side of it, clipped to the sphere.
    listOf(0f, -0.48f, 0.48f).forEach { fraction ->
        val y = radius * fraction
        val halfWidth = radius * kotlin.math.sqrt(1f - fraction * fraction)
        drawLine(
            color = color,
            start = Offset(-halfWidth, y),
            end = Offset(halfWidth, y),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }

    // One meridian, drawn as the ellipse the others would project to.
    val meridianHalfWidth = radius * 0.5f
    drawOval(
        color = color,
        topLeft = Offset(-meridianHalfWidth, -radius),
        size = Size(meridianHalfWidth * 2, radius * 2),
        style = stroke,
    )
}

package com.nullplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

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

/**
 * A round, tappable icon. [active] tints it with the accent, for the toggles that latch on.
 *
 * [enabled] false is a button that cannot be pressed at all. [unavailable] looks exactly the same
 * but still takes the press — for a control that is refused for a reason worth saying out loud,
 * where a dead button would leave the user guessing at what to fix.
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
    onLongPress: (() -> Unit)? = null,
) {
    val colour by animateColorAsState(
        targetValue = when {
            !enabled || unavailable -> MUTED.copy(alpha = 0.4f)
            active -> ACCENT
            else -> tint
        },
        label = "glyph",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pointerInput(enabled, onClick, onLongPress) {
                if (!enabled) return@pointerInput
                detectTapGestures(
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

val PauseGlyph: Glyph = { extent, color ->
    val barWidth = extent * 0.3f
    val gap = extent * 0.26f
    drawRect(color, Offset(-gap - barWidth, -extent), Size(barWidth, extent * 2))
    drawRect(color, Offset(gap, -extent), Size(barWidth, extent * 2))
}

val NextGlyph: Glyph = { extent, color -> drawSkip(extent, color, forward = true) }

val PreviousGlyph: Glyph = { extent, color -> drawSkip(extent, color, forward = false) }

/**
 * A bar plus two triangles, mirrored for the backward case.
 *
 * The leading triangle's point lands exactly on the bar's inner edge rather than stopping short
 * of it, and the two triangles meet point-to-base, so the whole mark is one connected run from
 * `-extent` to `+extent` with no floating parts.
 */
private fun DrawScope.drawSkip(extent: Float, color: Color, forward: Boolean) {
    val direction = if (forward) 1f else -1f
    val height = extent * 1.1f
    val barThickness = extent * 0.22f
    // Bar plus two triangles span the full width, which is what puts the tip on the bar.
    val width = extent - barThickness / 2f

    // The face of the bar the triangles point at.
    val barFace = direction * (extent - barThickness)

    drawRect(
        color = color,
        topLeft = Offset(if (forward) barFace else -extent, -height / 2),
        size = Size(barThickness, height),
    )

    repeat(2) { index ->
        val tip = barFace - direction * width * index
        val back = tip - direction * width
        drawPath(
            path = Path().apply {
                moveTo(tip, 0f)
                lineTo(back, -height / 2)
                lineTo(back, height / 2)
                close()
            },
            color = color,
        )
    }
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

/** Three faders at three different settings. */
val EqualizerGlyph: Glyph = { extent, color ->
    val stroke = extent * 0.15f
    val positions = listOf(-extent * 0.66f, 0f, extent * 0.66f)
    val knobs = listOf(-extent * 0.32f, extent * 0.36f, -extent * 0.04f)

    positions.forEachIndexed { index, x ->
        drawLine(
            color = color.copy(alpha = 0.55f),
            start = Offset(x, -extent),
            end = Offset(x, extent),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // Kept narrower than the gap between faders so three knobs never merge into a bar.
        drawLine(
            color = color,
            start = Offset(x - extent * 0.26f, knobs[index]),
            end = Offset(x + extent * 0.26f, knobs[index]),
            strokeWidth = stroke * 1.6f,
            cap = StrokeCap.Round,
        )
    }
}

/** A cogwheel: a thick ring with eight teeth around it. */
val SettingsGlyph: Glyph = { extent, color ->
    val ring = extent * 0.5f
    drawCircle(color, ring, Offset.Zero, style = Stroke(width = extent * 0.34f))
    repeat(8) { index ->
        rotate(degrees = index * 45f, pivot = Offset.Zero) {
            drawRoundRect(
                color = color,
                topLeft = Offset(-extent * 0.13f, -extent * 1.0f),
                size = Size(extent * 0.26f, extent * 0.44f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(extent * 0.08f),
            )
        }
    }
}

/** Stacked bars: the library, which is the only place a list of anything exists. */
val LibraryGlyph: Glyph = { extent, color ->
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

package com.nullplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nullplayer.playback.MusicPulse
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10

/** How far below the ceiling the meter reaches: a peak this far down leaves the glow at rest. */
private const val RANGE_DB = 12f

/**
 * How fast the ceiling -- the loudest peak lately -- comes down to meet quieter music, so a quiet
 * track or a breakdown moves the glow as much as a loud chorus does.
 */
private const val CEILING_FALL_DB_PER_SECOND = 3f

/** The lowest the ceiling goes: below it, the music is too quiet to light the glow at all. */
private const val CEILING_FLOOR_DB = -40f

/**
 * How fast the meter falls once the peaks drop: steady in decibels, which is an exponential
 * decay in amplitude. At this rate a full meter empties in about half a second, slow enough that
 * the glow breathes out between hits rather than flickering.
 */
private const val FALL_DB_PER_SECOND = 25f

/**
 * How quickly the meter climbs to a higher peak: a time constant short enough that a kick still
 * lands on time, long enough to take the frame-to-frame jitter off the top.
 */
private const val RISE_SECONDS = 0.03f

/**
 * The glow's breath, frame by frame, while [enabled]; null when the pulse is switched off. It is
 * a peak meter, from 0 at [RANGE_DB] below the ceiling to 1 at it.
 *
 * Each display frame takes the highest sub-bass peak of the audio it covers, as it is heard, and
 * puts it on a decibel scale, `20 * log10(peak)`, [RANGE_DB] deep under a ceiling that follows
 * the loudest recent peaks up at once and back down at [CEILING_FALL_DB_PER_SECOND]. The meter
 * climbs to a higher peak over [RISE_SECONDS] and otherwise falls at [FALL_DB_PER_SECOND], so it
 * drops smoothly instead of flickering between frames.
 *
 * Paused, or with nothing to go on, it settles back to one half, which is the glow as it always
 * was, so stopping the music leaves the button looking as it did before the pulse existed. The
 * value only ever reaches the draw phase, so the loop repaints the glow without composing
 * anything.
 *
 * It runs only while its button is on screen, which is also the only time the service is asked to
 * measure anything. The player and the mini player each hold one.
 */
@Composable
internal fun rememberMusicPulse(enabled: Boolean, playing: Boolean): MutableFloatState? {
    val meter = remember { mutableFloatStateOf(0.5f) }
    if (!enabled) return null

    // Measured only while the screen is showing: the player stays composed behind a stopped
    // activity, and there is no point paying for a glow nobody can see.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var watching = false
        fun follow() {
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible == watching) return
            watching = visible
            if (visible) MusicPulse.watch() else MusicPulse.unwatch()
        }
        val observer = LifecycleEventObserver { _, _ -> follow() }
        lifecycle.addObserver(observer)
        follow()
        onDispose {
            lifecycle.removeObserver(observer)
            if (watching) MusicPulse.unwatch()
        }
    }
    LaunchedEffect(playing) {
        val fall = FALL_DB_PER_SECOND / RANGE_DB
        var ceiling = CEILING_FLOOR_DB
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                var level by meter
                if (!playing) {
                    // Out of the music the glow eases back to rest rather than jumping.
                    val step = 1f - exp(-seconds / 0.25f)
                    level += (0.5f - level) * step
                    return@withFrameNanos
                }
                val peak = MusicPulse.peak(seconds.coerceAtLeast(1f / 60f)) ?: 0f
                val db = if (peak <= 0f) CEILING_FLOOR_DB - RANGE_DB else 20f * log10(peak)
                ceiling = maxOf(db, ceiling - CEILING_FALL_DB_PER_SECOND * seconds, CEILING_FLOOR_DB)
                val reading = ((db - (ceiling - RANGE_DB)) / RANGE_DB).coerceIn(0f, 1f)
                level = if (reading > level) {
                    level + (reading - level) * (1f - exp(-seconds / RISE_SECONDS))
                } else {
                    maxOf(reading, level - fall * seconds)
                }
            }
            if (!playing && abs(meter.floatValue - 0.5f) < 0.001f) {
                meter.floatValue = 0.5f
                break
            }
        }
    }
    return meter
}

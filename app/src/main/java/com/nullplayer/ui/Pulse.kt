package com.nullplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.nullplayer.playback.MusicPulse
import kotlin.math.abs
import kotlin.math.exp

/**
 * The glow's breath, frame by frame, while [enabled]; null when the pulse is switched off.
 *
 * Playing, it follows [MusicPulse] at the frame being heard. Paused, or with nothing to go on, it
 * settles back to one half, which is the glow as it always was, so stopping the music leaves the
 * button looking as it did before the pulse existed.
 *
 * The value only ever reaches the draw phase, so the loop repaints the glow without composing
 * anything; and it runs only while its button is on screen, which is also the only time the
 * service is asked to measure anything. The player and the mini player each hold one.
 */
@Composable
internal fun rememberMusicPulse(enabled: Boolean, playing: Boolean): MutableFloatState? {
    val breath = remember { mutableFloatStateOf(0.5f) }
    if (!enabled) return null

    DisposableEffect(Unit) {
        MusicPulse.watch()
        onDispose { MusicPulse.unwatch() }
    }
    LaunchedEffect(playing) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                val target = if (playing) MusicPulse.level() ?: 0f else 0.5f
                // Quick to rise and a touch slower to fall, over what the processor already
                // does: enough to hide a frame that lands between two readings, not so much that
                // the hit arrives late. Out of the music the glow eases rather than jumps.
                val settle = when {
                    !playing -> 0.25f
                    target > breath.floatValue -> 0.02f
                    else -> 0.06f
                }
                val step = 1f - exp(-seconds / settle)
                breath.floatValue += (target - breath.floatValue) * step
            }
            if (!playing && abs(breath.floatValue - 0.5f) < 0.001f) {
                breath.floatValue = 0.5f
                break
            }
        }
    }
    return breath
}

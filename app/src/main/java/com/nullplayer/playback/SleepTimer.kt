package com.nullplayer.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sleep timer's deadline, shared between the screen that sets it and the service that acts on
 * it — the same split as [PlaybackGate], and for the same reason: the countdown has to keep
 * running when no Activity exists.
 *
 * Only the deadline is held here. Nothing in this object can stop the music; [PlaybackService]
 * watches [deadline] and does the pausing, so there is one owner of the player as before.
 *
 * Deliberately not persisted. A sleep timer that survived a force-stop and silently paused the
 * music the next morning would be a bug, not a feature.
 */
object SleepTimer {

    /** Durations the button offers, in minutes. */
    val CHOICES = listOf(15, 30, 45, 60, 90)

    /** [SystemClock.elapsedRealtime] at which playback should stop, or null when disarmed. */
    private val _deadline = MutableStateFlow<Long?>(null)
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()

    fun arm(minutes: Int) {
        _deadline.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun cancel() {
        _deadline.value = null
    }

    /** Milliseconds left, or null when nothing is armed. Never negative. */
    fun remaining(): Long? = _deadline.value?.let {
        (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
}

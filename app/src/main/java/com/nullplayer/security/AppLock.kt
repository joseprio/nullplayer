package com.nullplayer.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app itself has been unlocked, for as long as the process lives.
 *
 * Kept out of the ViewModel so a rotation does not re-prompt, and re-armed after a spell in the
 * background. The grace period exists because the two things the dock does — the system file
 * picker and the biometric prompt's own fallback — both stop the Activity, and being asked to
 * authenticate again on the way back from choosing a file would train the user to stop reading
 * the prompt.
 */
object AppLock {

    private const val GRACE_MS = 30_000L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * Whether the dock has been unlocked. Tracked apart from [unlocked] because the two locks are
     * independent settings: the app can be open to anyone while the library that can erase it is
     * not, which is the default.
     */
    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked.asStateFlow()

    private var backgroundedAt = 0L

    fun unlock() {
        _unlocked.value = true
    }

    fun unlockVault() {
        _vaultUnlocked.value = true
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val away = backgroundedAt
        backgroundedAt = 0L
        if (away != 0L && SystemClock.elapsedRealtime() - away > GRACE_MS) {
            _unlocked.value = false
            _vaultUnlocked.value = false
        }
    }
}

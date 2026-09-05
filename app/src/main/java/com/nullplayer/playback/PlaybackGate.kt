package com.nullplayer.playback

import android.content.Context
import com.nullplayer.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one place that decides whether the music is allowed to start.
 *
 * It is a singleton because both sides of the app need the same answer: the UI, so pressing play
 * can raise a biometric prompt instead of silently doing nothing, and [PlaybackService], so the
 * notification, a Bluetooth remote and a car head unit are held to the same rules. App and service
 * share a process, so one object serves both without a round trip through the media session.
 *
 * The gate never *starts* playback — it only refuses it. Anything that wants to lift a refusal has
 * to go through [unlockPlayback], which only the biometric prompt calls.
 */
object PlaybackGate {

    enum class Block {
        /** The required headset or output device is not connected. */
        OUTPUT_DEVICE,

        /** The same device is missing, turning away VoiceOver rather than the music. */
        VOICE_OVER_OUTPUT,

        /** Biometrics are required and have not been given for this stretch of playback. */
        BIOMETRIC,
    }

    data class State(
        val loaded: Boolean = false,
        val requireBiometricToPlay: Boolean = false,
        val requireOutputDevice: Boolean = false,
        val requiredDeviceKey: String = "",
        val preferredDeviceKey: String = "",
        val outputs: List<AudioOutput> = emptyList(),
        val unlockedForPlayback: Boolean = false,
    ) {
        val outputSatisfied: Boolean
            get() = when {
                !requireOutputDevice -> true
                requiredDeviceKey.isNotEmpty() -> outputs.any { it.key == requiredDeviceKey }
                else -> outputs.any { it.isHeadset }
            }

        /** What the user asked for, named the way they chose it. */
        val requiredOutputLabel: String
            get() = when {
                requiredDeviceKey.isEmpty() -> "headphones"
                else -> outputs.firstOrNull { it.key == requiredDeviceKey }?.label
                    ?: AudioOutputs.labelForKey(requiredDeviceKey)
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set when a play attempt is turned away, so whoever is watching can say why. */
    private val _blocked = MutableStateFlow<Block?>(null)
    val blocked: StateFlow<Block?> = _blocked.asStateFlow()

    private var bound = false

    /** Wired up once, from `NullPlayerApp`, so the answer is ready before anything asks. */
    @Synchronized
    fun bind(context: Context, scope: CoroutineScope) {
        if (bound) return
        bound = true

        val settings = Settings(context.applicationContext)
        val outputs = AudioOutputs(context.applicationContext)

        scope.launch {
            settings.all.collect { config ->
                _state.update {
                    it.copy(
                        loaded = true,
                        requireBiometricToPlay = config.lockOnPlay,
                        requireOutputDevice = config.requireOutputDevice,
                        requiredDeviceKey = config.requiredDeviceKey,
                        preferredDeviceKey = config.preferredDeviceKey,
                    )
                }
            }
        }
        scope.launch {
            outputs.changes().collect { connected ->
                _state.update { it.copy(outputs = connected) }
            }
        }
    }

    /** Why playback cannot start right now, or null if it can. */
    fun blockReason(): Block? {
        val current = _state.value
        return when {
            !current.outputSatisfied -> Block.OUTPUT_DEVICE
            current.requireBiometricToPlay && !current.unlockedForPlayback -> Block.BIOMETRIC
            else -> null
        }
    }

    /**
     * The check the player wrapper makes on every play. Records the refusal so a press that came
     * from the notification — where no prompt can be raised — can still be explained in the app.
     */
    fun allowPlayback(): Boolean {
        val reason = blockReason()
        _blocked.value = reason
        return reason == null
    }

    /**
     * The same check for VoiceOver, which the output rule covers too.
     *
     * VoiceOver is the one thing in this app that says a track's name out loud, so through the
     * phone's own speaker it would read the title to whoever is in the room — the very thing
     * "only play to headphones" was turned on to prevent. Biometrics are deliberately not
     * consulted: they gate the music, and being told what is playing is not a way into the vault.
     */
    fun allowVoiceOver(): Boolean {
        val satisfied = _state.value.outputSatisfied
        if (!satisfied) _blocked.value = Block.VOICE_OVER_OUTPUT
        return satisfied
    }

    /** Called only after a successful biometric prompt. */
    fun unlockPlayback() {
        _blocked.value = null
        _state.update { it.copy(unlockedForPlayback = true) }
    }

    /**
     * Drops the unlock. Playback stopping — pausing, ending, the headphones being pulled — spends
     * it, so "require biometrics to play" also means "require them again to resume".
     */
    fun relockPlayback() {
        _state.update { it.copy(unlockedForPlayback = false) }
    }

    fun consumeBlock() {
        _blocked.value = null
    }
}

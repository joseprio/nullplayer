package com.nullplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One voice the text-to-speech engine offers, named the way a person would recognise it. */
data class VoiceOption(
    /** The engine's own identifier, which is what gets stored. */
    val id: String,
    val label: String,
)

/**
 * What the speech engine can do, published for the settings screen to draw.
 *
 * The same split as [AudioEffects]: the engine itself belongs to [PlaybackService], because
 * VoiceOver has to work when no Activity exists, but the list of voices is only ever read.
 */
object Voices {

    private val _available = MutableStateFlow<List<VoiceOption>>(emptyList())
    val available: StateFlow<List<VoiceOption>> = _available.asStateFlow()

    internal fun publish(options: List<VoiceOption>) {
        _available.value = options
    }
}

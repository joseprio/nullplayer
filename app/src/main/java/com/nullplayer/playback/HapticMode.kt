package com.nullplayer.playback

/**
 * How the phone vibrates with the music, if it does.
 *
 * [AUDIO] follows the sound itself and needs haptic channels in the phone's audio path, which few
 * phones have. [SUBWOOFER] drives the vibrator directly, so any phone whose vibrator can vary its
 * strength can use it.
 */
enum class HapticMode {
    OFF,

    /** The platform's [android.media.audiofx.HapticGenerator], worked out from the audio. */
    AUDIO,

    /** The vibrator follows the music's sub-bass as it is heard; see [SubwooferHaptics]. */
    SUBWOOFER;

    companion object {
        /** Stored as an ordinal; anything out of range is off, not a crash. */
        fun ofOrdinal(ordinal: Int): HapticMode = entries.getOrElse(ordinal) { OFF }
    }
}

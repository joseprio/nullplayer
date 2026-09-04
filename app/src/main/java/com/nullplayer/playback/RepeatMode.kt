package com.nullplayer.playback

import androidx.media3.common.Player

/**
 * The repeat button's three states.
 *
 * A local enum rather than the raw `Player.REPEAT_MODE_*` ints so the setting can be stored and
 * cycled without either the UI or the preference file having to know Media3's numbering.
 */
enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    @get:Player.RepeatMode
    val playerValue: Int
        get() = when (this) {
            OFF -> Player.REPEAT_MODE_OFF
            ALL -> Player.REPEAT_MODE_ALL
            ONE -> Player.REPEAT_MODE_ONE
        }

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun ofOrdinal(ordinal: Int): RepeatMode = entries.getOrElse(ordinal) { ALL }
    }
}

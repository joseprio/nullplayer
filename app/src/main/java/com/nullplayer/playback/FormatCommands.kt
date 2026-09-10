package com.nullplayer.playback

/**
 * The custom session command that carries the playing file's shape out to the UI.
 *
 * It only ever travels one way. The renderer's format is known in [PlaybackService], where the
 * real player is, and a controller cannot ask a session what the decoder was handed -- so the
 * service says so whenever it changes, the same way it announces VoiceOver starting and stopping.
 */
object FormatCommands {

    /** Broadcast to every controller. Its arguments are an [AudioProfile], or empty for none. */
    const val PROFILE_CHANGED = "com.nullplayer.command.PROFILE_CHANGED"

    val ALL = listOf(PROFILE_CHANGED)
}

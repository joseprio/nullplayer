package com.nullplayer.playback

/**
 * The custom session commands that carry VoiceOver between the UI and [PlaybackService].
 *
 * VoiceOver lives in the service rather than the ViewModel because the notification's speak button
 * has to work when no Activity exists. Routing the in-app button through the same commands keeps a
 * single text-to-speech engine and a single definition of what "speak" means.
 */
object VoiceCommands {

    const val SPEAK_TRACK = "com.nullplayer.command.SPEAK_TRACK"
    const val SPEAK_POSITION = "com.nullplayer.command.SPEAK_POSITION"
    const val STOP_SPEAKING = "com.nullplayer.command.STOP_SPEAKING"

    /** Broadcast from the service to every controller so the on-screen button can light up. */
    const val SPEAKING_CHANGED = "com.nullplayer.command.SPEAKING_CHANGED"

    /** Optional [SPEAK_TRACK] argument naming a specific track, used by the dock's rows. */
    const val EXTRA_TRACK_ID = "track_id"

    const val EXTRA_SPEAKING = "speaking"

    val ALL = listOf(SPEAK_TRACK, SPEAK_POSITION, STOP_SPEAKING, SPEAKING_CHANGED)
}

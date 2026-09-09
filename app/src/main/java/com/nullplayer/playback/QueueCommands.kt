package com.nullplayer.playback

/**
 * The custom session commands that carry a skip from the UI to [PlaybackService].
 *
 * Skipping wraps at both ends of the queue whatever the repeat mode says, and the ends are exactly
 * where Media3 withdraws the ordinary skip commands: `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` is granted
 * only while the player says there *is* a next item, and a controller drops a call to a command it
 * has not been granted before the session ever hears it. Re-advertising the command from the
 * service does not help either — what a controller may do is the intersection of what the session
 * grants and what the player itself reports, and the player is the half that keeps saying no.
 *
 * A custom command is not gated that way. It is a message to the service, which then performs the
 * skip on the real player, where the queue's true order — shuffled or not — is known.
 */
object QueueCommands {

    const val SKIP_NEXT = "com.nullplayer.command.SKIP_NEXT"
    const val SKIP_PREVIOUS = "com.nullplayer.command.SKIP_PREVIOUS"

    val ALL = listOf(SKIP_NEXT, SKIP_PREVIOUS)
}

package com.nullplayer.playback

import java.nio.ByteBuffer

/**
 * Hands [bytes] of [input] straight to [output], untouched.
 *
 * Both processors in the chain spend most of their life doing nothing: the equalizer is off, or
 * its curve is flat, or normalisation has settled at unity gain. They stay in the chain anyway —
 * `isActive` cannot know what the settings will be by the time the music reaches it — so "doing
 * nothing" still means moving every sample from one buffer to the other.
 *
 * Done a sample at a time through `putFloat`, that is a few hundred thousand bounds-checked calls
 * a second for a copy. `ByteBuffer.put` is one bulk move, and on a direct buffer that is a memcpy.
 * The audio thread has a deadline and no reason to spend it this way.
 *
 * The limit is walked in rather than trusting `remaining`: the caller has already rounded down to
 * whole frames, and a partial frame at the end of a buffer must not be copied past the end of an
 * output sized for the frames alone.
 */
internal fun copyThrough(input: ByteBuffer, output: ByteBuffer, bytes: Int) {
    val limit = input.limit()
    input.limit(input.position() + bytes)
    output.put(input)
    input.limit(limit)
}

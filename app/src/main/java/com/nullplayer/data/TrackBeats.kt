package com.nullplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where a track's beats fall, as the analysis sweep found them.
 *
 * A table of its own rather than columns on the track, the way the haptic envelopes once were:
 * it is a few kilobytes of blob that nothing but playback reads, and a missing row — or one
 * from an older [version] of the analysis — is the sweep's work queue.
 *
 * [beats] is [com.nullplayer.playback.Beats.encode]'s packing. A track the analysis found no
 * steady beat in has a row too, empty and with a [confidence] of zero, so it is not asked again.
 */
@Entity(tableName = "beats")
class TrackBeats(
    @PrimaryKey val trackId: String,
    val version: Int,
    val confidence: Float,
    val beats: ByteArray,
)

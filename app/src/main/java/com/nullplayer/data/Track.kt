package com.nullplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Everything the app knows about a track.
 *
 * These strings reach the screen in exactly one place, the dock, which is the screen for managing
 * the library and sits behind a biometric prompt by default. Everywhere else — the player, the
 * notification, a car head unit — they are only ever spoken, by
 * [com.nullplayer.playback.VoiceOver].
 */
@Entity(tableName = "tracks")
data class Track(
    /** Random id. Doubles as the vault filename, so the filename leaks nothing. */
    @PrimaryKey val id: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
    val year: String?,
    val durationMs: Long,
    val addedAt: Long,
    /** Playback order in the vault. */
    val sortIndex: Int,
    /**
     * Integrated loudness in LUFS, or null while the file is still waiting to be measured.
     *
     * Nullable rather than zeroed, because "not measured yet" and "measured, and it is silence"
     * are different answers and only one of them is worth queueing another scan for.
     */
    val loudnessLufs: Double? = null,
    /** The loudest sample in the file against a full scale of 1.0. Null alongside [loudnessLufs]. */
    val peakAmplitude: Double? = null,
)

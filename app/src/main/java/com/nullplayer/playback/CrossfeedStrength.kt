package com.nullplayer.playback

import kotlin.math.pow

/**
 * How much of a room crossfeed puts back: where the head's shadow begins, and how much quieter
 * the far ear is below it.
 *
 * These are the three settings bs2b ships, named for the circuits they reproduce, and in the
 * order anyone would try them. The differences are not subtle once heard side by side, which is
 * why they are a choice rather than a constant: 4.5 dB is what most people mean by "natural",
 * and some of them cannot hear it at all; 9.5 dB is unmistakable, and a modern mix made with
 * headphones in mind sounds smaller for it.
 */
enum class CrossfeedStrength(
    val label: String,
    /** Below this the far ear hears nearly everything; above it, less and less. */
    val cutoffHz: Double,
    /** How much quieter the far ear is than the near one, at the frequencies it hears at all. */
    val levelDb: Double,
) {
    NATURAL("Natural", cutoffHz = 700.0, levelDb = 4.5),
    CHU_MOY("Chu Moy", cutoffHz = 700.0, levelDb = 6.0),
    MEIER("Meier", cutoffHz = 650.0, levelDb = 9.5);

    /**
     * The share of a hard-panned bass note that crosses to the far side.
     *
     * With this much fed across and the same amount taken from the near side, the near ear keeps
     * `1 - feed` and the far one gets `feed`, and their ratio is [levelDb]. A note in the middle
     * of the image loses and gains the same amount and comes through untouched.
     */
    val feed: Double = 1.0 / (1.0 + 10.0.pow(levelDb / 20.0))

    companion object {
        /** Stored as an ordinal; anything out of range is the gentle one, not a crash. */
        fun ofOrdinal(ordinal: Int): CrossfeedStrength = entries.getOrElse(ordinal) { NATURAL }
    }
}

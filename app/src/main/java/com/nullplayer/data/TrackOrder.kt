package com.nullplayer.data

/**
 * How a tile's tracks are laid out before they become a queue.
 *
 * A property of the tile rather than of the app, for the same reason shuffle and repeat are: an
 * album wants the order it was pressed in, a big vault wants whatever you last asked for, and
 * moving the ribbon should not carry one tile's answer onto the next. It travels with them, in
 * [TileModes].
 *
 * This decides the order the queue is *written* in, which is a different question from the order
 * it is *played* in — shuffle still has the last word on what comes next. What it always governs
 * is the numbering: "track 41 of 96" counts down this list, so ordering by artist is what makes
 * the go-to-track dialog worth typing an artist's stretch of numbers into.
 */
enum class TrackOrder(val label: String) {
    /** When the file landed in the vault. The order everything was in before there was a choice. */
    ADDED("Date added"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RELEASED("Release date");

    companion object {
        /** Anything unrecognised is [ADDED], which is what a tile never asked about already has. */
        fun ofOrdinal(ordinal: Int): TrackOrder = entries.getOrElse(ordinal) { ADDED }
    }
}

/**
 * The tracks in [order], reversed if asked.
 *
 * Reversing is applied to the finished list rather than folded into the comparator, so it means
 * exactly what it says: the last track becomes the first, tie-breaks and all. Tracks missing the
 * field being sorted on gather at the end, and a reverse duly brings them to the front — "unknown
 * artist" is a group like any other, and hiding it at one end whichever way the list runs would
 * make the toggle a half-truth.
 */
fun List<Track>.ordered(order: TrackOrder, reversed: Boolean): List<Track> {
    val sorted = sortedWith(order.comparator)
    return if (reversed) sorted.reversed() else sorted
}

/**
 * The whole ordering, tie-breaks included.
 *
 * Every one of them ends on the vault index, so the result is total: two tracks that agree on
 * everything the user chose to sort by still land in a fixed order, and the list does not
 * reshuffle itself under them when the queue is rebuilt for an unrelated reason.
 */
private val TrackOrder.comparator: Comparator<Track>
    get() = when (this) {
        // The timestamp rather than the vault index, because they are the same order and only one
        // of them is what the user was promised.
        TrackOrder.ADDED -> compareBy<Track> { it.addedAt } then byIndex
        TrackOrder.ARTIST -> byText { it.artist } then byText { it.album } then
            byNumber { it.trackNumber } then byText { it.title } then byIndex
        TrackOrder.ALBUM -> byText { it.album } then byNumber { it.trackNumber } then
            byText { it.title } then byIndex
        // Artist next, so a year that half the library shares still reads as something rather
        // than as a shuffle of everything released in 1994.
        TrackOrder.RELEASED -> byNumber { it.releaseYear } then byText { it.artist } then
            byText { it.album } then byNumber { it.trackNumber } then byIndex
    }

private val byIndex: Comparator<Track> = compareBy { it.sortIndex }

/**
 * The four digits out of whatever the file called its year.
 *
 * Tags carry anything from "1994" to "1994-03-21" to "(1994)", and all three mean the same year.
 * Nothing finer than the year is offered because nothing finer is reliably there.
 */
private val Track.releaseYear: Int?
    get() = year?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

/**
 * Case-insensitive, with the blanks last.
 *
 * A missing tag and an empty one are the same thing to a reader, so they are the same thing here
 * — otherwise a file tagged with a stray space would sort into its own place at the top.
 */
private fun byText(selector: (Track) -> String?): Comparator<Track> = Comparator { a, b ->
    val left = selector(a)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    val right = selector(b)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    when {
        left == right -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }
}

/** The same bargain for a number that may not be there. */
private fun byNumber(selector: (Track) -> Int?): Comparator<Track> = Comparator { a, b ->
    val left = selector(a)
    val right = selector(b)
    when {
        left == right -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }
}

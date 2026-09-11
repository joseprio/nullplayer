package com.nullplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a tile lays its tracks out.
 *
 * Worth testing because the ordering is what the queue numbering counts down, and a comparator
 * that is merely *nearly* total fails quietly: the list comes back in a plausible order that
 * happens to differ from the last one, and "go to track 41" lands somewhere else than it did a
 * minute ago with nothing on screen to say why.
 */
class TrackOrderTest {

    private fun track(
        id: String,
        artist: String? = null,
        album: String? = null,
        trackNumber: Int? = null,
        year: String? = null,
        addedAt: Long = 0L,
        sortIndex: Int = 0,
    ) = Track(
        id = id,
        title = id,
        artist = artist,
        album = album,
        trackNumber = trackNumber,
        year = year,
        durationMs = 0L,
        addedAt = addedAt,
        sortIndex = sortIndex,
    )

    private fun List<Track>.ids() = map { it.id }

    @Test
    fun `date added runs oldest first`() {
        val tracks = listOf(
            track("late", addedAt = 300L, sortIndex = 2),
            track("early", addedAt = 100L, sortIndex = 0),
            track("middle", addedAt = 200L, sortIndex = 1),
        )
        assertEquals(
            listOf("early", "middle", "late"),
            tracks.ordered(TrackOrder.ADDED, reversed = false).ids(),
        )
    }

    @Test
    fun `reversing turns the whole list round, tie-breaks and all`() {
        val tracks = listOf(
            track("a", addedAt = 100L, sortIndex = 0),
            track("b", addedAt = 100L, sortIndex = 1),
            track("c", addedAt = 200L, sortIndex = 2),
        )
        assertEquals(
            listOf("c", "b", "a"),
            tracks.ordered(TrackOrder.ADDED, reversed = true).ids(),
        )
    }

    @Test
    fun `artist gathers an artist's albums, and each album's tracks in order`() {
        val tracks = listOf(
            track("b2", artist = "Bowie", album = "Low", trackNumber = 2),
            track("a1", artist = "ABBA", album = "Arrival", trackNumber = 1),
            track("b1", artist = "Bowie", album = "Heroes", trackNumber = 1),
            track("b3", artist = "Bowie", album = "Low", trackNumber = 1),
        )
        assertEquals(
            listOf("a1", "b1", "b3", "b2"),
            tracks.ordered(TrackOrder.ARTIST, reversed = false).ids(),
        )
    }

    @Test
    fun `case and stray whitespace do not make a second artist`() {
        val tracks = listOf(
            track("upper", artist = "BOWIE", album = "b", sortIndex = 1),
            track("apart", artist = "Aphex Twin", sortIndex = 0),
            track("padded", artist = " bowie ", album = "a", sortIndex = 2),
        )
        // Both spellings sort as one artist, so the album is what separates them.
        assertEquals(
            listOf("apart", "padded", "upper"),
            tracks.ordered(TrackOrder.ARTIST, reversed = false).ids(),
        )
    }

    @Test
    fun `an untagged track sorts last rather than first`() {
        // Nulls at the top would put every untagged file in front of the library, which is the
        // one place a chosen order has nothing to say.
        val tracks = listOf(
            track("blank", artist = "   "),
            track("missing", artist = null),
            track("named", artist = "Zappa"),
        )
        assertEquals("named", tracks.ordered(TrackOrder.ARTIST, reversed = false).ids().first())
    }

    @Test
    fun `a release date is the year out of whatever the tag says`() {
        val tracks = listOf(
            track("dated", year = "1994-03-21"),
            track("bracketed", year = "(1977)"),
            track("plain", year = "2001"),
        )
        assertEquals(
            listOf("bracketed", "dated", "plain"),
            tracks.ordered(TrackOrder.RELEASED, reversed = false).ids(),
        )
    }

    @Test
    fun `an unreadable year is an absent one`() {
        val tracks = listOf(
            track("rubbish", year = "unknown"),
            track("real", year = "1999"),
        )
        assertEquals(
            listOf("real", "rubbish"),
            tracks.ordered(TrackOrder.RELEASED, reversed = false).ids(),
        )
    }

    @Test
    fun `tracks that agree on everything still come back in a fixed order`() {
        val tracks = listOf(
            track("second", artist = "Same", album = "Same", trackNumber = 1, sortIndex = 9),
            track("first", artist = "Same", album = "Same", trackNumber = 1, sortIndex = 4),
        )
        assertEquals(
            listOf("first", "second"),
            tracks.ordered(TrackOrder.ARTIST, reversed = false).ids(),
        )
    }

    @Test
    fun `an unrecognised ordinal is the order everything already had`() {
        assertEquals(TrackOrder.ADDED, TrackOrder.ofOrdinal(-1))
        assertEquals(TrackOrder.ADDED, TrackOrder.ofOrdinal(99))
    }
}

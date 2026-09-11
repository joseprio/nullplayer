package com.nullplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a tile's shuffle, repeat and running order survive being written down.
 *
 * The codec is the only part of this that can be wrong quietly. A tile whose modes fail to decode
 * does not throw and does not show a mark anywhere — it silently plays like the app-wide default,
 * which is a plausible enough answer that nobody would think to look for a bug behind it.
 */
class TileModesTest {

    private val vault = TileModes(shuffle = true, repeatOrdinal = 1)
    private val album = TileModes(shuffle = false, repeatOrdinal = 0)

    @Test
    fun `a map survives the round trip`() {
        val modes = mapOf("a1b2" to album, "~favorites" to vault)
        assertEquals(modes, TileModes.decode(TileModes.encode(modes)))
    }

    @Test
    fun `the vault's blank id survives the round trip`() {
        // It is the one id that is empty, so it is the one that would be lost by a codec that
        // treated a missing field and an empty one as the same thing.
        val modes = mapOf(Group.VAULT_ID to album)
        val decoded = TileModes.decode(TileModes.encode(modes))
        assertEquals(album, decoded[Group.VAULT_ID])
        assertEquals(1, decoded.size)
    }

    @Test
    fun `nothing stored is no tiles rather than a broken one`() {
        assertEquals(emptyMap<String, TileModes>(), TileModes.decode(null))
        assertEquals(emptyMap<String, TileModes>(), TileModes.decode(""))
    }

    @Test
    fun `a damaged line is dropped and the rest still load`() {
        val stored = "good:1:2:2:1\nrubbish\nalso:bad:x:0:0\nfine:0:0:0:0"
        assertEquals(
            mapOf(
                "good" to TileModes(
                    shuffle = true,
                    repeatOrdinal = 2,
                    orderOrdinal = 2,
                    reversed = true,
                ),
                "fine" to TileModes(shuffle = false, repeatOrdinal = 0),
            ),
            TileModes.decode(stored),
        )
    }

    // -- upgrading from before a tile had an order ------------------------------------------------

    @Test
    fun `a line written before there was an order reads as the default one`() {
        // Which is the order that install was already playing in, so an upgrade moves nothing.
        val decoded = TileModes.decode("old:0:2")
        assertEquals(
            TileModes(
                shuffle = false,
                repeatOrdinal = 2,
                orderOrdinal = TrackOrder.ADDED.ordinal,
                reversed = false,
            ),
            decoded["old"],
        )
    }

    @Test
    fun `an order survives the round trip`() {
        val sorted = TileModes(
            shuffle = false,
            repeatOrdinal = 1,
            orderOrdinal = TrackOrder.ARTIST.ordinal,
            reversed = true,
        )
        val modes = mapOf(Group.VAULT_ID to sorted, "a1b2" to album)
        assertEquals(modes, TileModes.decode(TileModes.encode(modes)))
    }

    @Test
    fun `a line with a field count nobody writes is dropped`() {
        // Four fields is neither the old shape nor the new one, so it is a file half-written or
        // half-read rather than an older version of this app -- and guessing which field was
        // meant to be missing would be guessing at how the tile plays.
        assertEquals(emptyMap<String, TileModes>(), TileModes.decode("odd:1:2:3"))
    }

    // -- what a tile falls back on ----------------------------------------------------------------

    @Test
    fun `a tile never asked about inherits the app-wide pair`() {
        // The two legacy preferences, which is what an install upgrading into this feature has.
        val settings = AppSettings(shuffle = false, repeatOrdinal = 2)
        assertEquals(TileModes(shuffle = false, repeatOrdinal = 2), settings.modesFor("anything"))
    }

    @Test
    fun `a tile with its own answer keeps it`() {
        val settings = AppSettings(
            shuffle = false,
            repeatOrdinal = 2,
            tileModes = mapOf("mine" to album),
        )
        assertEquals(album, settings.modesFor("mine"))
        assertEquals(TileModes(shuffle = false, repeatOrdinal = 2), settings.modesFor("theirs"))
    }

    @Test
    fun `every tile has an answer, the two standing ones included`() {
        val settings = AppSettings(tileModes = mapOf(Group.FAVORITES_ID to album))
        assertEquals(album, settings.modesFor(Group.FAVORITES_ID))
        assertEquals(TileModes(shuffle = true, repeatOrdinal = 1), settings.modesFor(Group.VAULT_ID))
    }
}

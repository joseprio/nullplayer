package com.nullplayer.playback

import com.nullplayer.data.AppSettings
import com.nullplayer.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the announcement says once the user has had a say in it.
 *
 * The line is only ever spoken, so nothing on any screen would show these joins to be wrong: a
 * dropped part leaving a doubled full stop, or every switch off leaving a button that answers
 * with silence, both sound like the engine failing rather than like the setting working.
 */
class VoiceOverTest {

    private val track = Track(
        id = "1",
        title = "Blue Monday",
        artist = "New Order",
        album = "Power, Corruption and Lies",
        trackNumber = 1,
        year = "1983",
        durationMs = 450_000,
        addedAt = 0,
        sortIndex = 0,
    )

    @Test
    fun `every part on names all four`() {
        assertEquals(
            "Blue Monday. by New Order. from Power, Corruption and Lies. 1983.",
            VoiceOver.describe(track),
        )
    }

    @Test
    fun `a part switched off is not said at all`() {
        assertEquals(
            "Blue Monday. 1983.",
            VoiceOver.describe(track, setOf(VoicePart.TITLE, VoicePart.YEAR)),
        )
    }

    @Test
    fun `the title can be dropped like any other part`() {
        assertEquals(
            "by New Order.",
            VoiceOver.describe(track, setOf(VoicePart.ARTIST)),
        )
    }

    @Test
    fun `a part that is on but untagged is still skipped`() {
        val untagged = track.copy(album = null, year = null)
        assertEquals("Blue Monday. by New Order.", VoiceOver.describe(untagged))
    }

    @Test
    fun `an untitled file is named as such while the title is on`() {
        assertEquals(
            "Untitled track.",
            VoiceOver.describe(track.copy(title = null), setOf(VoicePart.TITLE)),
        )
    }

    @Test
    fun `every part off says so rather than nothing`() {
        assertEquals("Nothing to announce.", VoiceOver.describe(track, emptySet()))
    }

    @Test
    fun `switched down to tags this file does not carry says the same`() {
        val untagged = track.copy(artist = null)
        assertEquals("Nothing to announce.", VoiceOver.describe(untagged, setOf(VoicePart.ARTIST)))
    }

    @Test
    fun `an empty queue is unaffected by any of it`() {
        assertEquals("No song loaded.", VoiceOver.describe(null, emptySet()))
    }

    // -- the switches as they are stored ---------------------------------------------------------

    @Test
    fun `a fresh install says everything`() {
        assertEquals(VoicePart.entries.toSet(), AppSettings().voiceParts)
    }

    @Test
    fun `each switch governs its own part`() {
        assertEquals(
            setOf(VoicePart.TITLE, VoicePart.ALBUM),
            AppSettings(speakArtist = false, speakYear = false).voiceParts,
        )
    }
}

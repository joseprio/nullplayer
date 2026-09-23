package com.nullplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line of small print, and the mark at the end of it.
 *
 * These are the branches a device cannot easily be made to exercise: the vault would need a file
 * of every format to walk through them, and the ones that matter most -- FLAC, M4A, a variable
 * bitrate MP3 -- are exactly the ones that were taken on trust when this shipped.
 */
@UnstableApi
class AudioProfileTest {

    // -- what the line reads --------------------------------------------------------------------

    @Test
    fun `a lossy file is described by its bitrate`() {
        assertEquals(
            "MP3 · 320 kbps",
            AudioProfile("MP3", lossless = false, bitrateKbps = 320).summary,
        )
    }

    @Test
    fun `a varying bitrate says so`() {
        assertEquals(
            "MP3 · 160 kbps VBR",
            AudioProfile("MP3", lossless = false, bitrateKbps = 160, variableBitrate = true)
                .summary,
        )
    }

    @Test
    fun `a varying bitrate with no average still says which it is`() {
        assertEquals(
            "MP3 · VBR",
            AudioProfile("MP3", lossless = false, variableBitrate = true).summary,
        )
    }

    @Test
    fun `a lossy file with nothing known is just its format`() {
        assertEquals("AAC", AudioProfile("AAC", lossless = false).summary)
    }

    @Test
    fun `a lossless file is described by its rate and depth`() {
        assertEquals(
            "FLAC · 96 kHz · 24 bit",
            AudioProfile("FLAC", lossless = true, sampleRateHz = 96_000, bits = 24).summary,
        )
    }

    @Test
    fun `a rate that is not a whole number of kilohertz keeps its decimal`() {
        assertEquals(
            "WAV · 44.1 kHz · 16 bit",
            AudioProfile("WAV", lossless = true, sampleRateHz = 44_100, bits = 16).summary,
        )
    }

    @Test
    fun `a whole number of kilohertz does not carry a trailing zero`() {
        assertEquals(
            "WAV · 48 kHz · 24 bit",
            AudioProfile("WAV", lossless = true, sampleRateHz = 48_000, bits = 24).summary,
        )
    }

    @Test
    fun `hi-res is lossless and better than a CD in depth or rate`() {
        assertTrue(AudioProfile("FLAC", lossless = true, sampleRateHz = 96_000, bits = 24).hiRes)
        assertTrue(AudioProfile("FLAC", lossless = true, sampleRateHz = 192_000, bits = 32).hiRes)
        assertTrue(AudioProfile("FLAC", lossless = true, sampleRateHz = 44_100, bits = 24).hiRes)
        assertTrue(AudioProfile("FLAC", lossless = true, sampleRateHz = 96_000, bits = 16).hiRes)
        assertTrue(AudioProfile("WAV", lossless = true, sampleRateHz = 48_000, bits = 16).hiRes)
        assertTrue(AudioProfile("FLAC", lossless = true, bits = 24).hiRes)
    }

    @Test
    fun `CD quality or less is not hi-res`() {
        assertFalse(AudioProfile("FLAC", lossless = true, sampleRateHz = 44_100, bits = 16).hiRes)
        assertFalse(AudioProfile("WAV", lossless = true, sampleRateHz = 22_050, bits = 16).hiRes)
        assertFalse(AudioProfile("FLAC", lossless = true, sampleRateHz = 44_100).hiRes)
        assertFalse(AudioProfile("FLAC", lossless = true).hiRes)
    }

    @Test
    fun `a lossy file is never hi-res whatever the decoder reports`() {
        assertFalse(AudioProfile("AAC", lossless = false, sampleRateHz = 96_000, bits = 24).hiRes)
    }

    @Test
    fun `half of a lossless pair is better than neither`() {
        assertEquals(
            "FLAC · 44.1 kHz",
            AudioProfile("FLAC", lossless = true, sampleRateHz = 44_100).summary,
        )
        assertEquals(
            "FLAC · 16 bit",
            AudioProfile("FLAC", lossless = true, bits = 16).summary,
        )
    }

    @Test
    fun `a lossless file never advertises a bitrate`() {
        // It would be a fact about how well the file compressed, not about how it sounds.
        val profile = AudioProfile(
            "FLAC",
            lossless = true,
            bitrateKbps = 900,
            sampleRateHz = 96_000,
            bits = 24,
        )
        assertEquals("FLAC · 96 kHz · 24 bit", profile.summary)
    }

    // -- what the file is called ------------------------------------------------------------------

    @Test
    fun `a format is named the way the file is`() {
        assertEquals("MP3", AudioProfile.labelFor("audio/mpeg", null))
        assertEquals("FLAC", AudioProfile.labelFor("audio/flac", null))
        assertEquals("OGG", AudioProfile.labelFor("audio/vorbis", "audio/ogg"))
        assertEquals("Opus", AudioProfile.labelFor("audio/opus", "audio/ogg"))
        assertEquals("WAV", AudioProfile.labelFor("audio/raw", "audio/wav"))
        assertEquals("WAV", AudioProfile.labelFor("audio/wav", null))
    }

    @Test
    fun `anything in an mp4 box is called what the file is called`() {
        // AAC and ALAC share nothing but the container, and the container is the .m4a extension.
        assertEquals("M4A", AudioProfile.labelFor("audio/mp4a-latm", "audio/mp4"))
        assertEquals("M4A", AudioProfile.labelFor("audio/alac", "audio/mp4"))
        assertEquals("M4A", AudioProfile.labelFor("audio/alac", null))

        // Raw AAC outside a box has no .m4a to be named after.
        assertEquals("AAC", AudioProfile.labelFor("audio/mp4a-latm", null))
    }

    @Test
    fun `an unrecognised format falls back to its subtype`() {
        assertEquals("AC3", AudioProfile.labelFor("audio/ac3", null))
        assertEquals("AMR", AudioProfile.labelFor("audio/amr", null))
    }

    // -- depth ------------------------------------------------------------------------------------

    @Test
    fun `depth comes off the encoding the decoder is producing`() {
        assertEquals(8, AudioProfile.bitsOf(C.ENCODING_PCM_8BIT))
        assertEquals(16, AudioProfile.bitsOf(C.ENCODING_PCM_16BIT))
        assertEquals(16, AudioProfile.bitsOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertEquals(24, AudioProfile.bitsOf(C.ENCODING_PCM_24BIT))
        assertEquals(32, AudioProfile.bitsOf(C.ENCODING_PCM_32BIT))
    }

    @Test
    fun `float is what the sink converts to, not what a file is written in`() {
        assertEquals(null, AudioProfile.bitsOf(C.ENCODING_PCM_FLOAT))
        assertEquals(null, AudioProfile.bitsOf(C.ENCODING_INVALID))
    }
}

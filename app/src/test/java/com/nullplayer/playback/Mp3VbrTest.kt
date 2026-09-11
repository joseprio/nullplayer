package com.nullplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The header parse that decides whether an MP3 varies its bitrate.
 *
 * This is the one branch of the format line that has never been seen to run: no file in the test
 * vault is variable, and nothing above the bytes can answer the question, so until now the code
 * that reads them was shipped on the strength of the specification alone. Frames are built here
 * rather than sampled from real files, which also means the awkward cases -- mono, MPEG-2, a tag
 * hidden behind a large ID3 block -- can be asked for directly instead of hunted for.
 */
class Mp3VbrTest {

    /**
     * Four bytes of frame header.
     *
     * `FF FB 90 00` is MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo -- the commonest frame in the
     * world. The version bits and the channel mode are the two that move the table's offset, so
     * they are the two this can vary.
     */
    private fun header(mono: Boolean = false, mpeg2: Boolean = false): ByteArray = byteArrayOf(
        0xFF.toByte(),
        if (mpeg2) 0xF3.toByte() else 0xFB.toByte(),
        0x90.toByte(),
        if (mono) 0xC0.toByte() else 0x00,
    )

    /** A stream: optional leading bytes, then a frame, with [tag] written [at] bytes into it. */
    private fun stream(
        tag: String? = null,
        at: Int = 36,
        mono: Boolean = false,
        mpeg2: Boolean = false,
        leading: ByteArray = ByteArray(0),
    ): ByteArray {
        val bytes = ByteArray(leading.size + 256)
        leading.copyInto(bytes)
        header(mono, mpeg2).copyInto(bytes, leading.size)
        tag?.toByteArray(Charsets.US_ASCII)?.copyInto(bytes, leading.size + at)
        return bytes
    }

    /** An ID3v2 header declaring [size] bytes of tag, syncsafe. */
    private fun id3(size: Int, footer: Boolean = false): ByteArray = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
        4, 0,
        if (footer) 0x10 else 0x00,
        (size shr 21 and 0x7F).toByte(),
        (size shr 14 and 0x7F).toByte(),
        (size shr 7 and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    // -- the table behind the header --------------------------------------------------------------

    @Test
    fun `Xing means the bitrate varies`() {
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing"), 0))
    }

    @Test
    fun `VBRI means the bitrate varies`() {
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "VBRI"), 0))
    }

    @Test
    fun `Info is LAME saying it does not`() {
        assertEquals(false, Mp3Vbr.variableIn(stream(tag = "Info"), 0))
    }

    @Test
    fun `a frame with no table at all is taken as constant`() {
        // Variable without a seek table is legal, unseekable, and effectively extinct.
        assertEquals(false, Mp3Vbr.variableIn(stream(tag = null), 0))
    }

    // -- where the table sits ---------------------------------------------------------------------

    @Test
    fun `mono moves the table forward, because the side information is shorter`() {
        // MPEG-1 mono carries 17 bytes of side information rather than 32, so Xing lands at 21.
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing", at = 21, mono = true), 0))

        // And is not looked for where a stereo frame would have kept it.
        assertEquals(false, Mp3Vbr.variableIn(stream(tag = "Xing", at = 36, mono = true), 0))
    }

    @Test
    fun `MPEG-2 moves it too`() {
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing", at = 21, mpeg2 = true), 0))
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing", at = 13, mono = true, mpeg2 = true), 0))
    }

    @Test
    fun `Fraunhofer's table sits at a fixed distance whatever the frame is`() {
        // VBRI is 32 bytes past the header regardless of side information, so a mono frame keeps
        // it at 36 even though Xing would have moved to 21.
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "VBRI", at = 36, mono = true), 0))
    }

    // -- finding the frame ------------------------------------------------------------------------

    @Test
    fun `a stream with no frame in it is unknown rather than constant`() {
        assertNull(Mp3Vbr.variableIn(ByteArray(256), 0))
        assertNull(Mp3Vbr.variableIn(ByteArray(256) { 0x42 }, 0))
    }

    @Test
    fun `a false sync is stepped over`() {
        // 0xFF 0xE0 passes the sync test and fails everything after it: the layer bits are
        // reserved. The parse has to walk past it and find the real frame behind.
        val junk = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x00)
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing", leading = junk), junk.size))
    }

    @Test
    fun `the frame is found after the bytes it was told to start at`() {
        val leading = ByteArray(64) { 0x11 }
        assertEquals(true, Mp3Vbr.variableIn(stream(tag = "Xing", leading = leading), leading.size))
    }

    // -- the tag in front of the audio ------------------------------------------------------------

    @Test
    fun `a stream with no ID3 tag starts at nothing`() {
        val bytes = stream(tag = "Xing")
        assertEquals(0, Mp3Vbr.afterId3(bytes, bytes.size))
    }

    @Test
    fun `an ID3v2 tag is measured as a syncsafe integer`() {
        // 2 shl 7 or 1 is 257, and the ten byte header sits in front of it.
        val bytes = id3(257) + ByteArray(300)
        assertEquals(10 + 257, Mp3Vbr.afterId3(bytes, bytes.size))
    }

    @Test
    fun `a footer is ten bytes more`() {
        val bytes = id3(100, footer = true) + ByteArray(200)
        assertEquals(10 + 100 + 10, Mp3Vbr.afterId3(bytes, bytes.size))
    }

    @Test
    fun `a big tag does not swallow the size's high bits`() {
        // A megabyte of album art: 0x40 shl 21 is where a naive byte read would lose it.
        val size = (0x40 shl 21) or (0x10 shl 14) or (0x08 shl 7) or 0x04
        val bytes = id3(size) + ByteArray(64)
        assertEquals(10 + size, Mp3Vbr.afterId3(bytes, bytes.size))
    }

    @Test
    fun `a tag and the frame behind it are read together`() {
        // The whole path, as the file gives it: a tag, then the audio, then the table in the frame.
        val leading = id3(40) + ByteArray(40)
        val bytes = stream(tag = "Xing", leading = leading)
        val start = Mp3Vbr.afterId3(bytes, bytes.size)
        assertEquals(leading.size, start)
        assertEquals(true, Mp3Vbr.variableIn(bytes, start))
    }

    @Test
    fun `a truncated stream is unknown rather than a guess`() {
        assertEquals(0, Mp3Vbr.afterId3(ByteArray(4), 4))
        assertNull(Mp3Vbr.variableIn(ByteArray(3), 0))
    }
}

package com.nullplayer.playback

import android.util.Log
import com.nullplayer.data.VaultMediaSource
import java.io.File

private const val TAG = "Mp3Vbr"

/**
 * Whether an MP3 was encoded at a varying bitrate, by looking for the header that says so.
 *
 * Nothing above this level can answer the question. `Format.averageBitrate` is an average either
 * way -- ExoPlayer computes it from the Xing header when there is one and from a frame header when
 * there is not, and hands over the same field in both cases -- and the container has no opinion,
 * because an MP3 has no container. So the file itself is asked.
 *
 * An encoder writing a variable stream puts a table at the front of the first frame so that
 * players can seek in it: "Xing" from most, "VBRI" from Fraunhofer's. The same slot reads "Info"
 * when LAME writes a constant stream, which is a direct denial rather than an absence. A file with
 * none of the three is taken as constant, which is what a bare frame stream almost always is --
 * variable without a seek table is legal, unseekable, and effectively extinct.
 *
 * Reading is through [VaultMediaSource], the decrypting reader the tag pass and the loudness sweep
 * already use, so this looks at the vault copy and never puts a plaintext byte anywhere.
 */
internal object Mp3Vbr {

    /** Null when the file cannot be read, or holds no frame this recognises. */
    fun isVariable(file: File): Boolean? = runCatching {
        VaultMediaSource(file).use { source ->
            val head = ByteArray(PROBE_BYTES)
            val read = source.readAt(0, head, 0, head.size)
            if (read <= 0) return null

            // An ID3v2 tag sits in front of the audio and can be any size, so the first frame is
            // looked for after it rather than from the top of the file.
            val start = afterId3(head, read)
            val window = if (start < read) {
                head to start
            } else {
                val further = ByteArray(PROBE_BYTES)
                val got = source.readAt(start.toLong(), further, 0, further.size)
                if (got <= 0) return null
                further to 0
            }
            variableIn(window.first, window.second)
        }
    }.getOrElse {
        Log.i(TAG, "Could not read ${file.name}", it)
        null
    }

    /** The offset the audio starts at: past an ID3v2 tag if one is there, zero if not. */
    private fun afterId3(bytes: ByteArray, length: Int): Int {
        if (length < ID3_HEADER) return 0
        if (bytes[0].toInt().toChar() != 'I' ||
            bytes[1].toInt().toChar() != 'D' ||
            bytes[2].toInt().toChar() != '3'
        ) {
            return 0
        }
        // A syncsafe integer: seven bits per byte, the eighth always clear so the size can never
        // itself look like a frame sync.
        val size = (bytes[6].toInt() and 0x7F shl 21) or
            (bytes[7].toInt() and 0x7F shl 14) or
            (bytes[8].toInt() and 0x7F shl 7) or
            (bytes[9].toInt() and 0x7F)
        val footer = if (bytes[5].toInt() and 0x10 != 0) ID3_HEADER else 0
        return ID3_HEADER + size + footer
    }

    /** Walks forward to the first frame that parses, then reads the slot behind its header. */
    private fun variableIn(bytes: ByteArray, from: Int): Boolean? {
        var at = from
        while (at + FRAME_HEADER < bytes.size) {
            val frame = frameAt(bytes, at)
            if (frame == null) {
                at++
                continue
            }
            // Fraunhofer's table sits at a fixed distance from the header; everyone else's sits
            // after the side information, whose length depends on the version and channel mode.
            if (tagAt(bytes, at + FRAME_HEADER + VBRI_OFFSET) == "VBRI") return true
            return when (tagAt(bytes, at + FRAME_HEADER + frame)) {
                "Xing" -> true
                "Info" -> false
                else -> false
            }
        }
        return null
    }

    /**
     * The side information length of the frame starting at [at], or null if that is not a frame.
     *
     * The reserved values are what make this a check rather than a calculation: a byte pair can
     * pass the sync test by luck, and inside an ID3 tag or a stray APE tag it sometimes does.
     */
    private fun frameAt(bytes: ByteArray, at: Int): Int? {
        val b0 = bytes[at].toInt() and 0xFF
        val b1 = bytes[at + 1].toInt() and 0xFF
        val b2 = bytes[at + 2].toInt() and 0xFF
        val b3 = bytes[at + 3].toInt() and 0xFF

        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null

        val version = b1 shr 3 and 0x03
        val layer = b1 shr 1 and 0x03
        val bitrateIndex = b2 shr 4 and 0x0F
        val rateIndex = b2 shr 2 and 0x03
        val channelMode = b3 shr 6 and 0x03

        if (version == RESERVED_VERSION) return null
        if (layer != LAYER_III) return null
        if (bitrateIndex == 0 || bitrateIndex == 0x0F) return null
        if (rateIndex == RESERVED_RATE) return null

        val mono = channelMode == MONO
        return if (version == MPEG_1) {
            if (mono) 17 else 32
        } else {
            if (mono) 9 else 17
        }
    }

    private fun tagAt(bytes: ByteArray, at: Int): String? {
        if (at < 0 || at + 4 > bytes.size) return null
        return String(bytes, at, 4, Charsets.US_ASCII)
    }

    /** Enough for an ID3 header and then a frame or two of whatever follows it. */
    private const val PROBE_BYTES = 4096

    private const val ID3_HEADER = 10
    private const val FRAME_HEADER = 4
    private const val VBRI_OFFSET = 32

    private const val RESERVED_VERSION = 1
    private const val MPEG_1 = 3
    private const val LAYER_III = 1
    private const val RESERVED_RATE = 3
    private const val MONO = 3
}

package com.nullplayer.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * What the file turned out to be: the line of small print under the queue position.
 *
 * It is read off the decoder rather than out of the database, which is deliberate. Nothing here is
 * stored per track, so there is no column to migrate, no backfill pass for a vault imported before
 * the feature existed, and no chance of the line disagreeing with what is actually coming out of
 * the speaker. The player only ever needs this for the track it is playing, and that is exactly
 * when the decoder knows it.
 *
 * It also stays inside the app's rule about the player screen, which never names a track. A
 * bitrate says what the file is, not what it is of.
 */
data class AudioProfile(
    /** MP3, M4A, FLAC, OGG — what someone would call the file, not its MIME type. */
    val label: String,
    val lossless: Boolean,
    val bitrateKbps: Int? = null,
    val variableBitrate: Boolean = false,
    val sampleRateHz: Int? = null,
    val bits: Int? = null,
) {

    /**
     * The line as it is read.
     *
     * Lossy formats are described by their bitrate, which is the thing that was traded away.
     * Lossless ones are described by their rate and depth, which for those is the whole of the
     * quality -- a bitrate there is a fact about how well the file compressed, not about how it
     * sounds. Anything unknown is simply left out rather than guessed at or marked absent.
     */
    val summary: String
        get() {
            val detail = if (lossless) losslessDetail() else lossyDetail()
            return if (detail == null) label else "$label · $detail"
        }

    /**
     * Whether the file clears the bar for high-resolution audio: lossless, at least 24 bits deep
     * and sampled at 96 kHz or faster.
     *
     * Lossless is part of the test rather than assumed by the other two. A lossy file can carry
     * a high rate and come out of the decoder at 24 bits, and neither says anything about what
     * the encoder threw away on the way in.
     */
    val hiRes: Boolean
        get() = lossless && (bits ?: 0) >= HI_RES_BITS && (sampleRateHz ?: 0) >= HI_RES_RATE_HZ

    private fun losslessDetail(): String? {
        val rate = sampleRateHz?.let { kiloHertz(it) }
        val depth = bits?.let { "$it bit" }
        return when {
            rate != null && depth != null -> "$rate · $depth"
            else -> rate ?: depth
        }
    }

    private fun lossyDetail(): String? {
        val rate = bitrateKbps?.let { "$it kbps" }
        return when {
            rate != null && variableBitrate -> "$rate VBR"
            rate != null -> rate
            // A file known to vary but not to average: still worth saying which of the two it is.
            variableBitrate -> "VBR"
            else -> null
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_LABEL, label)
        putBoolean(KEY_LOSSLESS, lossless)
        putBoolean(KEY_VARIABLE, variableBitrate)
        putInt(KEY_BITRATE, bitrateKbps ?: ABSENT)
        putInt(KEY_RATE, sampleRateHz ?: ABSENT)
        putInt(KEY_BITS, bits ?: ABSENT)
    }

    companion object {

        private const val KEY_LABEL = "label"
        private const val KEY_LOSSLESS = "lossless"
        private const val KEY_VARIABLE = "variable"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_RATE = "rate"
        private const val KEY_BITS = "bits"

        /** Stands in for a null across the session boundary, where a Bundle has no nullable Int. */
        private const val ABSENT = 0

        private const val HI_RES_BITS = 24
        private const val HI_RES_RATE_HZ = 96_000

        fun fromBundle(bundle: Bundle): AudioProfile? {
            val label = bundle.getString(KEY_LABEL) ?: return null
            return AudioProfile(
                label = label,
                lossless = bundle.getBoolean(KEY_LOSSLESS),
                variableBitrate = bundle.getBoolean(KEY_VARIABLE),
                bitrateKbps = bundle.getInt(KEY_BITRATE).takeIf { it != ABSENT },
                sampleRateHz = bundle.getInt(KEY_RATE).takeIf { it != ABSENT },
                bits = bundle.getInt(KEY_BITS).takeIf { it != ABSENT },
            )
        }

        /**
         * Reads a profile off the format the renderer was handed.
         *
         * [variableBitrate] is not in here because the format cannot answer it -- see [Mp3Vbr] for
         * what it takes to find out -- so the caller establishes it and passes it in.
         */
        @UnstableApi
        fun of(format: Format, variableBitrate: Boolean): AudioProfile? {
            val mime = format.sampleMimeType ?: return null
            val lossless = mime in LOSSLESS
            return AudioProfile(
                label = labelFor(mime, format.containerMimeType),
                lossless = lossless,
                bitrateKbps = if (lossless) null else kilobits(format),
                variableBitrate = !lossless && variableBitrate,
                sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE && it > 0 },
                bits = bitsOf(format.pcmEncoding),
            )
        }

        @UnstableApi
        private val LOSSLESS = setOf(
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC,
            MimeTypes.AUDIO_RAW,
            MimeTypes.AUDIO_WAV,
        )

        /**
         * What the file would be called, which is not always what it contains.
         *
         * AAC and ALAC are different codecs with nothing in common but the box they travel in, and
         * that box is what the file is named after: both are .m4a, and a line reading "AAC" for a
         * file called .m4a would be telling the truth in a language nobody asked a question in.
         */
        @UnstableApi
        internal fun labelFor(sampleMime: String, containerMime: String?): String = when {
            sampleMime == MimeTypes.AUDIO_MPEG -> "MP3"
            sampleMime == MimeTypes.AUDIO_FLAC -> "FLAC"
            sampleMime == MimeTypes.AUDIO_VORBIS -> "OGG"
            sampleMime == MimeTypes.AUDIO_OPUS -> "Opus"
            sampleMime == MimeTypes.AUDIO_RAW || sampleMime == MimeTypes.AUDIO_WAV -> "WAV"
            sampleMime == MimeTypes.AUDIO_ALAC -> "M4A"
            containerMime == MimeTypes.AUDIO_MP4 -> "M4A"
            sampleMime == MimeTypes.AUDIO_AAC -> "AAC"
            else -> sampleMime.substringAfter('/').uppercase()
        }

        /** Average for choice, peak only as a stand-in, and rounded rather than truncated. */
        private fun kilobits(format: Format): Int? {
            val bps = format.averageBitrate.takeIf { it != Format.NO_VALUE && it > 0 }
                ?: format.peakBitrate.takeIf { it != Format.NO_VALUE && it > 0 }
                ?: return null
            return (bps + 500) / 1000
        }

        /**
         * The depth the decoder is producing, which for a lossless format is the file's own.
         *
         * For a lossy one it is the depth the decoder happens to be decoding *to*, which is a
         * property of the decoder rather than of the recording -- which is why [summary] never
         * quotes it for a lossy file.
         */
        internal fun bitsOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            // Float is what the sink converts to, never what a file is written in.
            else -> null
        }

        /**
         * Hertz as kilohertz, to one decimal, and without a trailing zero.
         *
         * 44100 is "44.1 kHz" and 48000 is "48 kHz" rather than "48.0 kHz", because the point of
         * the line is to be read at a glance and a zero that never varies is not worth a glance.
         */
        private fun kiloHertz(hz: Int): String {
            val tenths = (hz + 50) / 100
            val whole = tenths / 10
            val rest = tenths % 10
            return if (rest == 0) "$whole kHz" else "$whole.$rest kHz"
        }
    }
}

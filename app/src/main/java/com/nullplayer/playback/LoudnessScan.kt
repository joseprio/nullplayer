package com.nullplayer.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.nullplayer.data.VaultMediaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "LoudnessScan"

/**
 * Measures one vault file, by decoding it.
 *
 * There is no shortcut available. A tag can be read from a header, but loudness is a property of
 * the audio itself, so every sample has to be produced before it can be counted — which is the
 * same work playing the track does, minus the waiting and minus the sink.
 *
 * The file is read through [VaultMediaSource], the same decrypting reader the tag pass uses, so a
 * scan never puts a plaintext copy of a track anywhere: the bytes are deciphered into the
 * decoder's own input buffers and go no further. It is a suspending function that checks for
 * cancellation on every buffer, because the caller is a background sweep that has to stop the
 * moment it is told to.
 */
internal object LoudnessScan {

    /** Null when the file cannot be decoded, which the caller treats as "do not ask again". */
    suspend fun measure(file: File): Loudness? {
        val source = VaultMediaSource(file)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(source)

            val audio = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).mime()?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(audio)
            val mime = format.mime() ?: return null
            extractor.selectTrack(audio)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            decode(extractor, codec)
        } catch (t: Throwable) {
            // A file that will not decode is not an error worth surfacing: it is already in the
            // vault, it may well still play on a device with a codec this one lacks, and the only
            // consequence here is that it goes unnormalised.
            Log.i(TAG, "Could not measure ${file.name}", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }

    /**
     * The decode loop: fill an input buffer from the extractor, take whatever comes out, repeat.
     *
     * The meter cannot be built until the decoder has announced its output format, because the
     * sample rate decides the filter coefficients and the channel count decides how many delay
     * lines there are. That announcement always arrives before the first buffer, but the fallback
     * is here anyway rather than a null check that could silently measure nothing.
     */
    private suspend fun decode(extractor: MediaExtractor, codec: MediaCodec): Loudness? {
        val info = MediaCodec.BufferInfo()
        var meter: LoudnessMeter? = null
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var scratch = FloatArray(0)
        var inputDone = false

        while (true) {
            currentCoroutineContext().ensureActive()

            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                encoding = codec.outputFormat.pcmEncoding()
                if (meter == null) meter = meterFor(codec.outputFormat)
                continue
            }
            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                if (meter == null) meter = meterFor(codec.outputFormat)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                scratch = push(meter, buffer, encoding, scratch)
            }
            codec.releaseOutputBuffer(index, /* render = */ false)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return meter?.result()
    }

    private fun meterFor(format: MediaFormat): LoudnessMeter = LoudnessMeter(
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
    )

    /**
     * One decoded buffer into the meter, at a full scale of 1.0 whichever width it arrived in.
     *
     * The scratch array is handed back rather than reallocated per buffer: a four-minute track is
     * a few thousand of these, and the meter only ever reads as far as [count].
     */
    private fun push(
        meter: LoudnessMeter,
        buffer: ByteBuffer,
        encoding: Int,
        scratch: FloatArray,
    ): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val samples = buffer.asFloatBuffer()
            val count = samples.remaining()
            val target = grow(scratch, count)
            samples.get(target, 0, count)
            meter.feed(target, count)
            target
        } else {
            // Anything a decoder does not announce as float is 16-bit: that is the default the
            // platform documents, and the only other width it emits without being asked.
            val samples = buffer.asShortBuffer()
            val count = samples.remaining()
            val target = grow(scratch, count)
            for (index in 0 until count) target[index] = samples.get() / FULL_SCALE_16
            meter.feed(target, count)
            target
        }
    }

    private fun grow(scratch: FloatArray, count: Int): FloatArray =
        if (scratch.size >= count) scratch else FloatArray(count)

    private fun MediaFormat.mime(): String? = getString(MediaFormat.KEY_MIME)

    private fun MediaFormat.pcmEncoding(): Int =
        if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    /** Long enough not to spin on an empty queue, short enough to stay responsive to cancellation. */
    private const val TIMEOUT_US = 10_000L

    /** 16-bit full scale, as a divisor: −32768 maps to −1.0. */
    private const val FULL_SCALE_16 = 32_768f
}

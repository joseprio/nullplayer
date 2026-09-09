package com.nullplayer.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val TAG = "EqualizerProcessor"

/**
 * Temporary: logs what the curve actually costs the audio thread. Flip to false to silence it.
 *
 * The figure that matters is not microseconds but the ratio — time spent against the length of the
 * audio produced. Well under one percent is a processor with the whole buffer to spare; anything
 * approaching the buffer's own duration is a processor that will miss the moment the phone is busy.
 */
private const val MEASURE = false

/**
 * The equalizer itself: a biquad cascade sitting in ExoPlayer's audio path.
 *
 * This replaces `android.media.audiofx.Equalizer`, which could only ever offer the fixed handful
 * of bands the device happened to ship — usually five — and could not express an AutoEQ profile
 * at all. Running the maths here instead means the curve is the same on every phone, an arbitrary
 * number of sections is possible, and there is no audio session to lose when the sink is rebuilt.
 *
 * Everything the audio thread reads lives behind a single [Config] reference that is swapped
 * whole. A half-applied set of coefficients is a much worse artefact than the one-buffer delay it
 * takes for a new curve to land.
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {

    /** An immutable snapshot: the curve, plus the cascade already built for the current format. */
    private class Config(
        val eq: ParametricEq,
        val cascade: BiquadCascade?,
        val preamp: Double,
    )

    @Volatile
    private var pending: ParametricEq = ParametricEq()

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var config: Config? = null

    /**
     * The buffer, unpacked into a primitive array and worked on there.
     *
     * Reading a sample at a time through `ShortBuffer.get` is a call into a direct buffer's view
     * with a bounds check and an unsafe read behind it, and at 44.1 kHz in stereo that is nearly
     * two million of them a second in each direction. `get(array, offset, length)` is one bulk
     * move, and the loop that follows it is over a plain array, where the JIT can drop the bounds
     * checks and keep the arithmetic in registers.
     *
     * Grown rather than allocated afresh, for the reason the vault's reader keeps its own scratch:
     * this runs on a thread that must not stop to collect garbage.
     */
    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)

    /**
     * One array per channel, holding that channel's samples on their own.
     *
     * The cascade wants to run a whole buffer through one section at a time, and it can only do
     * that over samples that are adjacent. What arrives is interleaved, so the channels are split
     * apart here and put back together afterwards. Two extra passes over the buffer buys ten
     * passes that touch nothing but the audio — a trade that pays as soon as there is more than a
     * section or two, and a profile with ten of them is not a close call.
     */
    private var channelWork: Array<DoubleArray> = emptyArray()

    /** [MEASURE] only: nanoseconds spent, and frames produced, since the last line was logged. */
    private var spentNanos = 0L
    private var measuredFrames = 0L
    private var measuredBuffers = 0

    /**
     * Hands the processor a new curve. Safe from any thread.
     *
     * The cascade is rebuilt on the next configure or the next buffer, whichever comes first, so
     * that the audio thread never allocates in the middle of a run.
     */
    fun set(enabled: Boolean, eq: ParametricEq) {
        this.pending = eq
        this.enabled = enabled
        // Dropped rather than rebuilt here: the audio thread owns the delay lines, and building a
        // replacement from this side would race it.
        config = null
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        // Anything else passes through untouched rather than being refused, which would fail the
        // whole playback rather than just the effect.
        if (!handles(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        config = null
        return inputAudioFormat
    }

    /**
     * Depends on the format alone, never on the settings.
     *
     * Media3 samples this when the pipeline is built and then feeds buffers only to the processors
     * that said yes at that moment. Answering "no" because the equalizer happens to be off right
     * now drops this out of the chain for the rest of the track — and turning the equalizer on, or
     * importing a profile, is something you do *after* the music has started, so the curve would
     * silently do nothing until a seek or a track change rebuilt the sink.
     *
     * So it stays in the chain and copies its input through when there is nothing to apply. A
     * memcpy per buffer is a very cheap price for the setting taking effect when it is changed.
     */
    override fun isActive(): Boolean = handles(inputAudioFormat.encoding)

    /**
     * The two encodings that can actually arrive here, and both are handled.
     *
     * The sink decides between an integer and a floating-point path per track, and puts a
     * converter at the head of whichever it picks: `ToInt16PcmAudioProcessor` on one side,
     * `ToFloatPcmAudioProcessor` on the other. Both run before anything the app adds, so whatever
     * the file was encoded at — 8-bit, 24-bit, 32-bit — it is one of these two by the time it
     * reaches us, and there is no third case worth writing code for.
     */
    private fun handles(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        if (frames <= 0) return

        val channels = inputAudioFormat.channelCount
        val active = configure()
        val bytes = frames * inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(bytes)

        // The equalizer is off, or its curve is flat, which is most of the time. One bulk move,
        // and no typed view of a buffer nothing is going to read sample by sample.
        if (active == null) {
            copyThrough(inputBuffer, output, bytes)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        // The sink hands over native-order bytes; reading them through a typed view avoids
        // reassembling every sample by hand.
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        val started = if (MEASURE) System.nanoTime() else 0L

        // The maths is identical either way — the cascade works in Double and is linear, so it
        // neither knows nor cares what full scale is. Only the width of a sample differs, which is
        // why this is two loops rather than one with a conversion in the middle: a 24-bit track
        // deserves to reach the device as something better than the 16-bit it would be squashed to.
        val cascade = active.cascade
        val preamp = active.preamp
        val samples = frames * channels
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val scratch = floatScratch(samples)
            inputBuffer.asFloatBuffer().get(scratch, 0, samples)
            if (cascade == null) {
                // A curve that came out flat, with a preamp still to apply. Nothing to deinterleave
                // for: one multiply in place is the whole of the work.
                for (index in 0 until samples) scratch[index] = clampFloat(scratch[index] * preamp)
            } else {
                val work = channelWork(channels, frames)
                var at = 0
                for (frame in 0 until frames) {
                    for (channel in 0 until channels) work[channel][frame] = scratch[at++] * preamp
                }
                for (channel in 0 until channels) cascade.process(channel, work[channel], frames)
                at = 0
                for (frame in 0 until frames) {
                    for (channel in 0 until channels) {
                        scratch[at++] = clampFloat(work[channel][frame])
                    }
                }
            }
            output.asFloatBuffer().put(scratch, 0, samples)
        } else {
            val scratch = shortScratch(samples)
            inputBuffer.asShortBuffer().get(scratch, 0, samples)
            if (cascade == null) {
                for (index in 0 until samples) scratch[index] = clamp(scratch[index] * preamp)
            } else {
                val work = channelWork(channels, frames)
                var at = 0
                for (frame in 0 until frames) {
                    for (channel in 0 until channels) work[channel][frame] = scratch[at++] * preamp
                }
                for (channel in 0 until channels) cascade.process(channel, work[channel], frames)
                at = 0
                for (frame in 0 until frames) {
                    for (channel in 0 until channels) {
                        scratch[at++] = clamp(work[channel][frame])
                    }
                }
            }
            output.asShortBuffer().put(scratch, 0, samples)
        }

        // A view does not move the buffer it was taken from, so the position the bulk write would
        // have left behind is set by hand before the flip.
        inputBuffer.position(inputBuffer.limit())
        output.position(bytes)
        output.flip()

        if (MEASURE) measure(System.nanoTime() - started, frames)
    }

    /** Grown to fit and then kept; never handed out, so nothing else can hold a stale reference. */
    private fun floatScratch(samples: Int): FloatArray {
        if (floatScratch.size < samples) floatScratch = FloatArray(samples)
        return floatScratch
    }

    private fun shortScratch(samples: Int): ShortArray {
        if (shortScratch.size < samples) shortScratch = ShortArray(samples)
        return shortScratch
    }

    /** Rebuilt when the channel count changes, grown when a longer buffer than usual turns up. */
    private fun channelWork(channels: Int, frames: Int): Array<DoubleArray> {
        if (channelWork.size != channels || channelWork[0].size < frames) {
            channelWork = Array(channels) { DoubleArray(frames) }
        }
        return channelWork
    }

    /**
     * Averages the cost over a few hundred buffers before saying anything.
     *
     * Per buffer the figure is noise — one that lands during a garbage collection says nothing
     * about the curve. Averaged, and set against the duration of the audio those buffers carried,
     * it is the number this processor should be judged on.
     */
    private fun measure(nanos: Long, frames: Int) {
        spentNanos += nanos
        measuredFrames += frames
        if (++measuredBuffers < MEASURE_EVERY) return

        val sampleRate = inputAudioFormat.sampleRate
        val audioUs = if (sampleRate > 0) measuredFrames * 1_000_000 / sampleRate else 0
        val spentUs = spentNanos / 1_000
        val share = if (audioUs > 0) spentUs * 1000 / audioUs else 0
        Log.i(
            TAG,
            "Curve cost: ${spentUs}us of work for ${audioUs}us of audio " +
                "(${share / 10}.${share % 10}% of realtime) over $measuredBuffers buffers",
        )
        spentNanos = 0L
        measuredFrames = 0L
        measuredBuffers = 0
    }

    override fun onFlush() {
        config?.cascade?.reset()
    }

    override fun onReset() {
        config = null
        channelWork = emptyArray()
        spentNanos = 0L
        measuredFrames = 0L
        measuredBuffers = 0
    }

    /**
     * The cascade for the current format, rebuilt if the curve changed under us.
     *
     * Null means "pass the audio through": the equalizer is off, or its curve is flat. That is a
     * per-buffer decision precisely because [isActive] is not allowed to make it.
     */
    private fun configure(): Config? {
        config?.let { return it }
        if (!enabled || pending.isEmpty || !isActive) return null

        val eq = pending
        val sections = eq.filters
            .map { BiquadCoefficients.of(it, inputAudioFormat.sampleRate) }
            .filter { it != BiquadCoefficients.PASSTHROUGH }

        Log.i(
            TAG,
            "Curve applied: ${sections.size} sections at ${inputAudioFormat.sampleRate} Hz, " +
                "encoding ${inputAudioFormat.encoding}",
        )
        return Config(
            eq = eq,
            cascade = if (sections.isEmpty()) {
                null
            } else {
                BiquadCascade(sections, inputAudioFormat.channelCount)
            },
            preamp = eq.preampLinear,
        ).also { config = it }
    }

    /**
     * Back to 16-bit, saturating rather than wrapping.
     *
     * A resonant section can overshoot even under a correct preamp, on a transient the preamp was
     * not computed for. Wrapping turns that into a full-scale click; saturating turns it into a
     * moment of distortion nobody notices.
     */
    private fun clamp(value: Double): Short {
        val rounded = value.roundToInt()
        return when {
            rounded > Short.MAX_VALUE -> Short.MAX_VALUE
            rounded < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> rounded.toShort()
        }
    }

    /** The same saturation, against the unit full scale that float PCM is expressed in. */
    private fun clampFloat(value: Double): Float = value.coerceIn(-1.0, 1.0).toFloat()

    private companion object {
        /**
         * Roughly five seconds of audio at a typical buffer size: often enough to watch a track
         * change take effect, rare enough that the log is not the thing costing the time.
         */
        const val MEASURE_EVERY = 250
    }
}

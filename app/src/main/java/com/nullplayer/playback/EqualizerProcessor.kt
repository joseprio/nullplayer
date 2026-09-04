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
        val output = replaceOutputBuffer(frames * inputAudioFormat.bytesPerFrame)

        // The sink hands over native-order bytes; reading them through a typed view avoids
        // reassembling every sample by hand.
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        // The maths is identical either way — the cascade works in Double and is linear, so it
        // neither knows nor cares what full scale is. Only the width of a sample differs, which is
        // why this is two loops rather than one with a conversion in the middle: a 24-bit track
        // deserves to reach the device as something better than the 16-bit it would be squashed to.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val input = inputBuffer.asFloatBuffer()
            if (active == null) {
                while (input.hasRemaining()) output.putFloat(input.get())
            } else {
                val cascade = active.cascade
                val preamp = active.preamp
                repeat(frames) {
                    for (channel in 0 until channels) {
                        val sample = input.get() * preamp
                        val processed =
                            if (cascade == null) sample else cascade.process(channel, sample)
                        output.putFloat(clampFloat(processed))
                    }
                }
            }
        } else {
            val input = inputBuffer.asShortBuffer()
            if (active == null) {
                while (input.hasRemaining()) output.putShort(input.get())
            } else {
                val cascade = active.cascade
                val preamp = active.preamp
                repeat(frames) {
                    for (channel in 0 until channels) {
                        val sample = input.get() * preamp
                        val processed =
                            if (cascade == null) sample else cascade.process(channel, sample)
                        output.putShort(clamp(processed))
                    }
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        config?.cascade?.reset()
    }

    override fun onReset() {
        config = null
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
}

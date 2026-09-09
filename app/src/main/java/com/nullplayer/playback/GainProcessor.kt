package com.nullplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One number multiplied into every sample: what volume normalisation actually does.
 *
 * It sits ahead of [EqualizerProcessor] in the chain rather than behind it. The two are both
 * linear, so the order makes no difference to the arithmetic, but it makes a difference to the
 * headroom: a track pulled down here reaches the equalizer with room for the curve's boosts to
 * fit, where the same attenuation applied afterwards would arrive too late to stop them clipping.
 *
 * The gain is never applied as a step. A track change would otherwise land as a click, and a
 * setting toggled mid-song as a jolt, so a change is walked to over [RAMP_SECONDS] — long enough
 * to be inaudible, short enough that the first phrase of a track is already at its proper level.
 */
@UnstableApi
class GainProcessor : BaseAudioProcessor() {

    /** Written from whichever thread the player or the settings happen to be on. */
    @Volatile
    private var target: Double = 1.0

    /** Owned by the audio thread alone: where the ramp has actually reached. */
    private var current: Double = 1.0

    /** How far the ramp moves per frame, recomputed when the format is known. */
    private var step: Double = 1.0

    fun setGain(linear: Double) {
        target = if (linear.isFinite()) linear.coerceIn(MIN_GAIN, MAX_GAIN) else 1.0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        if (!handles(inputAudioFormat.encoding)) return AudioProcessor.AudioFormat.NOT_SET
        step = 1.0 / (inputAudioFormat.sampleRate * RAMP_SECONDS).coerceAtLeast(1.0)
        return inputAudioFormat
    }

    /**
     * Format alone, never the setting — for the same reason [EqualizerProcessor.isActive] does it
     * this way. The pipeline is built once per track, so a processor that answers "no" because
     * normalisation is off right now would stay out of the chain until the next track, and
     * switching it on mid-song would appear to do nothing.
     */
    override fun isActive(): Boolean = handles(inputAudioFormat.encoding)

    /** The two encodings the sink's own converters can hand an app's processor. */
    private fun handles(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        if (frames <= 0) return

        val channels = inputAudioFormat.channelCount
        val bytes = frames * inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(bytes)
        val wanted = target

        // Unity, and already there: multiplying every sample by one is still multiplying every
        // sample. A track with no measurement, or normalisation switched off, sits here for its
        // whole length, so this is the path worth making free rather than merely cheap.
        if (current == wanted && wanted == 1.0) {
            copyThrough(inputBuffer, output, bytes)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val input = inputBuffer.asFloatBuffer()
            repeat(frames) {
                val gain = advance(wanted)
                for (channel in 0 until channels) {
                    output.putFloat(clampFloat(input.get() * gain))
                }
            }
        } else {
            val input = inputBuffer.asShortBuffer()
            repeat(frames) {
                val gain = advance(wanted)
                for (channel in 0 until channels) {
                    output.putShort(clamp(input.get() * gain))
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** One frame of the ramp, and the gain that frame is to be multiplied by. */
    private fun advance(wanted: Double): Double {
        if (current != wanted) {
            val remaining = wanted - current
            current = if (abs(remaining) <= step) wanted else current + step * sign(remaining)
        }
        return current
    }

    private fun sign(value: Double): Double = if (value < 0.0) -1.0 else 1.0

    /**
     * A flush is a seek or a track change, and the audio after it is unrelated to the audio
     * before. There is nothing to ramp away from, so the gain simply starts where it belongs.
     */
    override fun onFlush() {
        current = target
    }

    override fun onReset() {
        current = 1.0
        target = 1.0
    }

    /** Saturating, not wrapping — the same rule, and the same reason, as the equalizer's. */
    private fun clamp(value: Double): Short {
        val rounded = value.roundToInt()
        return when {
            rounded > Short.MAX_VALUE -> Short.MAX_VALUE
            rounded < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> rounded.toShort()
        }
    }

    private fun clampFloat(value: Double): Float = value.coerceIn(-1.0, 1.0).toFloat()

    private companion object {

        /** 40 ms. Below audibility as a slide, above it as a step. */
        const val RAMP_SECONDS = 0.04

        /** −40 dB and +20 dB. Wider than anything [AudioEffects] will ask for, and a guard rail. */
        const val MIN_GAIN = 0.01
        const val MAX_GAIN = 10.0
    }
}

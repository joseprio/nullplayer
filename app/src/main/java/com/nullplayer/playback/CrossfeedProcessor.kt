package com.nullplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Crossfeed: a little of each side, low-passed and a fraction of a millisecond late, in the other.
 *
 * A record is mixed on speakers, where both ears hear both channels — the far one a few decibels
 * quieter below about 700 Hz, shadowed to almost nothing above it, and a couple of hundred
 * microseconds later. Headphones take all of that away, so a hard-panned instrument sits in one
 * ear alone, which is what makes some older recordings tiring to listen to on them. This puts the
 * room back: it is the Bauer stereophonic-to-binaural arrangement that bs2b popularised, at
 * whichever of its three [CrossfeedStrength]s is asked for.
 *
 * The whole effect is one first-order low-pass per channel and three multiplies per sample.
 * Low-passing the fed signal is what supplies both the head shadow and the delay — a first-order
 * section at 700 Hz has a group delay of roughly 230 µs in its passband, which is about the time
 * sound takes to cross a head — and the same low-passed signal is subtracted from its own channel,
 * so that a sound in the middle of the image is not made louder by arriving twice. Only the
 * difference between the sides is touched; a mono record passes through unchanged.
 *
 * Like [GainProcessor], it never switches as a step. The feed is walked between nothing and its
 * full amount over [RAMP_SECONDS] — and between one strength and the next the same way — so the
 * toggle lands as the image narrowing rather than as a click in the bass.
 */
@UnstableApi
class CrossfeedProcessor : BaseAudioProcessor() {

    /** Written from whichever thread the settings happen to be on. */
    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var strength: CrossfeedStrength = CrossfeedStrength.NATURAL

    /** Owned by the audio thread alone: how much feed is actually being applied right now. */
    private var current: Double = 0.0

    /** How far the ramp moves per frame, recomputed when the format is known. */
    private var step: Double = 1.0

    /**
     * The low-pass for the current rate and cutoff: `y = (1 - pole) * in + pole * y`.
     *
     * A one-pole filter can have its coefficient changed under it without a click — the state is
     * the output, and the next sample simply leans on it a little more or less — so a change of
     * strength moves the cutoff at once and leaves only the level to the ramp.
     */
    private var pole: Double = 0.0
    private var keep: Double = 1.0
    private var poleFor: CrossfeedStrength? = null

    /** The delay lines: one low-pass state per side. */
    private var lowLeft: Double = 0.0
    private var lowRight: Double = 0.0

    /** See [EqualizerProcessor] for why the buffer is unpacked into an array before the loop. */
    private var floatScratch = FloatArray(0)
    private var shortScratch = ShortArray(0)

    /** Hands the processor its setting. Safe from any thread. */
    fun set(enabled: Boolean, strength: CrossfeedStrength) {
        this.strength = strength
        this.enabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        if (!handles(inputAudioFormat)) return AudioProcessor.AudioFormat.NOT_SET
        step = FULL_FEED / (inputAudioFormat.sampleRate * RAMP_SECONDS).coerceAtLeast(1.0)
        poleFor = null
        lowLeft = 0.0
        lowRight = 0.0
        return inputAudioFormat
    }

    /**
     * Stereo alone. Crossfeed is a statement about two channels and a head between them; mono has
     * nothing to feed across, and a surround mix has already decided where things sit.
     *
     * `isActive` is deliberately *not* overridden here, unlike in [EqualizerProcessor]. The
     * inherited answer — whether [onConfigure] accepted the format — already depends on the
     * format alone and never on the setting, so a toggle flipped mid-song still lands. The
     * override those two use reads `inputAudioFormat`, which the base class only updates on
     * flush, so during `configure` it answers for the *previous* track. That is harmless for
     * them, since they take every format the sink offers, but this one refuses mono: after a
     * stereo track it would claim to be active while handing back NOT_SET, and Media3's
     * pipeline treats that combination as a broken processor and fails the whole playback.
     */
    private fun handles(format: AudioProcessor.AudioFormat): Boolean =
        format.channelCount == 2 &&
            (format.encoding == C.ENCODING_PCM_16BIT || format.encoding == C.ENCODING_PCM_FLOAT)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        if (frames <= 0) return

        val bytes = frames * inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(bytes)
        val strength = this.strength
        val wanted = if (enabled) strength.feed else 0.0

        // Off, and fully ramped out: the common case on speakers, and the one worth making free.
        if (current == wanted && wanted == 0.0) {
            copyThrough(inputBuffer, output, bytes)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }

        tune(strength)
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        val samples = frames * 2
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val scratch = floatScratch(samples)
            inputBuffer.asFloatBuffer().get(scratch, 0, samples)
            var at = 0
            repeat(frames) {
                val left = scratch[at].toDouble()
                val right = scratch[at + 1].toDouble()
                val spill = feed(left, right) * advance(wanted)
                scratch[at] = clampFloat(left + spill)
                scratch[at + 1] = clampFloat(right - spill)
                at += 2
            }
            output.asFloatBuffer().put(scratch, 0, samples)
        } else {
            val scratch = shortScratch(samples)
            inputBuffer.asShortBuffer().get(scratch, 0, samples)
            var at = 0
            repeat(frames) {
                val left = scratch[at].toDouble()
                val right = scratch[at + 1].toDouble()
                val spill = feed(left, right) * advance(wanted)
                scratch[at] = clamp(left + spill)
                scratch[at + 1] = clamp(right - spill)
                at += 2
            }
            output.asShortBuffer().put(scratch, 0, samples)
        }

        // A view does not move the buffer it was taken from, so the positions are set by hand.
        inputBuffer.position(inputBuffer.limit())
        output.position(bytes)
        output.flip()
    }

    /** Rebuilds the low-pass when the strength moved its cutoff; a compare per buffer otherwise. */
    private fun tune(strength: CrossfeedStrength) {
        if (poleFor == strength) return
        pole = exp(-2.0 * PI * strength.cutoffHz / inputAudioFormat.sampleRate)
        keep = 1.0 - pole
        poleFor = strength
    }

    /**
     * One frame of the filters, and the low-passed difference between the sides.
     *
     * The delay lines run whether or not the result is used at full strength, so that the ramp
     * fades a settled signal in rather than one whose filters are still charging.
     */
    private fun feed(left: Double, right: Double): Double {
        lowLeft = keep * left + pole * lowLeft
        lowRight = keep * right + pole * lowRight
        return lowRight - lowLeft
    }

    /** One frame of the ramp, and the feed that frame is to be applied at. */
    private fun advance(wanted: Double): Double {
        if (current != wanted) {
            val remaining = wanted - current
            current = if (abs(remaining) <= step) wanted else current + step * sign(remaining)
        }
        return current
    }

    private fun sign(value: Double): Double = if (value < 0.0) -1.0 else 1.0

    private fun floatScratch(samples: Int): FloatArray {
        if (floatScratch.size < samples) floatScratch = FloatArray(samples)
        return floatScratch
    }

    private fun shortScratch(samples: Int): ShortArray {
        if (shortScratch.size < samples) shortScratch = ShortArray(samples)
        return shortScratch
    }

    /**
     * A flush is a seek or a track change, and the audio after it is unrelated to the audio
     * before: the filters are emptied so the old bass does not leak into the new, and the ramp
     * starts where it belongs.
     */
    override fun onFlush() {
        lowLeft = 0.0
        lowRight = 0.0
        current = if (enabled) strength.feed else 0.0
    }

    override fun onReset() {
        lowLeft = 0.0
        lowRight = 0.0
        current = 0.0
        poleFor = null
        floatScratch = FloatArray(0)
        shortScratch = ShortArray(0)
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

        /** 40 ms, as the gain ramp: below audibility as a slide, above it as a step. */
        const val RAMP_SECONDS = 0.04

        /**
         * The most feed there can be — a level of 0 dB, both ears hearing the same — and so the
         * longest swing the ramp is ever asked to make. The ramp is over the feed itself rather
         * than a 0-to-1 blend, so that a change of strength slides too; sizing the step against
         * this makes the longest possible swing take [RAMP_SECONDS], and every real one less.
         */
        const val FULL_FEED = 0.5
    }
}

package com.nullplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What crossfeed promises, checked against what comes out.
 *
 * The effect is small by design, which is exactly why it is tested by its numbers: a sign the
 * wrong way round, or a feed applied to the mid instead of the side, would not sound broken. It
 * would sound like a slightly different record.
 */
@UnstableApi
class CrossfeedProcessorTest {

    private val rate = 44_100

    private fun processor(
        enabled: Boolean,
        strength: CrossfeedStrength = CrossfeedStrength.NATURAL,
    ): CrossfeedProcessor = CrossfeedProcessor().also {
        it.set(enabled, strength)
        it.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        // A flush is what the sink does before the first buffer, and it is also what puts the
        // ramp straight at its target, so the steady state can be measured without waiting.
        it.flush()
    }

    /** Runs interleaved stereo through the processor and hands back what it produced. */
    private fun run(processor: CrossfeedProcessor, frames: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(frames.size * 4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(frames)
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = FloatArray(output.remaining() / 4)
        output.asFloatBuffer().get(result)
        return result
    }

    private fun tone(hz: Double, seconds: Double, left: Double, right: Double): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2).also { out ->
            for (frame in 0 until frames) {
                val sample = sin(2.0 * PI * hz * frame / rate)
                out[frame * 2] = (sample * left).toFloat()
                out[frame * 2 + 1] = (sample * right).toFloat()
            }
        }
    }

    /** RMS of one channel over the last [seconds] of the signal, after the filters have settled. */
    private fun level(frames: FloatArray, channel: Int, seconds: Double = 0.5): Double {
        val count = (rate * seconds).toInt()
        val from = frames.size / 2 - count
        var sum = 0.0
        for (frame in from until frames.size / 2) {
            val v = frames[frame * 2 + channel].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    private fun decibels(ratio: Double): Double = 20.0 * kotlin.math.log10(ratio)

    @Test
    fun `off, it is a copy`() {
        val input = tone(440.0, 0.1, left = 0.8, right = -0.3)
        assertArrayEquals(input, run(processor(enabled = false), input), 0f)
    }

    @Test
    fun `a mono signal passes through untouched`() {
        val input = tone(100.0, 1.0, left = 0.5, right = 0.5)
        val output = run(processor(enabled = true), input)
        for (index in input.indices) {
            assertEquals(input[index].toDouble(), output[index].toDouble(), 1e-6)
        }
    }

    @Test
    fun `a bass note hard on the left reaches the right, the promised few decibels down`() {
        for (strength in CrossfeedStrength.entries) {
            val output = run(processor(true, strength), tone(60.0, 1.0, left = 0.5, right = 0.0))
            val left = level(output, 0)
            val right = level(output, 1)
            assertTrue("$strength: right side should now carry the note", right > 0.08)
            assertEquals(strength.name, strength.levelDb, decibels(left / right), 0.2)
        }
    }

    @Test
    fun `the strengths are in order, and the stored ordinal falls back gently`() {
        val feeds = CrossfeedStrength.entries.map { it.feed }
        assertEquals(feeds.sortedDescending(), feeds)
        assertEquals(CrossfeedStrength.MEIER, CrossfeedStrength.ofOrdinal(2))
        assertEquals(CrossfeedStrength.NATURAL, CrossfeedStrength.ofOrdinal(-1))
        assertEquals(CrossfeedStrength.NATURAL, CrossfeedStrength.ofOrdinal(99))
    }

    @Test
    fun `changing strength mid-song slides rather than steps`() {
        val processor = processor(enabled = true, strength = CrossfeedStrength.NATURAL)
        // One continuous tone, handed over in two pieces. The cut falls thirty cycles and a
        // quarter in: at the crest of the wave, where the low-pass holds the most and a step in
        // the feed would show up largest.
        val whole = tone(60.0, 1.0, left = 0.5, right = 0.0)
        val cut = 2 * (rate * 0.5 + rate / 240.0).toInt()
        val before = run(processor, whole.copyOfRange(0, cut))
        processor.set(true, CrossfeedStrength.MEIER)
        val after = run(processor, whole.copyOfRange(cut, whole.size))

        // Across the seam the right channel moves by no more than a 60 Hz wave can in a sample.
        // A step would be the difference between the two feeds on the crest: about 0.06.
        val seam = abs(after[1] - before[before.size - 1])
        assertTrue("right channel jumped $seam at the change of strength", seam < 0.005)

        val left = level(after, 0, seconds = 0.2)
        val right = level(after, 1, seconds = 0.2)
        assertEquals(CrossfeedStrength.MEIER.levelDb, decibels(left / right), 0.2)
    }

    @Test
    fun `a treble note hard on the left stays there`() {
        val output = run(processor(enabled = true), tone(8_000.0, 1.0, left = 0.5, right = 0.0))
        val left = level(output, 0)
        val right = level(output, 1)
        // Two octaves and a half above the cutoff, a first-order slope has taken most of it away.
        assertTrue("right side should stay nearly silent, was $right", decibels(left / right) > 18.0)
        assertEquals(0.5 / sqrt(2.0), left, 0.01)
    }

    @Test
    fun `the hard-panned bass keeps its energy where it was rather than getting louder`() {
        val input = tone(60.0, 1.0, left = 0.5, right = 0.0)
        val output = run(processor(enabled = true), input)
        val before = level(input, 0)
        val after = level(output, 0) + level(output, 1)
        // Near plus far adds up to what the near ear had alone: the note has been shared, not
        // duplicated. The slack is the low-pass's phase, which at 60 Hz is nearly nothing.
        assertEquals(before, after, before * 0.02)
    }

    @Test
    fun `switching on mid-buffer ramps in rather than stepping`() {
        val processor = processor(enabled = false)
        processor.set(true, CrossfeedStrength.NATURAL)
        val output = run(processor, tone(60.0, 0.2, left = 0.5, right = 0.0))
        // The right channel starts at nothing and is only fully fed once the ramp has finished.
        val early = abs(output[2 * 10 + 1])
        val late = level(output, 1, seconds = 0.05)
        assertTrue("first frames should carry almost nothing, was $early", early < 0.01)
        assertTrue("later frames should carry the feed, was $late", late > 0.1)
    }

    @Test
    fun `mono and surround are left alone`() {
        // Configure then flush, as the sink does: the format only lands on the flush.
        fun active(channels: Int, encoding: Int): Boolean = CrossfeedProcessor().let {
            it.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            it.flush()
            it.isActive
        }
        assertFalse(active(1, C.ENCODING_PCM_FLOAT))
        assertFalse(active(6, C.ENCODING_PCM_16BIT))
        assertTrue(active(2, C.ENCODING_PCM_16BIT))
        assertTrue(active(2, C.ENCODING_PCM_FLOAT))
    }

    /**
     * The sink reconfigures the same processor for every track, and asks `isActive` in the
     * middle of that, before the flush that lands the new format. A mono track after a stereo
     * one has to be turned down at that moment, not one flush later — Media3 fails the whole
     * playback for a processor that says it is active while refusing the format.
     */
    @Test
    fun `a mono track after a stereo one is refused straight away`() {
        val processor = processor(enabled = true)
        val mono = AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(mono))
        assertFalse(processor.isActive)
    }
}

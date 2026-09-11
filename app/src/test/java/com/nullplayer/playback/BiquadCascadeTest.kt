package com.nullplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Random

/**
 * The cascade runs two ways, and the whole point is that they are the same.
 *
 * [BiquadCascade.process] over a whole buffer was written to replace the one that takes a sample
 * at a time, because holding a section's coefficients and delay values in registers for a pass
 * costs a third less on the small cores a backgrounded player is scheduled on. The rearrangement
 * is only worth having if the samples that come out are bit for bit what came out before, and an
 * error in it would not sound broken -- it would sound like a slightly different curve, which is
 * exactly the kind of wrong nobody notices.
 *
 * So the two are run side by side. The per-sample version is the reference: it is the arithmetic
 * as the Audio EQ Cookbook writes it, one section at a time, and it was in the app for months.
 */
class BiquadCascadeTest {

    private val rate = 44_100

    /** A profile with the shape of a real one: several peaks, a shelf, boosts and cuts. */
    private fun sections(): List<BiquadCoefficients> = listOf(
        EqFilter(FilterType.LOW_SHELF, frequencyHz = 80.0, gainDb = 3.0, q = 0.7),
        EqFilter(FilterType.PEAKING, frequencyHz = 220.0, gainDb = -1.5, q = 1.2),
        EqFilter(FilterType.PEAKING, frequencyHz = 1_400.0, gainDb = -1.2, q = 1.8),
        EqFilter(FilterType.PEAKING, frequencyHz = 4_200.0, gainDb = 2.0, q = 3.0),
        EqFilter(FilterType.HIGH_SHELF, frequencyHz = 10_500.0, gainDb = -2.0, q = 2.0),
    ).map { BiquadCoefficients.of(it, rate) }

    @Test
    fun `a whole buffer through one section at a time matches one sample through every section`() {
        val bulk = BiquadCascade(sections(), channelCount = 2)
        val reference = BiquadCascade(sections(), channelCount = 2)
        val noise = Random(20260910)

        // Several buffers in a row, because what the rearrangement has to get right is the delay
        // line picked up and put back around each pass. A single buffer would not notice.
        repeat(6) {
            val frames = 512
            val left = DoubleArray(frames) { noise.nextGaussian() * 0.3 }
            val right = DoubleArray(frames) { noise.nextGaussian() * 0.3 }

            // Interleaved, the way the sink hands audio over: frame by frame, channel by channel.
            val expectedLeft = DoubleArray(frames)
            val expectedRight = DoubleArray(frames)
            for (frame in 0 until frames) {
                expectedLeft[frame] = reference.process(0, left[frame])
                expectedRight[frame] = reference.process(1, right[frame])
            }

            val actualLeft = left.copyOf()
            val actualRight = right.copyOf()
            bulk.process(0, actualLeft, frames)
            bulk.process(1, actualRight, frames)

            for (frame in 0 until frames) {
                assertEquals(expectedLeft[frame], actualLeft[frame], 1e-12)
                assertEquals(expectedRight[frame], actualRight[frame], 1e-12)
            }
        }
    }

    @Test
    fun `the two agree on a single section and on one channel`() {
        val one = listOf(BiquadCoefficients.of(EqFilter(FilterType.PEAKING, 1_000.0, 6.0, 1.0), rate))
        val bulk = BiquadCascade(one, channelCount = 1)
        val reference = BiquadCascade(one, channelCount = 1)

        val input = DoubleArray(256) { if (it == 0) 1.0 else 0.0 }
        val expected = DoubleArray(input.size) { reference.process(0, input[it]) }
        val actual = input.copyOf()
        bulk.process(0, actual, actual.size)

        for (index in input.indices) assertEquals(expected[index], actual[index], 1e-12)
    }

    @Test
    fun `a partial buffer only touches the frames it was given`() {
        val cascade = BiquadCascade(sections(), channelCount = 1)
        val samples = DoubleArray(64) { 0.5 }
        val untouched = samples.copyOf()

        cascade.process(0, samples, count = 16)

        for (index in 0 until 16) assertNotEquals(untouched[index], samples[index], 1e-12)
        for (index in 16 until samples.size) assertEquals(untouched[index], samples[index], 0.0)
    }

    @Test
    fun `channels do not leak into one another`() {
        val cascade = BiquadCascade(sections(), channelCount = 2)

        // Only the left channel is ever fed anything, so the right one must stay silent. A shared
        // delay line -- the mistake the flattened layout could most easily make -- would ring here.
        val left = DoubleArray(128) { if (it == 0) 1.0 else 0.0 }
        val right = DoubleArray(128)
        cascade.process(0, left, left.size)
        cascade.process(1, right, right.size)

        for (sample in right) assertEquals(0.0, sample, 0.0)
    }

    @Test
    fun `reset clears the delay lines`() {
        val cascade = BiquadCascade(sections(), channelCount = 1)
        val fresh = BiquadCascade(sections(), channelCount = 1)

        // Ring it, then reset: what follows must match what a cascade that has seen nothing does.
        cascade.process(0, DoubleArray(64) { 1.0 }, 64)
        cascade.reset()

        val after = DoubleArray(64) { if (it == 0) 1.0 else 0.0 }
        val expected = after.copyOf()
        cascade.process(0, after, after.size)
        fresh.process(0, expected, expected.size)

        for (index in after.indices) assertEquals(expected[index], after[index], 1e-12)
    }
}

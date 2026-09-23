package com.nullplayer.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * What a scan came out with: how loud a track is, and how much room it has left.
 *
 * Both numbers are needed to normalise safely. The loudness says which way a track has to move and
 * by how much; the peak says how far it can be moved up before its loudest sample runs out of full
 * scale.
 */
data class Loudness(
    /** Integrated loudness, LUFS. */
    val lufs: Double,
    /** The loudest sample in the file, against a full scale of 1.0. */
    val peak: Double,
) {
    companion object {
        /**
         * R128's absolute gate, and the floor this reports.
         *
         * A track measuring here is silence as far as the standard is concerned. It is stored as a
         * measurement rather than left blank so a silent file is not queued for analysis forever.
         */
        const val SILENCE_LUFS = -70.0
    }
}

/**
 * Integrated loudness to ITU-R BS.1770 / EBU R128, measured as the samples go past.
 *
 * Loudness rather than RMS, because RMS gets the answer wrong in the one way that matters here: it
 * counts a bass-heavy track as far louder than it sounds and a bright, thin one as quieter, so a
 * library levelled by RMS still needs the volume knob between tracks — which is the complaint
 * normalisation exists to answer. The K-weighting below is what buys agreement with the ear: a
 * shelf that discounts everything under a couple of kilohertz and a high-pass that throws away the
 * rumble beneath hearing, both applied before any energy is counted.
 *
 * Fed by [TrackScan] one decoded buffer at a time, so a long track never has to be held in
 * memory at once. Not thread-safe, and not meant to be: one scan, one meter, one thread.
 */
class LoudnessMeter(sampleRate: Int, channelCount: Int) {

    private val channels = channelCount.coerceAtLeast(1)

    /** One cascade across all channels: the weighting is per-channel, before the energies are summed. */
    private val weighting = BiquadCascade(kWeighting(sampleRate), channels)

    /**
     * 100 ms. A gating block is four of these, which is what gives the blocks the 75% overlap the
     * standard asks for: every new quarter closes a block with the three before it.
     */
    private val hopFrames = (sampleRate / 10).coerceAtLeast(1)

    /** Sum of weighted squares in each quarter, used as a ring. */
    private val quarters = DoubleArray(BLOCK_QUARTERS)
    private var quarter = 0
    private var framesInQuarter = 0
    private var quartersSeen = 0

    /** Mean square per gating block. The gates in [result] are a filter over this list, twice. */
    private val blocks = ArrayList<Double>()

    private var peak = 0.0

    /** Which channel the next sample belongs to; a buffer need not end on a frame boundary. */
    private var channel = 0

    /** Interleaved samples at a full scale of 1.0, however they were encoded on the way in. */
    fun feed(samples: FloatArray, count: Int) {
        for (index in 0 until count) {
            val sample = samples[index].toDouble()

            // Peak is taken before the weighting, because it is a fact about the file rather than
            // about how loud the file sounds: what it decides is how far the signal can be pushed.
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude

            val weighted = weighting.process(channel, sample)
            quarters[quarter] += weighted * weighted

            channel++
            if (channel == channels) {
                channel = 0
                framesInQuarter++
                if (framesInQuarter == hopFrames) closeQuarter()
            }
        }
    }

    /**
     * The integrated loudness, after both gates.
     *
     * The gating is why a quiet passage does not drag a loud track's measurement down: the
     * absolute gate throws away silence, and the relative gate throws away whatever sits more than
     * 10 LU below what is left — so the number describes the track as it is listened to rather
     * than as an average taken over its own pauses.
     */
    fun result(): Loudness {
        val sounded = blocks.filter { loudnessOf(it) > Loudness.SILENCE_LUFS }
        if (sounded.isEmpty()) return Loudness(Loudness.SILENCE_LUFS, peak)

        val relativeGate = loudnessOf(sounded.average()) - RELATIVE_GATE_LU
        val gated = sounded.filter { loudnessOf(it) > relativeGate }
        if (gated.isEmpty()) return Loudness(Loudness.SILENCE_LUFS, peak)

        return Loudness(
            lufs = loudnessOf(gated.average()).coerceAtLeast(Loudness.SILENCE_LUFS),
            peak = peak,
        )
    }

    /**
     * Closes the quarter just filled, and with it the block that ends on it.
     *
     * Nothing is recorded until four quarters exist, because a block is 400 ms by definition and a
     * partial one measured as though it were whole would read as a quiet moment that was never
     * there.
     */
    private fun closeQuarter() {
        quartersSeen++
        if (quartersSeen >= BLOCK_QUARTERS) {
            var sum = 0.0
            for (value in quarters) sum += value
            // Divided by frames rather than by samples: the channels are summed, not averaged,
            // which is what the standard's per-channel weights of 1.0 amount to for stereo.
            blocks += sum / (hopFrames.toDouble() * BLOCK_QUARTERS)
        }
        quarter = (quarter + 1) % BLOCK_QUARTERS
        quarters[quarter] = 0.0
        framesInQuarter = 0
    }

    private fun loudnessOf(meanSquare: Double): Double =
        if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY
        else BLOCK_OFFSET_DB + 10.0 * log10(meanSquare)

    private companion object {

        /** 400 ms of window, in 100 ms quarters. */
        const val BLOCK_QUARTERS = 4

        /** The standard's own constant, which puts a 1 kHz sine at −3.01 dBFS at −3.01 LUFS. */
        const val BLOCK_OFFSET_DB = -0.691

        /** How far below the ungated mean a block has to sit before it is dropped. */
        const val RELATIVE_GATE_LU = 10.0

        /**
         * K-weighting: the shelf, then the high-pass.
         *
         * BS.1770 publishes these as coefficients at 48 kHz, which is no use for the 44.1 kHz most
         * of a music library is in, so both are designed here from the parameters those
         * coefficients came from. At 48 kHz the two agree with the published values to eleven
         * significant figures, which is what says the design is the right one.
         *
         * Neither goes through [BiquadCoefficients.of] despite the shapes having those names. The
         * shelf especially is not the Audio EQ Cookbook shelf: the standard's has a different
         * denominator, and building it the cookbook way gives a visibly different curve and so a
         * measurement that is not R128's. That builder exists to reproduce what an AutoEQ config
         * means, and this exists to reproduce what the standard means; they are different jobs
         * that happen to share a word.
         */
        fun kWeighting(sampleRate: Int): List<BiquadCoefficients> = listOf(
            shelf(SHELF_HZ, SHELF_GAIN_DB, SHELF_Q, sampleRate),
            highPass(HIGH_PASS_HZ, HIGH_PASS_Q, sampleRate),
        )

        /** The head-shadow shelf: what a head does to sound arriving at it from in front. */
        fun shelf(
            frequencyHz: Double,
            gainDb: Double,
            q: Double,
            sampleRate: Int,
        ): BiquadCoefficients {
            if (sampleRate <= 0 || frequencyHz >= sampleRate / 2.0) {
                return BiquadCoefficients.PASSTHROUGH
            }
            val k = tan(PI * frequencyHz / sampleRate)
            val high = 10.0.pow(gainDb / 20.0)
            val band = high.pow(SHELF_SLOPE)
            val d = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = (high + band * k / q + k * k) / d,
                b1 = 2.0 * (k * k - high) / d,
                b2 = (high - band * k / q + k * k) / d,
                a1 = 2.0 * (k * k - 1.0) / d,
                a2 = (1.0 - k / q + k * k) / d,
            )
        }

        /** The RLB high-pass: the bottom octave, which is felt rather than heard as loudness. */
        fun highPass(frequencyHz: Double, q: Double, sampleRate: Int): BiquadCoefficients {
            if (sampleRate <= 0 || frequencyHz >= sampleRate / 2.0) {
                return BiquadCoefficients.PASSTHROUGH
            }
            val k = tan(PI * frequencyHz / sampleRate)
            val d = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / d,
                a2 = (1.0 - k / q + k * k) / d,
            )
        }

        const val SHELF_HZ = 1681.974450955533
        const val SHELF_GAIN_DB = 3.999843853973347
        const val SHELF_Q = 0.7071752369554196

        /** Not a round number anywhere in this filter, and none of them are ours to round. */
        const val SHELF_SLOPE = 0.4996667741545416

        const val HIGH_PASS_HZ = 38.13547087602444
        const val HIGH_PASS_Q = 0.5003270373238773
    }
}

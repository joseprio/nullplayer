package com.nullplayer.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order section, normalised so `a0` is already divided out.
 *
 * These are the Audio EQ Cookbook forms, which is what AutoEQ, EqualizerAPO and every other
 * consumer of a `Filter N: ON PK Fc ... Gain ... Q ...` line assume — a config written against
 * them only reproduces its published curve if it is realised with the same maths.
 */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {

        /** Passes the signal through untouched; used where a filter cannot be realised. */
        val PASSTHROUGH = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Coefficients for [filter] at [sampleRate].
         *
         * A centre frequency at or above Nyquist has no meaning at this rate — a 16 kHz shelf on
         * 22.05 kHz material, say — so it degrades to a passthrough rather than producing the
         * garbage coefficients the formulae would otherwise hand back.
         */
        fun of(filter: EqFilter, sampleRate: Int): BiquadCoefficients {
            val nyquist = sampleRate / 2.0
            if (sampleRate <= 0 || filter.frequencyHz <= 0.0 || filter.frequencyHz >= nyquist) {
                return PASSTHROUGH
            }
            // Not just an exact zero. A profile can carry sections of a hundredth of a decibel —
            // AutoEQ produces them where its target and the measurement happen to agree — and each
            // one is a fifth of the audio thread's inner loop spent on something no ear will ever
            // resolve. The threshold is two orders of magnitude below the smallest level change
            // anyone reports hearing, so nothing audible is being traded for it.
            if (abs(filter.gainDb) < MIN_AUDIBLE_GAIN_DB) return PASSTHROUGH

            val q = filter.q.coerceAtLeast(MIN_Q)
            val a = 10.0.pow(filter.gainDb / 40.0)
            val w0 = 2.0 * PI * filter.frequencyHz / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)

            return when (filter.type) {
                FilterType.PEAKING -> normalise(
                    b0 = 1.0 + alpha * a,
                    b1 = -2.0 * cosW0,
                    b2 = 1.0 - alpha * a,
                    a0 = 1.0 + alpha / a,
                    a1 = -2.0 * cosW0,
                    a2 = 1.0 - alpha / a,
                )

                FilterType.LOW_SHELF -> {
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    normalise(
                        b0 = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha),
                        b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW0),
                        b2 = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha),
                        a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha,
                        a1 = -2.0 * ((a - 1) + (a + 1) * cosW0),
                        a2 = (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha,
                    )
                }

                FilterType.HIGH_SHELF -> {
                    val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
                    normalise(
                        b0 = a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha),
                        b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW0),
                        b2 = a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha),
                        a0 = (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha,
                        a1 = 2.0 * ((a - 1) - (a + 1) * cosW0),
                        a2 = (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha,
                    )
                }
            }
        }

        private fun normalise(
            b0: Double,
            b1: Double,
            b2: Double,
            a0: Double,
            a1: Double,
            a2: Double,
        ): BiquadCoefficients {
            if (a0 == 0.0 || !a0.isFinite()) return PASSTHROUGH
            val result = BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            val sane = result.b0.isFinite() && result.b1.isFinite() && result.b2.isFinite() &&
                result.a1.isFinite() && result.a2.isFinite()
            return if (sane) result else PASSTHROUGH
        }

        /** Below this a section rings hard enough to be an oscillator rather than a filter. */
        /** Below this, a section is doing nothing worth the arithmetic. */
        private const val MIN_AUDIBLE_GAIN_DB = 0.05

        private const val MIN_Q = 0.05
    }
}

/**
 * A cascade of second-order sections, with one set of delay lines per channel.
 *
 * Transposed Direct Form II: it needs two state values per section per channel rather than four,
 * and it is the form that behaves best in floating point when a section has a high Q and a low
 * centre frequency — which is exactly what the bass filters in an AutoEQ profile look like.
 *
 * This is built once on the audio thread and then only fed samples; rebuilding it for a changed
 * setting is the caller's job, because swapping coefficients under a running delay line is what
 * makes a filter click.
 */
class BiquadCascade(
    sections: List<BiquadCoefficients>,
    private val channelCount: Int,
) {
    private val sectionCount = sections.size

    /**
     * The coefficients, flattened to `b0, b1, b2, a1, a2` per section.
     *
     * Held as bare doubles rather than as the [BiquadCoefficients] they were built from because
     * of where this is read: once per section per channel per sample, which at a ten-section
     * profile in stereo is around ten million reads a second. A list of objects costs an interface
     * call and a pointer chase for each of them, to fetch five fields that could have been lying
     * end to end in one array. The maths is unchanged; only the fetching is.
     */
    private val coefficients = DoubleArray(sectionCount * COEFFICIENTS)

    // [section][channel], flattened: the inner loop walks channels, so they sit adjacent.
    private val z1 = DoubleArray(sectionCount * channelCount)
    private val z2 = DoubleArray(sectionCount * channelCount)

    init {
        sections.forEachIndexed { index, section ->
            val at = index * COEFFICIENTS
            coefficients[at] = section.b0
            coefficients[at + 1] = section.b1
            coefficients[at + 2] = section.b2
            coefficients[at + 3] = section.a1
            coefficients[at + 4] = section.a2
        }
    }

    val isEmpty: Boolean get() = sectionCount == 0

    /** Clears the delay lines. Used when playback jumps, so the old audio does not ring on. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /**
     * Runs [count] samples of one channel through every section, in place.
     *
     * The same arithmetic as [process], turned inside out. Per sample, that one has to fetch five
     * coefficients and two delay values from arrays and put two back, because the next sample it
     * sees belongs to a different section. This one runs a whole buffer through one section before
     * moving to the next, so those seven values are read into locals once and stay in registers
     * for the entire pass — what crosses memory is the audio, and only the audio.
     *
     * Applying a section to a whole buffer and then the next to its output is exactly a cascade:
     * each section is a causal filter carrying its own state, and that state is picked up and put
     * back around the pass, so a buffer boundary is invisible to it.
     */
    fun process(channel: Int, samples: DoubleArray, count: Int) {
        var at = 0
        var slot = channel
        repeat(sectionCount) {
            val b0 = coefficients[at]
            val b1 = coefficients[at + 1]
            val b2 = coefficients[at + 2]
            val a1 = coefficients[at + 3]
            val a2 = coefficients[at + 4]
            var s1 = z1[slot]
            var s2 = z2[slot]
            for (index in 0 until count) {
                val sample = samples[index]
                val out = b0 * sample + s1
                s1 = b1 * sample - a1 * out + s2
                s2 = b2 * sample - a2 * out
                samples[index] = out
            }
            z1[slot] = s1
            z2[slot] = s2
            at += COEFFICIENTS
            slot += channelCount
        }
    }

    /** Runs one sample of one channel through every section, in order. */
    fun process(channel: Int, sample: Double): Double {
        var value = sample
        var at = 0
        // Both walks are strides rather than multiplications, for the same reason the coefficients
        // are flat: this is the innermost loop in the app.
        var slot = channel
        repeat(sectionCount) {
            val out = coefficients[at] * value + z1[slot]
            z1[slot] = coefficients[at + 1] * value - coefficients[at + 3] * out + z2[slot]
            z2[slot] = coefficients[at + 2] * value - coefficients[at + 4] * out
            value = out
            at += COEFFICIENTS
            slot += channelCount
        }
        return value
    }

    private companion object {
        /** `b0, b1, b2, a1, a2`: `a0` is already divided out by [BiquadCoefficients]. */
        const val COEFFICIENTS = 5
    }
}

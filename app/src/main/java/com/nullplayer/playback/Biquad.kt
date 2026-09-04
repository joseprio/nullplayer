package com.nullplayer.playback

import kotlin.math.PI
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
            if (filter.gainDb == 0.0) return PASSTHROUGH

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
    private val sections: List<BiquadCoefficients>,
    private val channelCount: Int,
) {
    // [section][channel], flattened: the inner loop walks channels, so they sit adjacent.
    private val z1 = DoubleArray(sections.size * channelCount)
    private val z2 = DoubleArray(sections.size * channelCount)

    val isEmpty: Boolean get() = sections.isEmpty()

    /** Clears the delay lines. Used when playback jumps, so the old audio does not ring on. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** Runs one sample of one channel through every section, in order. */
    fun process(channel: Int, sample: Double): Double {
        var value = sample
        for (index in sections.indices) {
            val section = sections[index]
            val slot = index * channelCount + channel
            val out = section.b0 * value + z1[slot]
            z1[slot] = section.b1 * value - section.a1 * out + z2[slot]
            z2[slot] = section.b2 * value - section.a2 * out
            value = out
        }
        return value
    }
}

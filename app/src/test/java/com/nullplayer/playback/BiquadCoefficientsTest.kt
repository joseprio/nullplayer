package com.nullplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the coefficients actually do to a signal, rather than what they look like.
 *
 * Checking the five numbers against hand-worked ones would test the transcription and nothing
 * else. The claim worth holding the code to is the one an AutoEQ profile makes: a peaking filter
 * asked for +6 dB at 1 kHz should pass 1 kHz through six decibels louder. That is a property of
 * the transfer function, so the transfer function is what gets evaluated.
 */
class BiquadCoefficientsTest {

    private val rate = 44_100

    /** |H(e^jw)| for the section, at [freq]. */
    private fun gainAt(section: BiquadCoefficients, freq: Double): Double {
        val w = 2.0 * PI * freq / rate
        // z^-1 = cos w - j sin w, and z^-2 the same at twice the angle.
        val numeratorRe = section.b0 + section.b1 * cos(w) + section.b2 * cos(2 * w)
        val numeratorIm = -(section.b1 * sin(w) + section.b2 * sin(2 * w))
        val denominatorRe = 1.0 + section.a1 * cos(w) + section.a2 * cos(2 * w)
        val denominatorIm = -(section.a1 * sin(w) + section.a2 * sin(2 * w))
        val numerator = numeratorRe * numeratorRe + numeratorIm * numeratorIm
        val denominator = denominatorRe * denominatorRe + denominatorIm * denominatorIm
        return sqrt(numerator / denominator)
    }

    private fun decibels(gain: Double): Double = 20.0 * kotlin.math.log10(gain)

    @Test
    fun `a peaking filter gives its asked-for gain at its centre`() {
        for (gainDb in listOf(-9.0, -3.5, 1.0, 6.0, 12.0)) {
            val section = BiquadCoefficients.of(
                EqFilter(FilterType.PEAKING, frequencyHz = 1_000.0, gainDb = gainDb, q = 1.0),
                rate,
            )
            assertEquals(gainDb, decibels(gainAt(section, 1_000.0)), 1e-9)
        }
    }

    @Test
    fun `a peaking filter leaves the rest of the spectrum alone`() {
        val section = BiquadCoefficients.of(
            EqFilter(FilterType.PEAKING, frequencyHz = 1_000.0, gainDb = 9.0, q = 4.0),
            rate,
        )
        // Two decades either side of a Q of 4 is well outside the bell.
        assertEquals(0.0, decibels(gainAt(section, 20.0)), 0.05)
        assertEquals(0.0, decibels(gainAt(section, 18_000.0)), 0.05)
    }

    @Test
    fun `a low shelf lifts the bottom and leaves the top`() {
        val section = BiquadCoefficients.of(
            EqFilter(FilterType.LOW_SHELF, frequencyHz = 200.0, gainDb = 6.0, q = 0.7),
            rate,
        )
        assertEquals(6.0, decibels(gainAt(section, 1.0)), 0.01)
        assertEquals(0.0, decibels(gainAt(section, 15_000.0)), 0.05)
    }

    @Test
    fun `a high shelf lifts the top and leaves the bottom`() {
        val section = BiquadCoefficients.of(
            EqFilter(FilterType.HIGH_SHELF, frequencyHz = 8_000.0, gainDb = -4.0, q = 0.7),
            rate,
        )
        assertEquals(-4.0, decibels(gainAt(section, 22_000.0)), 0.05)
        assertEquals(0.0, decibels(gainAt(section, 40.0)), 0.05)
    }

    @Test
    fun `a gain too small to hear is not worth a section`() {
        // The threshold exists because an AutoEQ profile can carry bands of a hundredth of a
        // decibel, and each one kept is a fifth of the audio thread's inner loop spent on nothing.
        assertEquals(
            BiquadCoefficients.PASSTHROUGH,
            BiquadCoefficients.of(EqFilter(FilterType.PEAKING, 1_000.0, 0.0, 1.0), rate),
        )
        assertEquals(
            BiquadCoefficients.PASSTHROUGH,
            BiquadCoefficients.of(EqFilter(FilterType.PEAKING, 1_000.0, 0.02, 1.0), rate),
        )
        assertEquals(
            BiquadCoefficients.PASSTHROUGH,
            BiquadCoefficients.of(EqFilter(FilterType.PEAKING, 1_000.0, -0.04, 1.0), rate),
        )
        assertNotEquals(
            BiquadCoefficients.PASSTHROUGH,
            BiquadCoefficients.of(EqFilter(FilterType.PEAKING, 1_000.0, 0.2, 1.0), rate),
        )
    }

    @Test
    fun `a band above Nyquist is dropped rather than realised as noise`() {
        // A 16 kHz shelf on 22.05 kHz material: the formulae would hand back garbage.
        val section = BiquadCoefficients.of(
            EqFilter(FilterType.HIGH_SHELF, frequencyHz = 16_000.0, gainDb = 4.0, q = 0.7),
            sampleRate = 22_050,
        )
        assertEquals(BiquadCoefficients.PASSTHROUGH, section)
    }

    @Test
    fun `passthrough passes through`() {
        assertEquals(1.0, gainAt(BiquadCoefficients.PASSTHROUGH, 100.0), 1e-12)
        assertEquals(1.0, gainAt(BiquadCoefficients.PASSTHROUGH, 10_000.0), 1e-12)
    }

    @Test
    fun `the preamp leaves room for the tallest boost`() {
        val filters = listOf(
            EqFilter(FilterType.PEAKING, 80.0, 2.5, 0.7),
            EqFilter(FilterType.PEAKING, 4_200.0, 6.5, 3.0),
            EqFilter(FilterType.PEAKING, 8_100.0, -3.0, 3.0),
        )
        assertEquals(-6.5, ParametricEq.headroomFor(filters), 1e-12)

        // Nothing to make room for when nothing is boosted.
        assertEquals(0.0, ParametricEq.headroomFor(filters.filter { it.gainDb < 0 }), 1e-12)
        assertEquals(0.0, ParametricEq.headroomFor(emptyList()), 1e-12)
    }

    @Test
    fun `the preamp is a plain multiplier`() {
        assertEquals(1.0, ParametricEq(preampDb = 0.0).preampLinear, 1e-12)
        assertEquals(0.5, ParametricEq(preampDb = -6.020599913).preampLinear, 1e-9)
        assertEquals(10.0.pow(-0.15), ParametricEq(preampDb = -3.0).preampLinear, 1e-12)
    }
}

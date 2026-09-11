package com.nullplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader for the ParametricEQ text AutoEQ publishes.
 *
 * Everything the equalizer does rests on this: a curve read wrong is a curve applied wrong, and
 * silently -- the wrong filter still filters. The dialect in the wild is looser than the one
 * AutoEQ emits, because people paste from forum posts and from other tools, so the cases here are
 * mostly about the forms that arrive rather than the form that is documented.
 */
class AutoEqParserTest {

    /** The shape AutoEQ itself emits. */
    private val canonical = """
        Preamp: -6.5 dB
        Filter 1: ON PK Fc 105 Hz Gain 6.5 dB Q 0.70
        Filter 2: ON PK Fc 1400 Hz Gain -1.2 dB Q 1.80
        Filter 3: ON LSC Fc 105 Hz Gain 2.0 dB Q 0.70
        Filter 4: ON HSC Fc 10000 Hz Gain -3.0 dB Q 0.70
    """.trimIndent()

    @Test
    fun `a canonical profile reads back whole`() {
        val eq = AutoEqParser.parse(canonical).getOrThrow()

        assertEquals(-6.5, eq.preampDb, 1e-12)
        assertEquals(4, eq.filters.size)

        assertEquals(FilterType.PEAKING, eq.filters[0].type)
        assertEquals(105.0, eq.filters[0].frequencyHz, 1e-12)
        assertEquals(6.5, eq.filters[0].gainDb, 1e-12)
        assertEquals(0.70, eq.filters[0].q, 1e-12)

        assertEquals(FilterType.LOW_SHELF, eq.filters[2].type)
        assertEquals(FilterType.HIGH_SHELF, eq.filters[3].type)
        assertEquals(-3.0, eq.filters[3].gainDb, 1e-12)
    }

    @Test
    fun `spacing, case and the dB suffix are all optional`() {
        val loose = """
            preamp:-3
            filter 1:on pk fc 1000hz gain 4 q 1
            FILTER 2: ON PK FC 2000 HZ GAIN -2.5 DB Q 2.5
        """.trimIndent()
        val eq = AutoEqParser.parse(loose).getOrThrow()

        assertEquals(-3.0, eq.preampDb, 1e-12)
        assertEquals(2, eq.filters.size)
        assertEquals(1_000.0, eq.filters[0].frequencyHz, 1e-12)
        assertEquals(4.0, eq.filters[0].gainDb, 1e-12)
        assertEquals(-2.5, eq.filters[1].gainDb, 1e-12)
    }

    @Test
    fun `a line it does not understand is skipped rather than fatal`() {
        val withJunk = """
            # exported from somewhere
            Preamp: -2.0 dB
            Filter 1: ON PK Fc 500 Hz Gain 3.0 dB Q 1.0
            Filter 2: OFF PK Fc 900 Hz Gain 9.0 dB Q 1.0
            Filter 3: ON XYZ Fc 900 Hz Gain 1.0 dB Q 1.0
            some trailing note
        """.trimIndent()
        val eq = AutoEqParser.parse(withJunk).getOrThrow()

        // The disabled band and the unknown type are both dropped; the good one survives.
        assertEquals(1, eq.filters.size)
        assertEquals(500.0, eq.filters[0].frequencyHz, 1e-12)
    }

    @Test
    fun `a preamp on its own is a legitimate profile`() {
        // It is how a flat attenuation is applied.
        val eq = AutoEqParser.parse("Preamp: -4.0 dB").getOrThrow()
        assertEquals(-4.0, eq.preampDb, 1e-12)
        assertTrue(eq.filters.isEmpty())
    }

    @Test
    fun `filters with no preamp get one computed for them`() {
        // AutoEQ boosts more often than it cuts, and a profile with a tall boost clips on loud
        // material unless the whole signal is pulled down first.
        val eq = AutoEqParser.parse(
            """
            Filter 1: ON PK Fc 100 Hz Gain 7.0 dB Q 0.7
            Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.0
            """.trimIndent()
        ).getOrThrow()

        assertEquals(-7.0, eq.preampDb, 1e-12)
    }

    @Test
    fun `nothing to read is a failure with a reason`() {
        assertTrue(AutoEqParser.parse("").isFailure)
        assertTrue(AutoEqParser.parse("   \n  \n").isFailure)
        assertTrue(AutoEqParser.parse("nothing here resembles a filter").isFailure)
    }

    @Test
    fun `an empty curve is empty and a real one is not`() {
        assertTrue(ParametricEq().isEmpty)
        assertTrue(AutoEqParser.parse(canonical).getOrThrow().isEmpty.not())
    }
}

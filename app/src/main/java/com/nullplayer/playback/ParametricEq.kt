package com.nullplayer.playback

import kotlin.math.pow

/** The filter shapes an AutoEQ ParametricEQ export actually uses. */
enum class FilterType(val token: String) {
    PEAKING("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),
}

/** One band of a parametric curve. */
data class EqFilter(
    val type: FilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * A parametric curve: a preamp and the sections that follow it.
 *
 * The preamp is not decoration. AutoEQ profiles boost far more often than they cut, and a profile
 * with +9 dB in the bass will clip on loud material unless the whole signal is pulled down first
 * by at least as much as the curve's highest peak — which is what the published `Preamp:` line is
 * for, and why a curve typed in by hand gets one computed for it.
 */
data class ParametricEq(
    val preampDb: Double = 0.0,
    val filters: List<EqFilter> = emptyList(),
) {
    val isEmpty: Boolean get() = filters.isEmpty() && preampDb == 0.0

    /** The preamp as a plain multiplier. */
    val preampLinear: Double get() = 10.0.pow(preampDb / 20.0)

    companion object {

        /**
         * A preamp that leaves headroom for the tallest boost in [filters].
         *
         * Summing the individual gains would be the honest worst case for filters that overlap,
         * but it is far too pessimistic for a normal curve and would leave everything sounding
         * quiet; the tallest single band is the usual convention and is what AutoEQ's own
         * `Preamp:` line approximates.
         */
        fun headroomFor(filters: List<EqFilter>): Double {
            val peak = filters.maxOfOrNull { it.gainDb } ?: 0.0
            return if (peak > 0.0) -peak else 0.0
        }
    }
}

/**
 * Reader for the ParametricEQ text AutoEQ publishes, and that EqualizerAPO and friends consume.
 *
 * The dialect in the wild is looser than the one AutoEQ emits — people paste from forum posts and
 * from other tools — so this is deliberately forgiving: it takes any line order, any spacing,
 * `dB` present or absent, `Fc`/`Gain`/`Q` in any case, and simply skips anything it does not
 * recognise rather than refusing the whole config over one stray line. What it will not do is
 * silently accept a paste that yielded nothing usable; that comes back as a failure so the screen
 * can say so.
 *
 * ```
 * Preamp: -6.8 dB
 * Filter 1: ON PK Fc 105 Hz Gain 4.7 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
 * ```
 */
object AutoEqParser {

    // Scanned over the whole text rather than anchored to whole lines. A config that arrives with
    // its line breaks eaten — pasted out of a PDF, a chat client, or a wrapped forum post — is
    // still perfectly readable, and refusing it over lost whitespace would be the parser being
    // fussy about presentation rather than content.
    private val PREAMP = Regex(
        """preamp\s*:\s*(-?[\d.]+)\s*db""",
        RegexOption.IGNORE_CASE,
    )

    private val FILTER = Regex(
        """filter\s*\d*\s*:\s*(on|off)\s+(\w+)\s+fc\s+(-?[\d.]+)\s*hz""" +
            """(?:\s+gain\s+(-?[\d.]+)\s*db)?""" +
            """(?:\s+q\s+(-?[\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Filters with no `Q` in the line — shelves, mostly — get the cookbook's default. */
    private const val DEFAULT_Q = 0.7071

    /**
     * Parses [text], or explains why it could not.
     *
     * A config of nothing but a preamp is legitimate — it is how you apply a flat attenuation —
     * so the failure case is "no preamp and no filters", not "no filters".
     */
    fun parse(text: String): Result<ParametricEq> {
        if (text.isBlank()) return Result.failure(EqParseException("Nothing to read."))

        var disabled = 0
        var unknownType = 0
        val filters = mutableListOf<EqFilter>()

        val preampMatch = PREAMP.find(text)
        val preamp = preampMatch?.groupValues?.get(1)?.toDoubleOrNull()
        val sawPreamp = preamp != null

        for (match in FILTER.findAll(text)) {
            if (!match.groupValues[1].equals("ON", ignoreCase = true)) {
                disabled++
                continue
            }

            val type = FilterType.entries
                .firstOrNull { it.token.equals(match.groupValues[2], ignoreCase = true) }
            if (type == null) {
                unknownType++
                continue
            }

            val frequency = match.groupValues[3].toDoubleOrNull() ?: continue
            val gain = match.groupValues[4].toDoubleOrNull() ?: 0.0
            val q = match.groupValues[5].toDoubleOrNull()?.takeIf { it > 0.0 } ?: DEFAULT_Q

            filters += EqFilter(type = type, frequencyHz = frequency, gainDb = gain, q = q)
        }

        if (filters.isEmpty() && !sawPreamp) {
            return Result.failure(EqParseException(complaint(disabled, unknownType)))
        }

        // A hand-typed curve rarely carries a preamp, and without one a boosting profile clips.
        val headroom = preamp ?: ParametricEq.headroomFor(filters)
        return Result.success(ParametricEq(preampDb = headroom, filters = filters))
    }

    private fun complaint(disabled: Int, unknownType: Int): String = when {
        disabled > 0 && unknownType == 0 ->
            "Every filter in that config is switched off."
        unknownType > 0 ->
            "No filters this can play. Only PK, LSC and HSC are supported; " +
                "$unknownType other ${if (unknownType == 1) "type was" else "types were"} skipped."
        else ->
            "No filters found. Expected lines like " +
                "\"Filter 1: ON PK Fc 105 Hz Gain 4.7 dB Q 0.70\"."
    }

    /** Renders a curve back to the same dialect, so what is stored can be shown and re-edited. */
    fun format(eq: ParametricEq): String = buildString {
        append("Preamp: ")
        append(trim(eq.preampDb))
        appendLine(" dB")
        eq.filters.forEachIndexed { index, filter ->
            append("Filter ")
            append(index + 1)
            append(": ON ")
            append(filter.type.token)
            append(" Fc ")
            append(trim(filter.frequencyHz))
            append(" Hz Gain ")
            append(trim(filter.gainDb))
            append(" dB Q ")
            appendLine(trim(filter.q))
        }
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else "$rounded"
    }
}

class EqParseException(message: String) : Exception(message)

package com.nullplayer.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import com.nullplayer.R

/**
 * The app's one typeface: GitHub's Mona Sans, shipped as two variable files under res/font
 * (SIL Open Font License, see THIRD_PARTY_LICENSES.md). The proportional one is modified -- the
 * foot of the tabular 1 is trimmed to its flag -- and so, the licence reserving the name "Mona",
 * is renamed Null Sans, in its file and in its own name table. Each family is declared once per weight
 * the UI asks for, so a `fontWeight` on a Text picks the matching axis position instead of
 * being faked by the renderer.
 */

private val MONA_WEIGHTS = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)

/**
 * Where on the 0–100 optical-size axis the text is set. Android never moves this axis itself,
 * and at its default of 0 the font's own rules swap in the double-storey text-size `a`; from
 * about 38 upward it is the single-storey display `a` the typeface is known for. 100 is the full
 * display cut — slightly tighter spacing, which the small print still wears fine.
 */
private const val MONA_DISPLAY = 100f

// The variation-settings overload is the one way to place a variable font on its axes, and
// Compose still labels it experimental.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, opsz: Float? = null) = FontFamily(
    MONA_WEIGHTS.map { weight ->
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(
                *listOfNotNull(
                    FontVariation.weight(weight.weight),
                    opsz?.let { FontVariation.Setting("opsz", it) },
                ).toTypedArray()
            ),
        )
    }
)

/** Everything that reads as prose: titles, labels, buttons, the lock screen. */
internal val MonaSans = variable(R.font.null_sans, opsz = MONA_DISPLAY)

/** Text that is read character by character rather than as figures: the share URL, the EQ paste box. */
internal val MonaSansMono = variable(R.font.mona_sans_mono)

/**
 * Figures that change in place -- clocks, levels, counts -- set in [MonaSans] with its tabular
 * figures switched on. The sans defaults to proportional digits, so without `tnum` a "1" is
 * narrower than a "0" and a running clock shuffles sideways as it counts. `Text` has no
 * parameter for font features, so this goes in as its `style`.
 */
internal val TABULAR = TextStyle(fontFamily = MonaSans, fontFeatureSettings = "tnum")

/** Material's default scale with every style set in [MonaSans], so a bare `Text` inherits it. */
internal fun Typography.inMonaSans(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = MonaSans),
    displayMedium = displayMedium.copy(fontFamily = MonaSans),
    displaySmall = displaySmall.copy(fontFamily = MonaSans),
    headlineLarge = headlineLarge.copy(fontFamily = MonaSans),
    headlineMedium = headlineMedium.copy(fontFamily = MonaSans),
    headlineSmall = headlineSmall.copy(fontFamily = MonaSans),
    titleLarge = titleLarge.copy(fontFamily = MonaSans),
    titleMedium = titleMedium.copy(fontFamily = MonaSans),
    titleSmall = titleSmall.copy(fontFamily = MonaSans),
    bodyLarge = bodyLarge.copy(fontFamily = MonaSans),
    bodyMedium = bodyMedium.copy(fontFamily = MonaSans),
    bodySmall = bodySmall.copy(fontFamily = MonaSans),
    labelLarge = labelLarge.copy(fontFamily = MonaSans),
    labelMedium = labelMedium.copy(fontFamily = MonaSans),
    labelSmall = labelSmall.copy(fontFamily = MonaSans),
)

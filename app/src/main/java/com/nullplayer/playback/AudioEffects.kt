package com.nullplayer.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The shape of the equalizer: where the faders sit, and how far they move. */
data class EqualizerSpec(
    val centreHz: List<Int>,
    val minMillibel: Int,
    val maxMillibel: Int,
    val presets: List<String>,
) {
    val bandCount: Int get() = centreHz.size
}

/** A named curve for the graphic faders, in millibels per band. */
data class EqPreset(val name: String, val bands: List<Int>)

/**
 * The equalizer, as a curve rather than as a device effect.
 *
 * The engine is [EqualizerProcessor], which lives in ExoPlayer's audio path — so unlike the system
 * effect this replaced, the bands are ours, the same everywhere, and there is no audio session to
 * lose. A singleton for the same reason as [PlaybackGate]: the processor has to live with the
 * player in [PlaybackService], while the screen that draws the faders needs to read it.
 *
 * Two things can define the curve and only one can win. A pasted AutoEQ profile has arbitrary
 * centres and Qs that ten fixed faders cannot represent, so while one is loaded it *is* the
 * curve and the faders step aside rather than showing a shape that is not what is playing.
 */
@UnstableApi
object AudioEffects {

    /**
     * Ten octave-spaced bands, the ISO centres a graphic equalizer has used since long before
     * anything here was written. Q is one octave, so neighbouring faders meet rather than either
     * overlapping into a single wide hump or leaving gaps between them.
     */
    private val CENTRES = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    private const val BAND_Q = 1.41
    private const val LIMIT_MILLIBEL = 1_200

    val PRESETS = listOf(
        EqPreset("Flat", List(10) { 0 }),
        EqPreset("Bass", listOf(600, 500, 350, 150, 0, 0, 0, 0, 0, 0)),
        EqPreset("Treble", listOf(0, 0, 0, 0, 0, 100, 250, 400, 500, 550)),
        EqPreset("Vocal", listOf(-200, -150, 0, 150, 300, 350, 250, 100, 0, -50)),
        EqPreset("Rock", listOf(450, 350, 150, -50, -150, -50, 200, 350, 400, 400)),
        EqPreset("Jazz", listOf(300, 200, 100, 150, -50, -100, 0, 150, 250, 300)),
        EqPreset("Classical", listOf(250, 200, 100, 50, -50, -50, 0, 150, 200, 300)),
        EqPreset("Podcast", listOf(-300, -250, -100, 200, 350, 350, 250, 50, -100, -200)),
    )

    /** Fixed: the bands are ours now, so they exist whether or not anything is playing. */
    val spec = EqualizerSpec(
        centreHz = CENTRES,
        minMillibel = -LIMIT_MILLIBEL,
        maxMillibel = LIMIT_MILLIBEL,
        presets = PRESETS.map { it.name },
    )

    private var processor: EqualizerProcessor? = null

    private val _levels = MutableStateFlow(List(CENTRES.size) { 0 })

    /** The band levels the faders should draw, in millibels. */
    val levels: StateFlow<List<Int>> = _levels.asStateFlow()

    private val _curve = MutableStateFlow(ParametricEq())

    /** Whatever is actually loaded into the engine, whichever source defined it. */
    val curve: StateFlow<ParametricEq> = _curve.asStateFlow()

    /** Called once, by the service that owns the player. */
    @Synchronized
    fun attach(processor: EqualizerProcessor) {
        this.processor = processor
    }

    @Synchronized
    fun release() {
        processor?.set(enabled = false, eq = ParametricEq())
        processor = null
    }

    /**
     * Applies the stored configuration.
     *
     * [autoEq] wins when it parses, because a profile is a complete curve rather than an
     * adjustment to one. Otherwise [preset] is used when it names one, and failing that the
     * [bands] are taken verbatim.
     */
    @Synchronized
    fun apply(enabled: Boolean, autoEq: String, preset: Int, bands: List<Int>) {
        val parsed = autoEq.takeIf { it.isNotBlank() }?.let { AutoEqParser.parse(it).getOrNull() }

        val levels = when {
            parsed != null -> null
            preset in PRESETS.indices -> PRESETS[preset].bands
            else -> List(spec.bandCount) { index ->
                (bands.getOrNull(index) ?: 0).coerceIn(spec.minMillibel, spec.maxMillibel)
            }
        }

        val eq = parsed ?: fromBands(levels.orEmpty())
        _levels.value = levels ?: List(spec.bandCount) { 0 }
        _curve.value = eq
        processor?.set(enabled = enabled, eq = eq)
    }

    /**
     * The graphic faders as a parametric curve: one peaking section per non-zero band.
     *
     * Flat bands are dropped rather than realised as zero-gain sections — a section that does
     * nothing still costs arithmetic on every sample of every channel, and a mostly flat curve is
     * the common case.
     */
    private fun fromBands(bands: List<Int>): ParametricEq {
        val filters = bands.mapIndexedNotNull { index, millibel ->
            if (millibel == 0) {
                null
            } else {
                EqFilter(
                    type = FilterType.PEAKING,
                    frequencyHz = CENTRES[index].toDouble(),
                    gainDb = millibel / 100.0,
                    q = BAND_Q,
                )
            }
        }
        return ParametricEq(preampDb = ParametricEq.headroomFor(filters), filters = filters)
    }
}

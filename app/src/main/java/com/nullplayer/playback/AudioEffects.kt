package com.nullplayer.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

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
 * Everything the app does to the audio on its way out: the curve, and the level.
 *
 * The engine is [EqualizerProcessor], which lives in ExoPlayer's audio path — so unlike the system
 * effect this replaced, the bands are ours, the same everywhere, and there is no audio session to
 * lose. A singleton for the same reason as [PlaybackGate]: the processors have to live with the
 * player in [PlaybackService], while the screen that draws the faders needs to read them.
 *
 * Two things can define the curve and only one can win. A pasted AutoEQ profile has arbitrary
 * centres and Qs that ten fixed faders cannot represent, so while one is loaded it *is* the
 * curve and the faders step aside rather than showing a shape that is not what is playing.
 *
 * Volume normalisation is the other half, and it is deliberately independent of all of that: it
 * needs no curve, it is not affected by one, and switching the equalizer off does not switch it
 * off. What it does need is a measurement — see [Loudness] — which is why the gain it hands
 * [GainProcessor] changes with the track rather than only with the setting.
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

    /**
     * What every track is levelled to, in LUFS.
     *
     * −14 is where the streaming services put their own normalisation, which makes it the level a
     * modern master is already mixed to sit near — so most of a library is nudged down a little,
     * some of it is lifted, and nothing has to move very far. A lower target would be safer still
     * against clipping but would leave the whole app quieter than everything else on the phone.
     */
    const val TARGET_LUFS = -14.0

    /**
     * The most a quiet track may be lifted.
     *
     * Beyond about this, a recording that measures quiet is usually quiet on purpose — a sparse
     * classical or ambient piece — and hauling it up to match a compressed pop master brings its
     * noise floor up with it and flattens the very contrast that was recorded.
     */
    private const val MAX_BOOST_DB = 12.0

    /**
     * How close to full scale a boosted track is allowed to come.
     *
     * A shade under 1.0 rather than at it: the sample peak measured on decode is not quite the
     * true peak of the waveform between samples, and this is the margin that keeps that difference
     * from becoming clipping.
     */
    private const val PEAK_CEILING = 0.98

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

    private var gain: GainProcessor? = null

    private var normalizing: Boolean = false

    /** What the track now playing measured, or null if it has never been measured. */
    private var loudness: Loudness? = null

    private val _levels = MutableStateFlow(List(CENTRES.size) { 0 })

    /** The band levels the faders should draw, in millibels. */
    val levels: StateFlow<List<Int>> = _levels.asStateFlow()

    private val _curve = MutableStateFlow(ParametricEq())

    /** Whatever is actually loaded into the engine, whichever source defined it. */
    val curve: StateFlow<ParametricEq> = _curve.asStateFlow()

    /** Called once, by the service that owns the player. */
    @Synchronized
    fun attach(processor: EqualizerProcessor, gain: GainProcessor) {
        this.processor = processor
        this.gain = gain
    }

    @Synchronized
    fun release() {
        processor?.set(enabled = false, eq = ParametricEq())
        processor = null
        gain?.setGain(1.0)
        gain = null
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

    // -- Volume normalisation ---------------------------------------------------------------

    /** Switches levelling on or off. Takes effect on the track already playing. */
    @Synchronized
    fun setNormalization(enabled: Boolean) {
        normalizing = enabled
        pushGain()
    }

    /**
     * The measurement for whatever is playing now.
     *
     * Null means the gain goes back to unity, which covers both of the ways that happens: no track
     * at all, and a track the sweep has not reached yet. A file whose loudness is unknown plays at
     * the level it was mastered at, which is the only honest thing to do with it.
     */
    @Synchronized
    fun setTrackLoudness(loudness: Loudness?) {
        this.loudness = loudness
        pushGain()
    }

    private fun pushGain() {
        gain?.setGain(gainFor(loudness))
    }

    /**
     * The multiplier for one track: how far it is from the target, capped by its own headroom.
     *
     * Attenuation is never capped — pulling a track down cannot make it clip. A boost is, and by
     * the peak rather than by a limiter: a limiter would let a quiet-measuring track with one loud
     * transient reach the target by squashing the transient, which is a change to the recording
     * rather than to its level. Refusing the last few decibels of gain instead leaves the track
     * slightly under the target and exactly as it was mastered.
     */
    private fun gainFor(loudness: Loudness?): Double {
        if (!normalizing || loudness == null) return 1.0

        val wanted = 10.0.pow((TARGET_LUFS - loudness.lufs).coerceAtMost(MAX_BOOST_DB) / 20.0)
        if (wanted <= 1.0) return wanted

        val ceiling = if (loudness.peak > 0.0) PEAK_CEILING / loudness.peak else wanted
        return minOf(wanted, ceiling.coerceAtLeast(1.0))
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

package com.nullplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.roundToInt

private const val TAG = "SubwooferHaptics"

/** The phone's vibrator, or null where there is none to speak of. */
internal fun Context.vibrator(): Vibrator? {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }
    return vibrator?.takeIf { it.hasVibrator() }
}

/** How much rumble is drawn per hand-over; the next one replaces it before it runs out. */
private const val CHUNK_MS = 200

/**
 * One stretch of rumble as this vibrator plays it: a level per [stepMs], each from 0 (nothing)
 * to 1 (as hard as it goes), turned into one effect.
 */
private interface Rumble {
    val stepMs: Int
    val maxSteps: Int
    fun effect(levels: FloatArray): VibrationEffect
}

/**
 * The plain way, on any vibrator that can vary its strength: a waveform of [STEP_MS] steps, each
 * at its own amplitude. The actuator runs at its own frequency throughout, so what carries the
 * bass is how hard it shakes, and when.
 */
private class Amplitudes : Rumble {
    override val stepMs = STEP_MS
    override val maxSteps = CHUNK_MS / STEP_MS

    override fun effect(levels: FloatArray): VibrationEffect = VibrationEffect.createWaveform(
        LongArray(levels.size) { stepMs.toLong() },
        IntArray(levels.size) { index ->
            val level = levels[index]
            // An amplitude too low to be felt is the same as none, so the scale starts above it.
            if (level <= 0f) 0 else (FLOOR + (255 - FLOOR) * level).roundToInt().coerceIn(1, 255)
        },
        -1,
    )

    private companion object {
        const val STEP_MS = 10
        const val FLOOR = 40
    }
}

/**
 * The deep way, where the vibrator takes envelopes (PWLE, Android 16): the level still sets the
 * strength, and the frequency sits at the lowest the actuator still moves well at, rising towards
 * its resonance only on the hardest hits. Low and slow is what reads as a subwoofer rather than
 * a buzz; the rise is what gives a kick its punch.
 *
 * Every point is kept within what this vibrator says it can take; the amplitudes are shares of
 * the most it can manage at each frequency, so the same numbers suit any actuator.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private class Deep private constructor(
    private val high: Float,
    private val low: Float,
    override val stepMs: Int,
    override val maxSteps: Int,
) : Rumble {

    private fun frequency(level: Float) = low + (high - low) * level * PUNCH

    override fun effect(levels: FloatArray): VibrationEffect {
        val builder = VibrationEffect.WaveformEnvelopeBuilder()
            .setInitialFrequencyHz(frequency(levels[0]))
        for (level in levels) builder.addControlPoint(level, frequency(level), stepMs.toLong())
        return builder.build()
    }

    companion object {
        /** How far towards the resonance the hardest hit climbs. */
        private const val PUNCH = 0.4f

        /** The shortest step wanted, where the vibrator allows it. */
        private const val STEP_MS = 10L

        /** The least a frequency must shake the phone by, as a share of its best, to be used. */
        private const val USABLE = 0.3f

        fun on(vibrator: Vibrator): Deep? {
            if (!vibrator.areEnvelopeEffectsSupported()) return null
            val info = vibrator.envelopeEffectInfo
            // The envelope names frequencies, so without a profile to choose them from it is no use.
            val profile = vibrator.frequencyProfile ?: return null
            // The top is the resonance, where the actuator moves most, or failing a stated one
            // wherever the profile peaks.
            val resonant = vibrator.resonantFrequency
            val high = if (resonant.isFinite() && profile.getOutputAccelerationGs(resonant) > 0f) {
                resonant
            } else {
                val table = profile.frequenciesOutputAcceleration
                (0 until table.size()).maxByOrNull { table.valueAt(it) }
                    ?.let { table.keyAt(it).toFloat() }
                    ?: return null
            }
            // The floor goes down from there for as long as the actuator still has some strength.
            // Walked rather than asked of [VibratorFrequencyProfile.getFrequencyRange], which
            // gives the lowest stretch above the line, not the one around the peak.
            val usable = profile.maxOutputAccelerationGs * USABLE
            var low = high
            while (low - 1f >= profile.minFrequencyHz &&
                profile.getOutputAccelerationGs(low - 1f) >= usable
            ) {
                low -= 1f
            }
            val step = STEP_MS.coerceIn(
                info.minControlPointDurationMillis,
                info.maxControlPointDurationMillis,
            )
            val steps = minOf(
                (CHUNK_MS / step).toInt(),
                info.maxSize,
                (info.maxDurationMillis / step).toInt(),
            )
            if (steps < 2) return null
            Log.i(TAG, "Deep rumble from $low Hz to $high Hz, $steps steps of $step ms")
            return Deep(high = high, low = low, stepMs = step.toInt(), maxSteps = steps)
        }
    }
}

/** The best rumble this vibrator can play, or null where it can only buzz on and off. */
private fun rumbleOn(vibrator: Vibrator): Rumble? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) Deep.on(vibrator)?.let { return it }
    return if (vibrator.hasAmplitudeControl()) Amplitudes() else null
}

/** Whether this phone's vibrator can play the subwoofer. */
object SubwooferSupport {
    fun available(context: Context): Boolean = context.vibrator()?.let { rumbleOn(it) } != null
}

/**
 * The phone as a subwoofer: the vibrator follows the music's sub-bass, below about 100 Hz, as it
 * is heard -- swelling with a bass line, thumping with a kick, still when there is no low end.
 *
 * The measuring is [PeakProcessor]'s, read through [MusicPulse.rumble]. A vibrator cannot be
 * streamed to, so every [RESEND_MS] it is handed the next [CHUNK_MS] of rumble, starting
 * [LEAD_MS] after what is being heard to cover its own start, and each hand-over replaces the
 * last before it runs out. That reads into audio not yet played, which the sink's buffer always
 * holds more than enough of.
 *
 * The level is on a decibel scale, from [FLOOR_DB] to [TOP_DB] of full scale, the way the ear
 * hears loudness. Runs on the player's thread, and only while the player is playing.
 */
class SubwooferHaptics(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private val vibrator = context.vibrator()
    private var rumble: Rumble? = null
    private var running: Job? = null
    private var buzzing = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = follow()
    }

    init {
        player.addListener(listener)
    }

    fun configure(enabled: Boolean) {
        rumble = if (enabled) vibrator?.let { rumbleOn(it) } else null
        MusicPulse.feeling = rumble != null
        follow()
    }

    fun release() {
        player.removeListener(listener)
        rumble = null
        MusicPulse.feeling = false
        follow()
    }

    private fun follow() {
        running?.cancel()
        running = null
        val rumble = rumble
        if (rumble == null || !player.isPlaying) {
            quiet()
            return
        }
        running = scope.launch { run(rumble) }
    }

    private suspend fun run(rumble: Rumble) {
        while (true) {
            val levels = MusicPulse.rumble(LEAD_MS, rumble.stepMs, rumble.maxSteps)
                ?.let { lows -> FloatArray(lows.size) { scale(lows[it]) } }
            if (levels == null || levels.size < 2 || levels.all { it <= 0f }) {
                quiet()
                delay(RESEND_MS.toLong())
                continue
            }
            play(rumble.effect(levels))
            buzzing = true
            // Handed over again before this one ends, even when there was less to hand over.
            val lasts = levels.size * rumble.stepMs
            delay(minOf(RESEND_MS, lasts - OVERLAP_MS).coerceAtLeast(rumble.stepMs).toLong())
        }
    }

    /** The sub-bass envelope, a share of full scale, as a level from 0 to 1 on a decibel scale. */
    private fun scale(low: Float): Float {
        if (low <= 0f) return 0f
        val db = 20f * log10(low)
        return ((db - FLOOR_DB) / (TOP_DB - FLOOR_DB)).coerceIn(0f, 1f)
    }

    private fun quiet() {
        if (!buzzing) return
        buzzing = false
        runCatching { vibrator?.cancel() }
    }

    private fun play(effect: VibrationEffect) {
        val vibrator = vibrator ?: return
        // Media usage, so it answers to the system's media vibration setting like the generator.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                )
            }
        }.onFailure { Log.w(TAG, "Could not vibrate", it) }
    }

    private companion object {
        /** How far after what is heard each stretch starts, for the vibrator to be moving by then. */
        const val LEAD_MS = 20

        /** How often a new stretch is handed over. */
        const val RESEND_MS = 150

        /** How long before a short stretch runs out the next one is handed over. */
        const val OVERLAP_MS = 30

        /** Sub-bass this quiet, against full scale, is not felt at all. */
        const val FLOOR_DB = -40f

        /** Sub-bass this loud shakes the phone as hard as it goes. */
        const val TOP_DB = -6f
    }
}

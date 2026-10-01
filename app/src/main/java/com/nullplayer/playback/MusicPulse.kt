package com.nullplayer.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp

/**
 * The music's sub-bass, moment by moment, lined up with what is actually coming out of the
 * speaker. Its peaks drive the play button's glow; its envelope drives the phone's vibrator in
 * [SubwooferHaptics].
 *
 * [PeakProcessor] hears the audio well before anyone else does: between it and the ear sit the
 * sink's buffer, the track's buffer and, over Bluetooth, the headphones' own. Read the moment it
 * is written, the glow would jump a fifth of a second or more ahead of the music. So each reading
 * is filed by the frame it describes, and [peak] looks up the frames the track says are being
 * heard right now, the same way the player's position is kept honest.
 *
 * The vibrator reads ahead of what is heard, into audio that has been measured but not played
 * yet, so each stretch of rumble can be handed over before it is due; see [rumble].
 *
 * Service and screen share one process, so this is a plain object between them rather than
 * anything carried over the media session.
 */
object MusicPulse {

    /** How much of the past is kept: well past any output latency, Bluetooth included. */
    private const val SLOTS = 8192

    /**
     * How many play buttons are showing the pulse: the player's, the mini player's, or for a
     * moment while one screen gives way to the other, both. A count rather than a flag, so the
     * one leaving cannot switch the measuring off under the one arriving.
     */
    private val watchers = AtomicInteger()

    fun watch() {
        watchers.incrementAndGet()
    }

    fun unwatch() {
        watchers.decrementAndGet()
    }

    /** Whether the subwoofer is on, which wants the measuring whatever is on screen. */
    @Volatile
    var feeling = false

    /** Whether anything is watching; while nothing is, the processor only counts frames. */
    val listening: Boolean
        get() = feeling || watchers.get() > 0

    private val slots = FloatArray(SLOTS)

    /** The sub-bass envelope, filed alongside [slots] hop for hop. */
    private val lows = FloatArray(SLOTS)

    /** How many hops have been filed since the last flush. Published after the slot it covers. */
    @Volatile
    private var written = 0L

    @Volatile
    internal var hopFrames = 0

    @Volatile
    internal var sampleRate = 0

    /**
     * The track the sink is writing to now; handed over by [HapticTracks], its one provider.
     *
     * A new track counts its frames from zero, so the filing starts again with it. Nothing else
     * restarts the count: when the same file comes round again on repeat, or the next one has
     * the same shape, the sink flushes the processors but keeps the track and writes straight on
     * after what it already has, and a count started again there would point at the wrong frames.
     */
    @Volatile
    var track: AudioTrack? = null
        set(value) {
            field = value
            fresh = true
        }

    /** Set with each new track; taken by the processor, on the thread that writes to it. */
    @Volatile
    internal var fresh = false

    private val stamp = AudioTimestamp()



    internal fun restart() {
        written = 0L
    }

    internal fun file(hop: Long, peak: Float, low: Float) {
        val slot = (hop % SLOTS).toInt()
        slots[slot] = peak
        lows[slot] = low
        written = hop + 1
    }

    /**
     * The highest sub-bass peak, from 0 to 1 of full scale, over the last [spanSeconds] of what is
     * being heard now -- a display frame's worth, so no peak falls between two frames -- or null
     * when there is nothing to go on: no track yet, a track just opened, or nobody listening.
     */
    fun peak(spanSeconds: Float): Float? {
        val hop = hopFrames.takeIf { it > 0 } ?: return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        val last = heardFrame()?.div(hop) ?: return null
        val filed = written
        if (last < 0 || last >= filed || last < filed - SLOTS) return null
        val span = (spanSeconds * rate / hop).toLong().coerceIn(1L, MAX_SPAN_HOPS)
        val first = maxOf(last - span + 1, filed - SLOTS, 0L)
        var peak = 0f
        for (index in first..last) peak = maxOf(peak, slots[(index % SLOTS).toInt()])
        return peak
    }

    /**
     * The sub-bass still to come: [count] steps of [stepMs] each, starting [aheadMs] after what is
     * being heard now, each the highest the sub-bass envelope reaches over its step, as a share of
     * full scale. Shorter than asked for when the processor has not got that far yet, and null
     * when there is nothing to go on at all.
     */
    fun rumble(aheadMs: Int, stepMs: Int, count: Int): FloatArray? {
        val hop = hopFrames.takeIf { it > 0 } ?: return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        val heard = heardFrame() ?: return null
        val filed = written
        val hopsPerStep = (stepMs.toLong() * rate / 1000L / hop).coerceAtLeast(1L)
        val start = (heard + aheadMs.toLong() * rate / 1000L) / hop
        if (start < 0 || start < filed - SLOTS) return null
        val available = ((filed - start) / hopsPerStep).toInt().coerceIn(0, count)
        return FloatArray(available) { step ->
            val from = start + step * hopsPerStep
            var low = 0f
            for (index in from until from + hopsPerStep) {
                low = maxOf(low, lows[(index % SLOTS).toInt()])
            }
            low
        }
    }

    /**
     * The frame being heard now, or null without a track to ask.
     *
     * The track's timestamp pins a frame to the moment it left the device, including the delay of
     * whatever it is connected to, and is carried forward from there by the clock; it is capped at
     * the play head so that a paused track does not run on without it.
     */
    private fun heardFrame(): Long? {
        val track = track ?: return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null
        return try {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val heard = synchronized(stamp) {
                if (track.getTimestamp(stamp)) {
                    // The timestamp is on the monotonic clock, System.nanoTime's, which stops
                    // while the phone sleeps. Carried forward on the boot clock instead, the gap
                    // grows by every hour the phone has ever slept, and times the sample rate it
                    // soon overflows: fine on an emulator that never sleeps, lost on a phone.
                    val since = System.nanoTime() - stamp.nanoTime
                    stamp.framePosition + since * rate / 1_000_000_000L
                } else {
                    head
                }
            }
            // A timestamp that lands anywhere odd is not worth trusting over the play head.
            if (heard < 0) head else minOf(heard, head)
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** The most hops one reading spans: a tenth of a second, however long the frame stalled. */
    private const val MAX_SPAN_HOPS = 10L
}

/**
 * Passes the audio through untouched and files the music's sub-bass with [MusicPulse], a thousand
 * times a second. The sub-bass is the audio through two one-pole low-passes at [CUTOFF_HZ]: the
 * kick and the bass line, and little of anything above.
 *
 * Two things are filed per hop. The peak, `max(|x0|, |x1|, ..., |xN|)` of the sub-bass as a share
 * of full scale, is for the glow; hops this short let a display frame take the true peak of
 * exactly the audio it covers, whatever the refresh rate. It is the sub-bass rather than the
 * whole signal because a mastered track peaks within a few decibels of full scale in nearly every
 * frame, and a meter on it barely moves; the low end is where the hits stand out. The envelope,
 * which rises at once and falls over [LOW_RELEASE_SECONDS] -- long enough to ride over the gaps
 * between a 30 Hz wave's crests, short enough to let a kick end -- is for the vibrator.
 *
 * Nothing else is smoothed here. The meter's ballistics -- the decibel scale, the fall and the peak
 * hold -- belong to the screen, which knows how much time passes between its frames.
 *
 * Last in the chain, so it measures what is heard, equalizer and all, and so that the frames it
 * counts are the frames the track is given.
 */
@UnstableApi
class PeakProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var hop = 0

    // The audio thread's alone.
    private var hops = 0L
    private var inHop = 0
    private var peak = 0f
    private var lowPass = 0f
    private var lowRelease = 0f
    private var first = 0f
    private var second = 0f
    private var low = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        hop = (rate / HOPS_PER_SECOND).coerceAtLeast(1)
        lowPass = (1.0 - exp(-2.0 * Math.PI * CUTOFF_HZ / rate)).toFloat()
        lowRelease = exp(-1.0 / (rate * LOW_RELEASE_SECONDS)).toFloat()
        MusicPulse.hopFrames = hop
        MusicPulse.sampleRate = rate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The sink opens a track before it hands over the first buffer meant for it, so this
        // lands exactly on the track's first frame.
        if (MusicPulse.fresh) {
            MusicPulse.fresh = false
            hops = 0L
            inHop = 0
            peak = 0f
            MusicPulse.restart()
        }

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        val count = inputBuffer.remaining() / bytesPerFrame
        if (count <= 0) return
        val bytes = count * bytesPerFrame

        if (MusicPulse.listening) {
            measure(inputBuffer.duplicate().order(ByteOrder.nativeOrder()), count)
        } else {
            // Still counted, and still filed, so that the frames stay lined up with the track's
            // whenever someone starts watching again.
            repeat(count) { step() }
        }

        val output = replaceOutputBuffer(bytes)
        copyThrough(inputBuffer, output, bytes)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun measure(input: ByteBuffer, count: Int) {
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        repeat(count) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += if (float) input.getFloat() else input.getShort() / 32768f
            }
            first += lowPass * (sum / channels - first)
            second += lowPass * (first - second)
            peak = maxOf(peak, abs(second))
            low = maxOf(abs(second), low * lowRelease)
            step()
        }
    }

    /** One frame on; at the end of a hop, the hop's peak is filed. */
    private fun step() {
        if (++inHop < hop) return
        // Float audio can run past full scale; the meter's scale ends there all the same.
        MusicPulse.file(hops++, peak.coerceAtMost(1f), low.coerceAtMost(1f))
        peak = 0f
        inHop = 0
    }

    /** The audio after a flush is unrelated to the audio before, so the filters start at rest. */
    override fun onFlush() {
        peak = 0f
        first = 0f
        second = 0f
        low = 0f
    }

    private companion object {
        const val HOPS_PER_SECOND = 1000

        /** Where the sub-bass ends: the kick and the bass line, and little of anything above. */
        const val CUTOFF_HZ = 100.0

        /** How long the sub-bass envelope takes to fall away. */
        const val LOW_RELEASE_SECONDS = 0.05
    }
}

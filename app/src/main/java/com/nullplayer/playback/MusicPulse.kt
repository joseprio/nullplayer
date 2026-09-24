package com.nullplayer.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How hard the low end of the music is hitting, moment by moment, lined up with what is actually
 * coming out of the speaker. It drives the play button's glow.
 *
 * [PulseProcessor] hears the audio well before anyone else does: between it and the ear sit the
 * sink's buffer, the track's buffer and, over Bluetooth, the headphones' own. Read the moment it
 * is written, the level would pulse a fifth of a second or more ahead of the beat. So each reading
 * is filed by the frame it describes, and [level] looks up the frame the track says is being
 * heard right now, the same way the player's position is kept honest.
 *
 * A track the analysis sweep has already been through brings its [grid] instead: the beats found
 * by listening to the whole file, read against the player's own position. That pulses on every
 * beat, including the ones with no bass under them, and can start to swell just before each one.
 * The live measurement is what is left for a track the sweep has not reached, or one it found no
 * steady beat in.
 *
 * Service and screen share one process, so this is a plain object between them rather than
 * anything carried over the media session.
 */
object MusicPulse {

    /** Where a track's beats fall and how hard each lands; see [Beats]. */
    class BeatGrid(private val timesMs: IntArray, private val strengths: FloatArray) {

        /**
         * The glow at [positionMs]: rising over [LEAD_MS] into each beat so that it peaks on it,
         * then dying away over [DECAY_MS]. A weak beat still pulses, at a little over half, so a
         * quiet bar keeps time rather than going dark.
         */
        fun levelAt(positionMs: Long): Float {
            var next = timesMs.binarySearch(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            if (next < 0) next = -next - 1 else next++
            var level = 0f
            val previous = next - 1
            if (previous >= 0) {
                val since = positionMs - timesMs[previous]
                level = weight(previous) * exp(-since / DECAY_MS)
            }
            if (next < timesMs.size) {
                val until = timesMs[next] - positionMs
                if (until < LEAD_MS) {
                    val rise = 1f - until / LEAD_MS
                    level = maxOf(level, weight(next) * rise * rise)
                }
            }
            return level
        }

        private fun weight(index: Int) = 0.55f + 0.45f * strengths[index]

        private companion object {
            const val LEAD_MS = 60f
            const val DECAY_MS = 160f
        }
    }

    /** The current track's beats, when it has some worth trusting. Set by the service. */
    @Volatile
    var grid: BeatGrid? = null

    /**
     * The player's position, as heard. Set by the service, and only ever called from the main
     * thread, which is the player's own and the one the screen draws on.
     */
    @Volatile
    var clock: (() -> Long)? = null

    /** How much of the past is kept: well past any output latency, Bluetooth included. */
    private const val SLOTS = 2048

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

    /** Whether anything is watching; while nothing is, the processor only counts frames. */
    val listening: Boolean
        get() = watchers.get() > 0

    private val slots = FloatArray(SLOTS)

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

    internal fun file(hop: Long, value: Float) {
        slots[(hop % SLOTS).toInt()] = value
        written = hop + 1
    }

    /**
     * The level at the frame being heard now, from 0 to 1, or null when there is nothing to go
     * on — no track yet, a track just opened, or the pulse not being listened for.
     *
     * The track's timestamp pins a frame to the moment it left the device, including the delay of
     * whatever it is connected to, and is carried forward from there by the clock; it is capped at
     * the play head so that a paused track does not run on without it.
     */
    fun level(): Float? {
        val grid = grid
        val clock = clock
        if (grid != null && clock != null) return grid.levelAt(clock())
        return heard()
    }

    /** The live measurement, at the frame being heard now. */
    private fun heard(): Float? {
        val track = track ?: return null
        val hop = hopFrames.takeIf { it > 0 } ?: return null
        val rate = sampleRate.takeIf { it > 0 } ?: return null

        val frame = try {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val heard = synchronized(stamp) {
                if (track.getTimestamp(stamp)) {
                    val since = SystemClock.elapsedRealtimeNanos() - stamp.nanoTime
                    stamp.framePosition + since * rate / 1_000_000_000L
                } else {
                    head
                }
            }
            minOf(heard, head)
        } catch (_: IllegalStateException) {
            return null
        }

        val index = frame / hop
        val filed = written
        if (index < 0 || index >= filed || index < filed - SLOTS) return null
        return slots[(index % SLOTS).toInt()]
    }
}

/**
 * Passes the audio through untouched and files how hard the music is hitting with [MusicPulse],
 * a hundred times a second.
 *
 * What it measures is the bass and the kick, below about 120 Hz, taken against how loud that band
 * has been on average over the last second or two rather than against full scale: a quiet track
 * pulses as visibly as a loud one, and only what stands out of its surroundings lights the glow,
 * so a steady bass line or a held note settles low instead of holding it full. It rises at once on
 * a hit and falls away over [RELEASE_SECONDS], which is what makes it read as a beat.
 *
 * Not every passage has a low end to follow: an intro on synths and voice alone, a breakdown, an
 * acoustic song. While the bass is too faint to follow — silent, or small beside everything above
 * it — the same measure is taken of everything above it instead, which pulses on the notes rather
 * than the kick but keeps the glow alive.
 *
 * Last in the chain, so it measures what is heard, equalizer and all, and so that the frames it
 * counts are the frames the track is given.
 */
@UnstableApi
class PulseProcessor : BaseAudioProcessor() {

    private var channels = 0
    private var hop = 0
    private var lowPass = 0.0
    private var release = 0.0
    private var fade = 0.0

    // The audio thread's alone.
    private var hops = 0L
    private var inHop = 0
    private var first = 0.0
    private var second = 0.0
    private val bass = Band()
    private val above = Band()

    /** One band's hop of energy, the envelope over it, and its recent average. */
    private inner class Band {
        var energy = 0.0
        var envelope = 0.0
        var average = 0.0

        /** Closes a hop: returns where the envelope stands against the average. */
        fun close(): Double {
            val amplitude = sqrt(energy / hop)
            envelope = maxOf(amplitude, envelope * release)
            average += (amplitude - average) * (1.0 - fade)
            energy = 0.0
            return if (average <= FLOOR) 0.0 else envelope / average
        }

        fun rest() {
            energy = 0.0
            envelope = 0.0
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat):
        AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        hop = (rate / HOPS_PER_SECOND).coerceAtLeast(1)
        val hopSeconds = hop.toDouble() / rate
        lowPass = 1.0 - exp(-2.0 * Math.PI * CUTOFF_HZ / rate)
        release = exp(-hopSeconds / RELEASE_SECONDS)
        fade = exp(-hopSeconds / MEMORY_SECONDS)
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
            bass.energy = 0.0
            above.energy = 0.0
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
            skip(count)
        }

        val output = replaceOutputBuffer(bytes)
        copyThrough(inputBuffer, output, bytes)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun measure(input: ByteBuffer, count: Int) {
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        repeat(count) {
            var sum = 0.0
            for (channel in 0 until channels) {
                sum += if (float) input.getFloat().toDouble() else input.getShort() / 32768.0
            }
            val mono = sum / channels
            // Two one-pole low-passes in a row: a gentle slope, but a steep enough one to leave
            // the kick and the bass and little of anything above them. What they take away is
            // the rest.
            first += lowPass * (mono - first)
            second += lowPass * (first - second)
            bass.energy += second * second
            val rest = mono - second
            above.energy += rest * rest
            step()
        }
    }

    private fun skip(count: Int) {
        repeat(count) { step() }
    }

    /** One frame on; at the end of a hop, the hop is filed. */
    private fun step() {
        if (++inHop < hop) return

        val low = bass.close()
        val high = above.close()
        // Both bands are always kept up, so that the one taken over has an average ready and
        // the glow does not jump when the bass drops out or comes back.
        val followBass = bass.average > FLOOR && bass.average >= above.average * BASS_SHARE
        val standing = if (followBass) low else high
        val level = if (!MusicPulse.listening || standing <= 0.0) {
            0.0
        } else {
            // At the average the glow sits just above rest; a hit [STANDOUT] times the average
            // fills it.
            ((standing - REST) / (STANDOUT - REST)).coerceIn(0.0, 1.0)
        }
        MusicPulse.file(hops++, level.toFloat())
        inHop = 0
    }

    /**
     * The audio after a flush is unrelated to the audio before, so the filters and the envelope
     * start from rest. The count of frames does not: see [MusicPulse.track] for what does.
     */
    override fun onFlush() {
        first = 0.0
        second = 0.0
        bass.rest()
        above.rest()
    }

    override fun onReset() {
        bass.average = 0.0
        above.average = 0.0
    }

    private companion object {
        const val HOPS_PER_SECOND = 100
        const val CUTOFF_HZ = 120.0

        /** How long a hit takes to fall away. */
        const val RELEASE_SECONDS = 0.14

        /** How far back the average that a hit is measured against reaches. */
        const val MEMORY_SECONDS = 1.5

        /** About −50 dBFS: below it the band is silence, and nothing is drawn. */
        const val FLOOR = 0.003

        /**
         * How loud the bass has to be, against everything above it, to be the band followed.
         * About −16 dB: a mix with any real low end clears it easily, an intro with none does not.
         */
        const val BASS_SHARE = 0.15

        /** Where on the scale, as a share of the average, the glow starts to rise. */
        const val REST = 0.8

        /** How far above the average a hit has to stand to fill the glow. */
        const val STANDOUT = 2.5
    }
}

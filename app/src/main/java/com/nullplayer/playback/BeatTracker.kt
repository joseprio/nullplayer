package com.nullplayer.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the beats fall in a track, found by listening to all of it first.
 *
 * [timesMs] and [strengths] run in step: when each beat lands, from the start of the track, and
 * how hard it lands against the track's own strongest, from 0 to 1. [confidence] is how clearly
 * the track has a steady beat at all — the correlation of its onsets with themselves one beat
 * apart — and it is what playback checks before trusting the grid over the live pulse. A track
 * with no beat to speak of comes back empty with a confidence of zero, which is an answer, not a
 * failure: it is stored, so the sweep does not ask again.
 */
class Beats(
    val timesMs: IntArray,
    val strengths: FloatArray,
    val confidence: Float,
    val bpm: Float,
) {

    /** Five bytes a beat: the time as an int, then the strength as an unsigned byte. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(timesMs.size * BYTES_PER_BEAT).order(ByteOrder.LITTLE_ENDIAN)
        for (index in timesMs.indices) {
            buffer.putInt(timesMs[index])
            buffer.put((strengths[index].coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        }
        return buffer.array()
    }

    companion object {
        private const val BYTES_PER_BEAT = 5

        val NONE = Beats(IntArray(0), FloatArray(0), confidence = 0f, bpm = 0f)

        fun decode(bytes: ByteArray, confidence: Float): Beats {
            val count = bytes.size / BYTES_PER_BEAT
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val times = IntArray(count)
            val strengths = FloatArray(count)
            for (index in 0 until count) {
                times[index] = buffer.getInt()
                strengths[index] = (buffer.get().toInt() and 0xFF) / 255f
            }
            return Beats(times, strengths, confidence, bpm = 0f)
        }
    }
}

/**
 * Finds the beats in a whole track, fed as it is decoded.
 *
 * Three steps, the standard ones:
 *
 * 1. **Onsets.** A hundred times a second the last ~46 ms is taken through an FFT, its spectrum
 *    gathered into bands spaced evenly in pitch, and each band compressed like hearing does.
 *    How much the bands rose since the last look — the spectral flux, counting rises only — is
 *    how much of a hit is happening now. Every instrument counts, not just the bass, which is
 *    what lets a track with no kick still have a beat.
 * 2. **Tempo.** The onset curve is correlated with itself at every spacing between 50 and 200
 *    beats a minute. A steady beat lines up with itself one beat apart; a preference centred on
 *    120 settles the halves and doubles that line up nearly as well.
 * 3. **Beats.** The dynamic programme from Ellis's beat tracker: every onset frame is scored as
 *    a possible beat by how strong it is plus the best chain of earlier beats that could lead to
 *    it, with a penalty for any gap that strays from the tempo. The best chain, traced back, is
 *    the beats. Unlike a grid laid over the track, it bends where the playing does.
 *
 * Only the onset curve is kept while feeding — a hundred floats per second of audio — so a long
 * file costs little to hold, and everything else happens once in [result].
 */
class BeatTracker(private val sampleRate: Int, private val channels: Int) {

    private val hop = (sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
    private val size = windowSize(sampleRate)
    private val window = FloatArray(size) { 0.5f - 0.5f * cos(2.0 * PI * it / size).toFloat() }
    private val bandOf = bandsFor(size, sampleRate)

    private val ring = FloatArray(size)
    private var cursor = 0
    private var fed = 0L
    private var sinceHop = 0

    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)
    private val energy = FloatArray(BANDS)
    private val previous = FloatArray(BANDS)
    private var primed = false

    private var onsets = FloatArray(1024)
    private var count = 0

    /** The sample at the end of the first analysed window, from which every frame's time follows. */
    private var firstEnd = -1L

    /** [samples] interleaved, at a full scale of 1.0, as [LoudnessMeter.feed] takes them. */
    fun feed(samples: FloatArray, sampleCount: Int) {
        var index = 0
        while (index + channels <= sampleCount) {
            var sum = 0f
            for (channel in 0 until channels) sum += samples[index + channel]
            index += channels

            ring[cursor] = sum / channels
            cursor = if (cursor + 1 == size) 0 else cursor + 1
            fed++
            if (++sinceHop == hop) {
                sinceHop = 0
                if (fed >= size) analyse()
            }
        }
    }

    private fun analyse() {
        if (firstEnd < 0) firstEnd = fed
        // The ring holds the last [size] samples, oldest at the cursor.
        for (index in 0 until size) {
            real[index] = ring[(cursor + index) % size] * window[index]
            imaginary[index] = 0f
        }
        fft(real, imaginary)

        energy.fill(0f)
        for (bin in 1 until size / 2) {
            val band = bandOf[bin]
            if (band >= 0) energy[band] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
        }

        var flux = 0f
        for (band in 0 until BANDS) {
            val level = ln(1f + COMPRESSION * sqrt(energy[band]))
            // A rise too small to hear is the window's own ripple over a held note, and left in
            // it is a perfectly regular one that the tempo search would take for a beat.
            if (primed) flux += (level - previous[band] - RIPPLE).coerceAtLeast(0f)
            previous[band] = level
        }
        primed = true

        if (count == onsets.size) onsets = onsets.copyOf(count * 2)
        onsets[count++] = flux
    }

    fun result(): Beats {
        if (count < MIN_SECONDS * FRAMES_PER_SECOND) return Beats.NONE
        val raw = onsets.copyOf(count)

        // Take away the slow swell of the track, so a loud chorus does not outvote a quiet verse,
        // and keep only what rises above it.
        val local = movingAverage(raw, FRAMES_PER_SECOND / 2)
        val onset = FloatArray(count) { (raw[it] - local[it]).coerceAtLeast(0f) }
        val deviation = standardDeviation(onset)
        // Onsets that hardly vary are a track with nothing in it that hits: a held note, a drone.
        if (deviation < MIN_DEVIATION) return Beats.NONE
        for (index in onset.indices) onset[index] /= deviation.toFloat()

        val period = tempo(onset) ?: return Beats.NONE
        val beats = track(smooth(onset, period / 32.0), period)
        val kept = trimSilence(beats, raw)
        if (kept.size < MIN_BEATS) return Beats.NONE

        val strengths = strengths(kept, onset)
        val times = IntArray(kept.size) { timeMs(kept[it]) }
        return Beats(times, strengths, confidence(onset, period), (60.0 * FRAMES_PER_SECOND / period).toFloat())
    }

    /** The beat period in frames, fractional, or null for a track with no steady beat in range. */
    private fun tempo(onset: FloatArray): Double? {
        val shortest = FRAMES_PER_SECOND * 60 / MAX_BPM
        val longest = FRAMES_PER_SECOND * 60 / MIN_BPM
        val correlation = autocorrelation(onset, (longest + 1) * 2 + 1)
        val preferred = FRAMES_PER_SECOND * 60.0 / PREFERRED_BPM

        val score = DoubleArray(longest + 2)
        for (lag in shortest..longest + 1) {
            // A beat also lines up with itself two beats on; counting that half as much helps the
            // true period beat a spacing that only lines up once.
            val lined = correlation[lag] + 0.5 * correlation[lag * 2]
            val octaves = ln(lag / preferred) / ln(2.0)
            score[lag] = lined * exp(-0.5 * (octaves / PREFERENCE_OCTAVES).pow(2))
        }
        var best = shortest
        for (lag in shortest..longest) if (score[lag] > score[best]) best = lag
        if (score[best] <= 0.0) return null

        // A parabola through the peak and its neighbours puts the period between whole frames.
        val left = score[best - 1]
        val right = score[best + 1]
        val bend = left - 2 * score[best] + right
        val shift = if (bend < 0) (0.5 * (left - right) / bend).coerceIn(-0.5, 0.5) else 0.0
        return best + shift
    }

    /** The beats, as onset frames, by Ellis's dynamic programme. */
    private fun track(onset: FloatArray, period: Double): IntArray {
        val size = onset.size
        val score = DoubleArray(size)
        val from = IntArray(size) { -1 }
        val earliest = (period * 2).roundToInt()
        val latest = (period / 2).roundToInt().coerceAtLeast(1)

        for (frame in 0 until size) {
            var best = Double.NEGATIVE_INFINITY
            var bestFrom = -1
            for (prior in (frame - earliest).coerceAtLeast(0)..(frame - latest)) {
                val stray = ln((frame - prior) / period)
                val candidate = score[prior] - TIGHTNESS * stray * stray
                if (candidate > best) {
                    best = candidate
                    bestFrom = prior
                }
            }
            // A chain only worth less than nothing is not carried on: the frame starts afresh.
            val carried = bestFrom >= 0 && best > 0
            score[frame] = onset[frame] + if (carried) best else 0.0
            from[frame] = if (carried) bestFrom else -1
        }

        // The chain ends at the best-scoring frame within a beat of the end.
        var end = size - 1
        for (frame in (size - period.roundToInt()).coerceAtLeast(0) until size) {
            if (score[frame] > score[end]) end = frame
        }
        val chain = ArrayList<Int>()
        var frame = end
        while (frame >= 0) {
            chain.add(frame)
            frame = from[frame]
        }
        chain.reverse()
        return chain.toIntArray()
    }

    /**
     * Drops beats from the head and tail of the track where there is next to nothing playing.
     * The programme will happily keep time through a fade-out or the silence before a hidden
     * track, and a glow pulsing to nothing looks broken rather than rhythmic.
     */
    private fun trimSilence(beats: IntArray, raw: FloatArray): IntArray {
        val swell = movingAverage(raw, FRAMES_PER_SECOND)
        val typical = median(swell)
        if (typical <= 0f) return beats
        val floor = typical * SILENCE_SHARE
        var first = 0
        var last = beats.size - 1
        while (first <= last && swell[beats[first]] < floor) first++
        while (last >= first && swell[beats[last]] < floor) last--
        return if (first > last) IntArray(0) else beats.copyOfRange(first, last + 1)
    }

    /** How hard each beat lands: its strongest onset nearby, against the track's strong beats. */
    private fun strengths(beats: IntArray, onset: FloatArray): FloatArray {
        val peaks = FloatArray(beats.size) { index ->
            val at = beats[index]
            var peak = 0f
            for (frame in (at - STRENGTH_REACH).coerceAtLeast(0)..(at + STRENGTH_REACH).coerceAtMost(onset.size - 1)) {
                if (onset[frame] > peak) peak = onset[frame]
            }
            peak
        }
        val sorted = peaks.sortedArray()
        val strong = sorted[((sorted.size - 1) * 0.9).roundToInt()]
        if (strong <= 0f) return FloatArray(beats.size) { 1f }
        return FloatArray(beats.size) { (peaks[it] / strong).coerceIn(0f, 1f) }
    }

    /**
     * How much the onsets look like themselves one beat later, from −1 to 1. A click track sits
     * near 1, a song with a clear beat well above zero, noise and a held tone at about nothing.
     */
    private fun confidence(onset: FloatArray, period: Double): Float {
        val mean = onset.average()
        val lag = period.roundToInt()
        var lined = 0.0
        var total = 0.0
        for (index in onset.indices) {
            val centred = onset[index] - mean
            total += centred * centred
            if (index + lag < onset.size) lined += centred * (onset[index + lag] - mean)
        }
        if (total <= 0.0) return 0f
        return (lined / total * onset.size / (onset.size - lag)).toFloat().coerceIn(-1f, 1f)
    }

    /** Frame to milliseconds: each frame stands for the middle of the window it was taken over. */
    private fun timeMs(frame: Int): Int {
        val centre = firstEnd + frame.toLong() * hop - size / 2
        return (centre * 1000 / sampleRate).toInt().coerceAtLeast(0)
    }

    companion object {
        /** Bumped whenever the analysis changes enough that stored results should be redone. */
        const val VERSION = 1

        const val FRAMES_PER_SECOND = 100
        private const val BANDS = 40
        private const val LOWEST_HZ = 30.0
        private const val HIGHEST_HZ = 8_000.0
        private const val COMPRESSION = 1_000f

        /** The smallest rise in one band's compressed level that counts: about half a decibel. */
        private const val RIPPLE = 0.05f

        /** Below this spread of onsets there is nothing hitting; see [result]. */
        private const val MIN_DEVIATION = 1.0

        /**
         * The range the tempo is looked for in. The top is the one that matters: rock and punk
         * run up to the 160s and want pulsing on every beat, while hi-hats on every eighth of a
         * 96 bpm groove line up at 192 about as well as the beat does. Stopping short of that
         * leaves the hi-hats out of the running; a song genuinely faster than this is found at
         * half its tempo, which still keeps time.
         */
        private const val MIN_BPM = 50
        private const val MAX_BPM = 175
        /** Where the tempo search leans between a tempo and its half, and how hard. */
        private const val PREFERRED_BPM = 120.0
        private const val PREFERENCE_OCTAVES = 0.8

        /** How hard the programme holds to the tempo; librosa's default. */
        private const val TIGHTNESS = 100.0

        private const val MIN_SECONDS = 5
        private const val MIN_BEATS = 8
        private const val SILENCE_SHARE = 0.1f
        private const val STRENGTH_REACH = 3

        /** About 46 ms, rounded up to a power of two for the FFT: 2048 samples at 44.1 kHz. */
        internal fun windowSize(sampleRate: Int): Int {
            var size = 256
            while (size < sampleRate * 0.04) size *= 2
            return size
        }

        /** Which band each FFT bin falls in, bands spaced evenly in pitch; −1 for out of range. */
        private fun bandsFor(size: Int, sampleRate: Int): IntArray {
            val top = minOf(HIGHEST_HZ, sampleRate / 2.0)
            val span = ln(top / LOWEST_HZ)
            return IntArray(size / 2) { bin ->
                val hz = bin.toDouble() * sampleRate / size
                if (hz < LOWEST_HZ || hz >= top) -1
                else (ln(hz / LOWEST_HZ) / span * BANDS).toInt().coerceIn(0, BANDS - 1)
            }
        }

        /** In place, radix 2. [real] and [imaginary] must be the same power-of-two length. */
        internal fun fft(real: FloatArray, imaginary: FloatArray) {
            val n = real.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = real[i]; real[i] = real[j]; real[j] = t
                    t = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val angle = -2.0 * PI / length
                val stepReal = cos(angle).toFloat()
                val stepImaginary = sin(angle).toFloat()
                var start = 0
                while (start < n) {
                    var turnReal = 1f
                    var turnImaginary = 0f
                    for (k in 0 until length / 2) {
                        val a = start + k
                        val b = a + length / 2
                        val bReal = real[b] * turnReal - imaginary[b] * turnImaginary
                        val bImaginary = real[b] * turnImaginary + imaginary[b] * turnReal
                        real[b] = real[a] - bReal
                        imaginary[b] = imaginary[a] - bImaginary
                        real[a] += bReal
                        imaginary[a] += bImaginary
                        val nextReal = turnReal * stepReal - turnImaginary * stepImaginary
                        turnImaginary = turnReal * stepImaginary + turnImaginary * stepReal
                        turnReal = nextReal
                    }
                    start += length
                }
                length = length shl 1
            }
        }

        /** Correlation of [signal] with itself at every lag below [lags], per overlapping frame. */
        private fun autocorrelation(signal: FloatArray, lags: Int): DoubleArray {
            val mean = signal.average()
            return DoubleArray(lags) { lag ->
                if (lag >= signal.size) return@DoubleArray 0.0
                var sum = 0.0
                for (index in 0 until signal.size - lag) {
                    sum += (signal[index] - mean) * (signal[index + lag] - mean)
                }
                sum / (signal.size - lag)
            }
        }

        /** The mean over [reach] frames either side, by running sums. */
        private fun movingAverage(signal: FloatArray, reach: Int): FloatArray {
            val sums = DoubleArray(signal.size + 1)
            for (index in signal.indices) sums[index + 1] = sums[index] + signal[index]
            return FloatArray(signal.size) { index ->
                val from = (index - reach).coerceAtLeast(0)
                val to = (index + reach + 1).coerceAtMost(signal.size)
                ((sums[to] - sums[from]) / (to - from)).toFloat()
            }
        }

        /** A Gaussian blur of [width] frames, so the programme can land a frame either side. */
        private fun smooth(signal: FloatArray, width: Double): FloatArray {
            val sigma = width.coerceAtLeast(0.5)
            val reach = (sigma * 3).roundToInt().coerceAtLeast(1)
            val kernel = FloatArray(reach * 2 + 1) { exp(-0.5 * ((it - reach) / sigma).pow(2)).toFloat() }
            val total = kernel.sum()
            return FloatArray(signal.size) { index ->
                var sum = 0f
                for (k in kernel.indices) {
                    val at = index + k - reach
                    if (at in signal.indices) sum += signal[at] * kernel[k]
                }
                sum / total
            }
        }

        private fun standardDeviation(signal: FloatArray): Double {
            val mean = signal.average()
            var sum = 0.0
            for (value in signal) sum += (value - mean) * (value - mean)
            return sqrt(sum / signal.size)
        }

        private fun median(signal: FloatArray): Float = signal.sortedArray()[signal.size / 2]
    }
}

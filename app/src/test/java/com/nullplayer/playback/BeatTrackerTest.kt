package com.nullplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatTrackerTest {

    private val rate = 44_100

    @Test
    fun `a click track is found at its tempo, on its clicks`() {
        val clicks = (0 until 60).map { 1_000 + it * 500 }
        val beats = analyse(render(30.0) { buffer -> clicks.forEach { click(buffer, it, 0.8f) } })

        assertEquals(120f, beats.bpm, 2f)
        assertTrue("confidence ${beats.confidence}", beats.confidence > 0.5f)
        assertOnBeat(beats, clicks)
    }

    @Test
    fun `a drum pattern is found at its bar's pulse rather than its hi-hats`() {
        // 96 bpm: kick on one and three, snare on two and four, hi-hat on every eighth.
        val beatMs = 60_000 / 96
        val start = 500
        val quarters = (0 until 60).map { start + it * beatMs }
        val audio = render(36.0) { buffer ->
            quarters.forEachIndexed { index, at ->
                if (index % 2 == 0) kick(buffer, at) else snare(buffer, at, seed = index)
                hat(buffer, at, seed = index)
                hat(buffer, at + beatMs / 2, seed = index + 1000)
            }
        }
        val beats = analyse(audio)

        assertEquals(96f, beats.bpm, 3f)
        assertTrue("confidence ${beats.confidence}", beats.confidence > 0.3f)
        assertOnBeat(beats, quarters)
    }

    @Test
    fun `the beat is kept through a bar with no drums`() {
        val clicks = (0 until 60).map { 1_000 + it * 500 }
        // Bars 5 to 8 go quiet but for a soft click on every beat.
        val beats = analyse(render(30.0) { buffer ->
            clicks.forEachIndexed { index, at -> click(buffer, at, if (index in 16..31) 0.15f else 0.8f) }
        })
        assertOnBeat(beats, clicks)
    }

    @Test
    fun `a held tone has no beat`() {
        val beats = analyse(render(20.0) { buffer ->
            for (index in buffer.indices) buffer[index] = 0.5f * sin(2 * PI * 110 * index / rate).toFloat()
        })
        assertTrue("confidence ${beats.confidence}", beats.confidence < 0.15f)
    }

    @Test
    fun `noise has no beat`() {
        val random = Random(7)
        val beats = analyse(render(20.0) { buffer ->
            for (index in buffer.indices) buffer[index] = random.nextFloat() - 0.5f
        })
        assertTrue("confidence ${beats.confidence}", beats.confidence < 0.15f)
    }

    @Test
    fun `too short a file has no beat`() {
        val beats = analyse(render(3.0) { buffer -> (0 until 6).forEach { click(buffer, 250 + it * 500, 0.8f) } })
        assertEquals(0, beats.timesMs.size)
        assertEquals(0f, beats.confidence)
    }

    @Test
    fun `stored beats read back as they were`() {
        val beats = Beats(intArrayOf(0, 480, 1_000_000), floatArrayOf(0f, 0.5f, 1f), 0.4f, 120f)
        val read = Beats.decode(beats.encode(), 0.4f)
        assertArrayEquals(beats.timesMs, read.timesMs)
        assertArrayEquals(beats.strengths, read.strengths, 1f / 255)
    }

    @Test
    fun `the fft finds a pure tone in its bin`() {
        val size = 256
        val real = FloatArray(size) { cos(2 * PI * 8 * it / size) }
        val imaginary = FloatArray(size)
        BeatTracker.fft(real, imaginary)
        val magnitudes = FloatArray(size / 2) { real[it] * real[it] + imaginary[it] * imaginary[it] }
        assertEquals(8, magnitudes.indices.maxBy { magnitudes[it] })
    }

    // -- Helpers ------------------------------------------------------------------------------------

    private fun cos(x: Double) = kotlin.math.cos(x).toFloat()

    private fun render(seconds: Double, draw: (FloatArray) -> Unit): FloatArray =
        FloatArray((seconds * rate).toInt()).also(draw)

    /** Fed in stereo, in decoder-sized pieces, the way [TrackScan] feeds it. */
    private fun analyse(mono: FloatArray): Beats {
        val tracker = BeatTracker(rate, channels = 2)
        val piece = FloatArray(4096)
        var at = 0
        while (at < mono.size) {
            val frames = minOf(piece.size / 2, mono.size - at)
            for (frame in 0 until frames) {
                piece[frame * 2] = mono[at + frame]
                piece[frame * 2 + 1] = mono[at + frame]
            }
            tracker.feed(piece, frames * 2)
            at += frames
        }
        return tracker.result()
    }

    /** Every expected beat inside the found span has a found beat within 30 ms of it. */
    private fun assertOnBeat(beats: Beats, expected: List<Int>) {
        assertTrue("found ${beats.timesMs.size} beats", beats.timesMs.size >= expected.size * 3 / 4)
        val first = beats.timesMs.first()
        val last = beats.timesMs.last()
        for (at in expected.filter { it in first..last }) {
            val nearest = beats.timesMs.minBy { abs(it - at) }
            assertTrue("beat at $at ms, nearest found $nearest ms", abs(nearest - at) <= 30)
        }
    }

    private fun click(buffer: FloatArray, atMs: Int, level: Float) {
        val start = atMs * rate / 1000
        for (index in 0 until rate / 100) {
            if (start + index < buffer.size) {
                buffer[start + index] += level * exp(-index / 60.0).toFloat() *
                    sin(2 * PI * 2_000 * index / rate).toFloat()
            }
        }
    }

    private fun kick(buffer: FloatArray, atMs: Int) {
        val start = atMs * rate / 1000
        for (index in 0 until rate / 5) {
            if (start + index >= buffer.size) break
            val t = index.toDouble() / rate
            val pitch = 50 + 100 * exp(-t * 30)
            buffer[start + index] += 0.8f * exp(-t * 12).toFloat() * sin(2 * PI * pitch * t).toFloat()
        }
    }

    private fun snare(buffer: FloatArray, atMs: Int, seed: Int) {
        val random = Random(seed)
        val start = atMs * rate / 1000
        for (index in 0 until rate / 6) {
            if (start + index >= buffer.size) break
            val t = index.toDouble() / rate
            buffer[start + index] += 0.5f * exp(-t * 20).toFloat() * (random.nextFloat() - 0.5f)
        }
    }

    private fun hat(buffer: FloatArray, atMs: Int, seed: Int) {
        val random = Random(seed)
        val start = atMs * rate / 1000
        for (index in 0 until rate / 20) {
            if (start + index >= buffer.size) break
            val t = index.toDouble() / rate
            buffer[start + index] += 0.15f * exp(-t * 80).toFloat() * (random.nextFloat() - 0.5f)
        }
    }
}

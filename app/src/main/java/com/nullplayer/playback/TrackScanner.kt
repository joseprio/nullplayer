package com.nullplayer.playback

import android.os.Process
import android.util.Log
import com.nullplayer.data.VaultRepository
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private const val TAG = "TrackScanner"

/**
 * The sweep that measures the loudness of whatever has not been measured yet.
 *
 * One track at a time, oldest first, with a pause between them. Analysing is decoding, so a vault
 * of a few hundred tracks is a few minutes of work the phone would rather not do all at once —
 * and there is nothing to be gained by finishing sooner, because the results only matter to
 * tracks that have not been played yet.
 *
 * The queue is the database itself: a track with no loudness recorded is a track to measure.
 * That makes the sweep resumable for free: it survives being cancelled, being killed with the
 * app, and an import that lands halfway through, and it never decodes the same file twice.
 */
class TrackScanner(private val repository: VaultRepository) {

    /**
     * Files that would not decode, remembered for as long as the app lives.
     *
     * Their loudness stays null in the database, because null is the truth and a made-up figure
     * would be applied to playback as though it had been measured. Held here instead, where it
     * keeps the sweep from picking the same
     * unreadable file up on every pass — and keeps the screen from counting it as work still to
     * do.
     */
    private val skipped = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The sweep's own thread, and deliberately a lowly one.
     *
     * `Dispatchers.Default` was the obvious home and the wrong one. Its threads run at the normal
     * priority every other piece of app work gets, and analysing is not normal work: it is a
     * decode of a whole track, sample by sample through the meters' filters, competing for the
     * same cores as the decode of the track being *listened to*. On screen that contest is invisible
     * — the foreground process has the big cores and clocks to spare. With the screen off it is
     * the same two jobs on a much smaller ration, and the one with a deadline is the one that
     * shows when it loses.
     *
     * A thread of its own is what makes the priority safe to set: dropping a shared pool thread to
     * background would leave it there for whatever unrelated work landed on it next. At
     * [Process.THREAD_PRIORITY_BACKGROUND] the scheduler puts this in the background group, where
     * it gets what is spare and nothing more — which is exactly the standing a sweep with no
     * deadline should have.
     */
    private val sweepThread = Executors.newSingleThreadExecutor { work ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "analysis-sweep")
    }.asCoroutineDispatcher()

    /** How many tracks are genuinely still to analyse: the unanalysed, less the unreadable. */
    val remaining: Flow<Int> =
        combine(repository.observeUnmeasured(), skipped) { pending, unreadable ->
            pending.count { it !in unreadable }
        }

    /** Runs until nothing is left to analyse. Cancel it to stop between — or during — tracks. */
    suspend fun drain() = withContext(sweepThread) {
        while (true) {
            val unreadable = skipped.value
            val next = repository.unmeasured().firstOrNull { it.id !in unreadable }
                ?: return@withContext
            val loudness = TrackScan.measure(repository.files.fileFor(next.id))
            if (loudness == null) {
                skipped.update { it + next.id }
                Log.i(TAG, "Nothing to analyse in ${next.id}")
            } else {
                repository.setLoudness(next.id, loudness.lufs, loudness.peak)
            }
            delay(BREATH_MS)
        }
    }

    /** Lets the thread go when the owner does; it is idle but it is still a thread. */
    fun release() {
        sweepThread.close()
    }

    private companion object {
        /**
         * A gap between tracks, so a large import does not hold a core at full tilt for minutes on
         * end while someone is trying to listen to the first file in it.
         */
        const val BREATH_MS = 400L
    }
}

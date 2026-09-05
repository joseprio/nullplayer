package com.nullplayer.playback

import android.util.Log
import com.nullplayer.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "LoudnessScanner"

/**
 * The sweep that measures whatever has not been measured yet.
 *
 * One track at a time, oldest first, with a pause between them. Measuring is decoding, so a vault
 * of a few hundred tracks is a few minutes of work the phone would rather not do all at once —
 * and there is nothing to be gained by finishing sooner, because the results only matter to
 * tracks that have not been played yet.
 *
 * The queue is the database itself: a track with no loudness recorded is a track to measure. That
 * makes the sweep resumable for free — it survives being cancelled, being killed with the app, and
 * an import that lands halfway through, and it never measures the same file twice.
 */
class LoudnessScanner(private val repository: VaultRepository) {

    /**
     * Files that would not decode, remembered for as long as the app lives.
     *
     * Their loudness stays null in the database, because null is the truth and a made-up figure
     * would be applied to playback as though it had been measured. Held here instead, where it
     * keeps the sweep from picking the same unreadable file up on every pass — and keeps the
     * screen from counting it as work still to do.
     */
    private val skipped = MutableStateFlow<Set<String>>(emptySet())

    /** How many tracks are genuinely still to measure: the unmeasured, less the unreadable. */
    val remaining: Flow<Int> =
        combine(repository.observeUnmeasured(), skipped) { pending, unreadable ->
            pending.count { it !in unreadable }
        }

    /** Runs until nothing is left to measure. Cancel it to stop between — or during — tracks. */
    suspend fun drain() = withContext(Dispatchers.Default) {
        while (true) {
            val unreadable = skipped.value
            val next = repository.unmeasured().firstOrNull { it.id !in unreadable }
                ?: return@withContext
            val loudness = LoudnessScan.measure(repository.files.fileFor(next.id))
            if (loudness == null) {
                skipped.update { it + next.id }
                Log.i(TAG, "Nothing to measure in ${next.id}")
            } else {
                repository.setLoudness(next.id, loudness.lufs, loudness.peak)
            }
            delay(BREATH_MS)
        }
    }

    private companion object {
        /**
         * A gap between tracks, so a large import does not hold a core at full tilt for minutes on
         * end while someone is trying to listen to the first file in it.
         */
        const val BREATH_MS = 400L
    }
}

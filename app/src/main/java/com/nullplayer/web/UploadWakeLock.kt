package com.nullplayer.web

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the CPU up and the radio at full speed for as long as an upload is running.
 *
 * A long upload is precisely the stretch where the phone believes it has nothing to do: the screen
 * is off because the user is at their laptop, and both the CPU governor and Wi-Fi's power-save
 * state read that as permission to idle. The transfer does not stop, it just slows to a crawl —
 * and a crawl is what eventually trips the socket timeout, which is the shape of an upload that
 * fails partway through for no visible reason.
 *
 * Playback already holds a lock of its own through ExoPlayer's wake mode. This is the same idea for
 * the other thing the app does that has to keep running while nobody is looking at it.
 */
internal class UploadWakeLock(context: Context) {

    private val cpu = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        ?.apply { setReferenceCounted(false) }

    // The application context on purpose: a WifiLock outlives any one request, and holding a
    // shorter-lived context here would be a leak.
    private val radio = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WIFI_MODE, TAG)
        ?.apply { setReferenceCounted(false) }

    /**
     * How many uploads are in flight. The locks are not reference counted themselves because the
     * bounded [acquire] below has to be re-armed by each new upload, which a counted lock would
     * not do.
     */
    private val running = AtomicInteger(0)

    /** Runs [block] with the phone awake, whatever [block] does or throws. */
    fun <T> heldFor(block: () -> T): T {
        if (running.incrementAndGet() == 1) acquire()
        try {
            return block()
        } finally {
            if (running.decrementAndGet() == 0) release()
        }
    }

    /** Also called when the server shuts down, in case a request died without unwinding. */
    fun release() {
        runCatching { if (cpu?.isHeld == true) cpu.release() }
        runCatching { if (radio?.isHeld == true) radio.release() }
    }

    private fun acquire() {
        // Bounded, so a request that dies in a way the `finally` above cannot see still cannot pin
        // the CPU on for the rest of the day.
        runCatching { cpu?.acquire(LIMIT_MS) }
        runCatching { radio?.acquire() }
    }

    private companion object {
        const val TAG = "nullplayer:upload"

        /** Generous for one file over a slow link, and nowhere near a battery-visible mistake. */
        const val LIMIT_MS = 10 * 60 * 1000L

        val WIFI_MODE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }
}

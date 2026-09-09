package com.nullplayer

import android.app.Application
import com.nullplayer.data.VaultCrypto
import com.nullplayer.playback.PlaybackGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class NullPlayerApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        clearUploadScratch()
        // Before anything can ask for a track: the player, the web server and the import path all
        // decrypt, and any of the three can be the first thing awake in the process.
        VaultCrypto.bind(this)
        // Bound here rather than in the service or the ViewModel so the playback rules are already
        // in force whichever of the two wakes up first.
        PlaybackGate.bind(this, scope)
    }

    /**
     * Uploads are spooled to a cache file before being encrypted into the vault. A crash mid-upload
     * could leave one behind in the clear, so the directory is emptied on every cold start.
     */
    private fun clearUploadScratch() {
        runCatching {
            File(cacheDir, "upload").listFiles()?.forEach { it.delete() }
        }
    }
}

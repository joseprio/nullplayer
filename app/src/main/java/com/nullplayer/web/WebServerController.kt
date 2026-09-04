package com.nullplayer.web

import android.content.Context
import android.util.Log
import com.nullplayer.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "WebServerController"
private val PORTS = 8080..8090

data class WebServerState(
    val enabled: Boolean = false,
    val url: String? = null,
    val pin: String? = null,
    val error: String? = null,
    /** People on the manager page right now, not people who have ever logged in. */
    val activeUsers: Int = 0,
)

/** Owns the lifetime of [VaultWebServer] and works out what address to tell the user about. */
class WebServerController(
    private val context: Context,
    private val repository: VaultRepository,
    private val activeVaultId: () -> String,
    /** Called when the server shuts itself off, so the stored preference can follow it down. */
    private val onIntrusion: () -> Unit,
) {

    private var server: VaultWebServer? = null
    private var watcher: Job? = null

    // NanoHTTPD answers on its own threads and the count has to fall on its own when nobody is
    // there, so it is polled rather than pushed: there is no request to hang the decay off.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    @Synchronized
    fun start() {
        if (server != null) return

        val address = localAddress()
        if (address == null) {
            _state.value = WebServerState(
                enabled = false,
                error = "Join a Wi-Fi network first."
            )
            return
        }

        for (port in PORTS) {
            val candidate = VaultWebServer(context, repository, port, activeVaultId) {
                stop(TOO_MANY_PINS)
                onIntrusion()
            }
            try {
                candidate.start(SOCKET_TIMEOUT_MS, /* daemon = */ false)
                server = candidate
                _state.value = WebServerState(
                    enabled = true,
                    url = "http://$address:$port",
                    pin = candidate.pin,
                )
                watchUsers(candidate)
                Log.i(TAG, "Serving on port $port")
                return
            } catch (e: IOException) {
                candidate.stop()
                Log.i(TAG, "Port $port unavailable, trying the next one", e)
            }
        }

        _state.value = WebServerState(enabled = false, error = "No free port in $PORTS.")
    }

    /**
     * [reason] survives into the state so the screen can say why the server went away.
     *
     * A stop on an already-stopped server leaves the state alone: turning the preference off in
     * response to an intrusion arrives here a second time, and it must not wipe the explanation
     * that the first call left behind.
     */
    @Synchronized
    fun stop(reason: String? = null) {
        val wasRunning = server != null
        watcher?.cancel()
        watcher = null
        server?.stop()
        server = null
        if (wasRunning || reason != null) {
            _state.value = WebServerState(enabled = false, error = reason)
        }
    }

    private fun watchUsers(running: VaultWebServer) {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                val count = running.activeUsers()
                _state.update { if (it.activeUsers == count) it else it.copy(activeUsers = count) }
                delay(POLL_MS)
            }
        }
    }

    /** First non-loopback IPv4 address, which on a phone is the Wi-Fi interface. */
    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "Could not enumerate network interfaces", t)
        null
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 10_000

        /** Three ticks inside the server's activity window, so the count falls promptly. */
        const val POLL_MS = 5_000L
        const val TOO_MANY_PINS =
            "Stopped after ${VaultWebServer.MAX_ATTEMPTS} wrong PINs. Turn it on again for a new one."
    }
}

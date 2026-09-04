package com.nullplayer.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One audio output the user can point at.
 *
 * [key] is deliberately not `AudioDeviceInfo.getId()`: that is handed out afresh every time a
 * device is plugged in, so a preference stored against it would stop matching the first time the
 * headphones came out. Type plus product name survives a reconnect, which is what a setting needs.
 */
data class AudioOutput(
    val key: String,
    val label: String,
    /** True for anything only the listener can hear — the whole point of the "headphones" gate. */
    val isHeadset: Boolean,
    /** Built-in speaker and earpiece: always present, so never worth requiring. */
    val isBuiltIn: Boolean,
)

/** Reads, names and watches the set of connected audio outputs. */
class AudioOutputs(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun snapshot(): List<AudioOutput> =
        devices().map { it.toOutput() }.distinctBy { it.key }.sortedBy { it.isBuiltIn }

    /** The live [AudioDeviceInfo] behind a stored key, or null while it is unplugged. */
    fun resolve(key: String): AudioDeviceInfo? =
        devices().firstOrNull { keyOf(it) == key }

    /**
     * Emits the connected outputs, starting with the current set — `registerAudioDeviceCallback`
     * replays everything already attached as an "added" callback the moment it is registered.
     */
    fun changes(): Flow<List<AudioOutput>> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(snapshot())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(snapshot())
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun devices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filterNot { it.type in IGNORED_TYPES }

    private fun AudioDeviceInfo.toOutput() = AudioOutput(
        key = keyOf(this),
        label = labelFor(this),
        isHeadset = type in HEADSET_TYPES,
        isBuiltIn = type in BUILT_IN_TYPES,
    )

    private fun keyOf(device: AudioDeviceInfo): String =
        "${device.type}:${productNameOf(device)}"

    private fun labelFor(device: AudioDeviceInfo): String =
        labelForKey(keyOf(device))

    private fun productNameOf(device: AudioDeviceInfo): String =
        device.productName?.toString()?.trim().orEmpty()

    companion object {

        /**
         * Names a device from its key alone, so a stored choice can still be shown by name while
         * it is unplugged — which is exactly when the user most needs to be told which one it was.
         */
        fun labelForKey(key: String): String {
            val kind = KIND_NAMES[key.substringBefore(':').toIntOrNull()] ?: "Audio device"
            val product = key.substringAfter(':', "")
            // Built-in outputs report the phone's own model as their product name, which tells the
            // user nothing they do not already know.
            val useful = product.isNotEmpty() &&
                !product.equals(Build.MODEL, ignoreCase = true) &&
                !product.equals(kind, ignoreCase = true)
            return if (useful) "$product · $kind" else kind
        }

        // TYPE_BLE_* landed in API 31. The constants are compile-time ints, so naming them here is
        // safe on older devices — they simply never turn up in the device list.
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private val BUILT_IN_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        )

        /** Routes that are never a listening choice. */
        private val IGNORED_TYPES = setOf(
            AudioDeviceInfo.TYPE_UNKNOWN,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        )

        private val KIND_NAMES = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Phone speaker",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE to "Phone speaker",
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to "Earpiece",
            AudioDeviceInfo.TYPE_WIRED_HEADSET to "Wired headset",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Wired headphones",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB audio",
            AudioDeviceInfo.TYPE_USB_ACCESSORY to "USB audio",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Bluetooth",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "Bluetooth",
            AudioDeviceInfo.TYPE_BLE_HEADSET to "Bluetooth LE",
            AudioDeviceInfo.TYPE_BLE_SPEAKER to "Bluetooth LE speaker",
            AudioDeviceInfo.TYPE_HEARING_AID to "Hearing aid",
            AudioDeviceInfo.TYPE_HDMI to "HDMI",
            AudioDeviceInfo.TYPE_HDMI_ARC to "HDMI",
            AudioDeviceInfo.TYPE_DOCK to "Dock",
            AudioDeviceInfo.TYPE_LINE_ANALOG to "Line out",
            AudioDeviceInfo.TYPE_LINE_DIGITAL to "Line out",
            AudioDeviceInfo.TYPE_AUX_LINE to "Aux",
        )
    }
}

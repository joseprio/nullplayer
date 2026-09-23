package com.nullplayer.playback

import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider

private const val TAG = "HapticEngine"

/**
 * Whether this phone can turn audio into vibration as it plays: the platform's generator effect,
 * and an audio path with haptic channels for it to write into. Most phones have neither; the ones
 * that do drive the actuator from the same stream the speaker gets.
 */
object HapticSupport {

    /** Android 12, where [HapticGenerator] arrived. */
    const val MIN_SDK = Build.VERSION_CODES.S

    fun available(): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK &&
            HapticGenerator.isAvailable() &&
            AudioManager.isHapticPlaybackSupported()
}

/**
 * Opens the player's audio tracks with their haptic channels unmuted while [wanted] says so.
 *
 * Media3 builds the track's attributes from the player's and offers no switch for this, so the
 * one seam is the track builder: [DefaultAudioTrackProvider] hands it over last thing before
 * building, and the attributes are set again there with the one flag changed. Everything else
 * about the track -- format, buffer, session -- is left as Media3 chose it.
 *
 * The flag is read as each track is opened, which the sink does on every new format and every
 * flush; that is why [HapticEngine] seeks in place when the switch is flipped mid-track.
 */
@UnstableApi
class HapticTracks : DefaultAudioSink.AudioTrackProvider {

    @Volatile
    var wanted = false

    private val builder = object : DefaultAudioTrackProvider() {
        var attributes: android.media.AudioAttributes? = null

        override fun customizeAudioTrackBuilder(builder: AudioTrack.Builder): AudioTrack.Builder =
            attributes?.let { builder.setAudioAttributes(it) } ?: builder
    }

    override fun getAudioTrack(
        config: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
    ): AudioTrack {
        // Tunnelled and offloaded tracks bypass the mixer the haptic channels are added in.
        builder.attributes = if (
            Build.VERSION.SDK_INT >= HapticSupport.MIN_SDK &&
            wanted && !config.tunneling && !config.offload
        ) {
            android.media.AudioAttributes.Builder(audioAttributes.audioAttributesV21.audioAttributes)
                .setHapticChannelsMuted(false)
                .build()
        } else {
            null
        }
        return builder.getAudioTrack(config, audioAttributes, audioSessionId)
    }
}

/**
 * The platform's [HapticGenerator] on the player's audio session: the phone vibrates with what
 * it plays, worked out from the audio as it goes rather than from anything analysed beforehand.
 *
 * Two halves have to agree for anything to be felt -- the effect on the session, and a track
 * opened with its haptic channels unmuted for the effect to write into -- so [configure] moves
 * both. Everything here runs on the player's thread.
 */
@UnstableApi
class HapticEngine(
    private val player: ExoPlayer,
    private val tracks: HapticTracks,
) {
    private var enabled = false
    private var generator: HapticGenerator? = null

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = attach()
    }

    init {
        player.addListener(listener)
    }

    fun configure(enabled: Boolean) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        tracks.wanted = enabled
        attach()
        // The open track was made under the old setting. A seek to where the player already is
        // flushes the sink, and the track it opens next is made under this one.
        if (player.currentMediaItem != null) player.seekTo(player.currentPosition)
    }

    fun release() {
        player.removeListener(listener)
        detach()
    }

    private fun attach() {
        detach()
        if (!enabled || Build.VERSION.SDK_INT < HapticSupport.MIN_SDK) return
        // An effect the platform will not create is a feature that stays off, not a service
        // that dies: the music matters more than the shaking.
        generator = runCatching {
            HapticGenerator.create(player.audioSessionId).apply { setEnabled(true) }
        }.onFailure { Log.w(TAG, "Could not create the haptic generator", it) }.getOrNull()
    }

    private fun detach() {
        generator?.let { runCatching { it.release() } }
        generator = null
    }
}

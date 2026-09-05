package com.nullplayer.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nullplayer.MainActivity
import com.nullplayer.R
import com.nullplayer.data.Group
import com.nullplayer.data.Settings
import com.nullplayer.data.VaultDataSource
import com.nullplayer.data.VaultFiles
import com.nullplayer.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    private lateinit var repository: VaultRepository
    private lateinit var voiceOver: VoiceOver
    private lateinit var outputs: AudioOutputs
    private lateinit var settings: Settings

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    private var isSpeaking = false

    /** Cleared whenever the notification's controller is new, so it is always told once. */
    private var notificationControls: NotificationControls? = null

    /** The tile the queue is drawn from. Read from the audio thread's side of the session. */
    @Volatile
    private var sourceName: String = ""

    override fun onCreate() {
        super.onCreate()

        repository = VaultRepository(this)
        outputs = AudioOutputs(this)
        settings = Settings(this)

        // The text-to-speech callback arrives on the engine's own thread, so everything it touches
        // is hopped back onto the main thread where the player lives.
        voiceOver = VoiceOver(this) { speaking ->
            scope.launch { onSpeakingChanged(speaking) }
        }

        // The equalizer is a link in the audio chain now rather than an effect bolted onto a
        // session id, so it has to be handed to the renderers as the player is built.
        val equalizer = EqualizerProcessor()
        AudioEffects.attach(equalizer)

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(EqualizedRenderers(this, equalizer))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(VaultDataSource.Factory(VaultFiles(this)))
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Without a wake lock the CPU can doze mid-track once the screen goes off. The vault
            // is local, so a local-only lock is enough; the WAKE_LOCK permission is already held.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exoPlayer

        session = MediaSession.Builder(
            this,
            GuardedPlayer(AnonymousPlayer(exoPlayer, getString(R.string.app_name)) { sourceName })
        )
            .setCallback(VoiceOverCallback())
            .setCustomLayout(listOf(speakButton()))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        exoPlayer.addListener(PlaybackTicket())
        ContextCompat.registerReceiver(
            this,
            becomingNoisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchSource()
        watchAudioPolicy()
        watchNotificationControls()
        watchSleepTimer()
        watchEqualizer()
        watchVoice()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = session?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(becomingNoisy) }
        AudioEffects.release()
        SleepTimer.cancel()
        voiceOver.release()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    // -- VoiceOver ------------------------------------------------------------------------------

    /** The button the notification draws, alongside the standard transport controls. */
    private fun speakButton(): CommandButton =
        CommandButton.Builder()
            .setDisplayName(getString(R.string.cd_voiceover))
            .setIconResId(R.drawable.ic_voiceover)
            .setSessionCommand(SessionCommand(VoiceCommands.SPEAK_TRACK, Bundle.EMPTY))
            .setEnabled(true)
            .build()

    private fun onSpeakingChanged(speaking: Boolean) {
        isSpeaking = speaking
        // Duck rather than pause, the way the real VoiceOver talks over the music.
        player?.volume = if (speaking) DUCKED_VOLUME else 1f
        session?.broadcastCustomCommand(
            SessionCommand(VoiceCommands.SPEAKING_CHANGED, Bundle.EMPTY),
            Bundle().apply { putBoolean(VoiceCommands.EXTRA_SPEAKING, speaking) },
        )
    }

    /** Pressing speak while it is already talking stops it, from the notification or the app. */
    private fun speakTrack(trackId: String?) {
        if (trackId == null && isSpeaking) {
            voiceOver.stop()
            return
        }
        val id = trackId ?: player?.currentMediaItem?.mediaId
        if (id == null) {
            voiceOver.speak(VoiceOver.describe(null))
            return
        }
        scope.launch { voiceOver.speak(VoiceOver.describe(repository.track(id))) }
    }

    private fun speakPosition() {
        val current = player ?: return
        val id = current.currentMediaItem?.mediaId
        if (id == null) {
            speakVaultSummary()
            return
        }
        val index = current.currentMediaItemIndex
        val total = current.mediaItemCount
        scope.launch {
            val duration = repository.track(id)?.durationMs ?: 0L
            voiceOver.speak(VoiceOver.describePosition(index, total, duration))
        }
    }

    /**
     * The fallback when the queue is empty and there is no track to describe. Always about the
     * selected tile: it is the one the queue was built from.
     */
    private fun speakVaultSummary() {
        scope.launch {
            val groupId = settings.all.first().activeGroupId
            val tracks = repository.tracks(groupId)
            voiceOver.speak(VoiceOver.describeVault(tracks.size, repository.vaultBytes(groupId)))
        }
    }

    private inner class VoiceOverCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands(speakable = true))
                .build()

        /**
         * The notification draws itself through a controller of its own, and that is the one held
         * to the gate, so it is fitted out the moment it arrives.
         */
        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (!session.isMediaNotificationController(controller)) return
            notificationControls = null
            refreshNotificationControls()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                VoiceCommands.SPEAK_TRACK -> {
                    val trackId = args.getString(VoiceCommands.EXTRA_TRACK_ID)
                    // A press with no track named while it is talking is a stop, and stopping
                    // is never refused — the same reason pausing never is.
                    val stopping = trackId == null && isSpeaking
                    if (stopping || PlaybackGate.allowVoiceOver()) speakTrack(trackId)
                }
                VoiceCommands.SPEAK_POSITION -> if (PlaybackGate.allowVoiceOver()) speakPosition()
                VoiceCommands.STOP_SPEAKING -> voiceOver.stop()
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // -- What the notification offers -----------------------------------------------------------

    /**
     * Puts the notification's buttons on the same terms as the ones on the player screen.
     *
     * The screen greys a refused button and still takes the press, because it has somewhere to
     * print the reason. A notification has not, and Android draws no such thing as a greyed media
     * action — so what it does instead is stop offering the control: a command the notification's
     * controller was not given takes no press, and its button goes with it.
     *
     * This decides only what the notification draws. [GuardedPlayer] and [VoiceOverCallback] still
     * refuse on their own, which is what holds a Bluetooth remote and a car head unit — neither
     * of which reads any of this — to the same rules.
     */
    private fun refreshNotificationControls() {
        val live = session ?: return
        val notification = live.mediaNotificationControllerInfo ?: return

        // Both halves are re-read on every track change, and pushing a value that has not moved
        // would rebuild the notification for nothing.
        val wanted = NotificationControls(playable(), PlaybackGate.state.value.outputSatisfied)
        if (wanted == notificationControls) return
        notificationControls = wanted
        val speakable = wanted.speakable

        live.setAvailableCommands(
            notification,
            sessionCommands(speakable),
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .removeIf(Player.COMMAND_PLAY_PAUSE, !playable())
                .build(),
        )
        live.setCustomLayout(notification, if (speakable) listOf(speakButton()) else emptyList())
    }

    /**
     * The player screen's rule for the play button, answered from the service's side.
     *
     * Pausing is never refused, so a player that is running keeps its button whatever else is
     * true: a gate that could trap the music playing would be worse than no gate at all.
     */
    private fun playable(): Boolean {
        val current = player ?: return false
        if (current.isPlaying) return true
        return current.mediaItemCount > 0 && PlaybackGate.state.value.outputSatisfied
    }

    /** What the notification was last told it could do. */
    private data class NotificationControls(val playable: Boolean, val speakable: Boolean)

    /** VoiceOver's commands are withheld from a controller that is not allowed to speak. */
    private fun sessionCommands(speakable: Boolean): SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
            .buildUpon()
            .apply {
                VoiceCommands.ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
                if (!speakable) {
                    remove(SessionCommand(VoiceCommands.SPEAK_TRACK, Bundle.EMPTY))
                    remove(SessionCommand(VoiceCommands.SPEAK_POSITION, Bundle.EMPTY))
                }
            }
            .build()

    /** Redraws the notification's buttons whenever anything their rules read has moved. */
    private fun watchNotificationControls() {
        scope.launch {
            PlaybackGate.state
                .map { it.outputSatisfied }
                .distinctUntilChanged()
                .collect { refreshNotificationControls() }
        }
    }

    // -- Audio policy ---------------------------------------------------------------------------

    /**
     * ACTION_AUDIO_BECOMING_NOISY: the headphones just came out, or the Bluetooth link dropped, and
     * the audio is about to fall back to the phone's own speaker.
     *
     * ExoPlayer can do the pause itself via `setHandleAudioBecomingNoisy`, but pausing is only half
     * of what has to happen here. The biometric unlock is spent at the same moment, so plugging the
     * headphones back in cannot resume the music without another prompt — and VoiceOver is cut off,
     * because it would otherwise read the track title out of the phone's speaker to the room.
     */
    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            player?.pause()
            voiceOver.stop()
            PlaybackGate.relockPlayback()
        }
    }

    /**
     * Applies the routing preference, and pulls the plug when the required device goes away.
     *
     * [PlaybackGate] re-emits on every device change, so a preferred device that is unplugged and
     * reconnected is picked up again without anything else having to notice. Both halves are
     * narrowed to the value they actually care about: re-routing a live audio track is not free,
     * and the gate's state also changes for reasons — a biometric unlock — that mean nothing here.
     */
    /**
     * Keeps the notification's idea of where the music is coming from up to date.
     *
     * Renaming a group while it plays will not move the notification on its own — the session only
     * re-reads metadata when the player reports a change, and a rename is not something the player
     * knows about. The next track sorts it out, which is soon enough for a name the user has just
     * typed themselves.
     */
    private fun watchSource() {
        scope.launch {
            combine(
                settings.all.map { it.activeGroupId }.distinctUntilChanged(),
                repository.observeGroups(),
            ) { activeId, groups ->
                groups.firstOrNull { it.id == activeId }?.name ?: Group.VAULT_NAME
            }
                .distinctUntilChanged()
                .collect { sourceName = it }
        }
    }

    private fun watchAudioPolicy() {
        scope.launch {
            PlaybackGate.state
                .map { it.preferredDeviceKey to it.outputs }
                .distinctUntilChanged()
                .collect { (key, _) ->
                    player?.setPreferredAudioDevice(
                        key.takeIf { it.isNotEmpty() }?.let { outputs.resolve(it) }
                    )
                }
        }
        scope.launch {
            PlaybackGate.state
                .map { it.outputSatisfied }
                .distinctUntilChanged()
                .collect { satisfied -> if (!satisfied) player?.pause() }
        }
    }

    /** Spends the biometric unlock whenever playback stops, however it stopped. */
    private inner class PlaybackTicket : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) PlaybackGate.relockPlayback()
        }

        /**
         * The other two things the play button's rule reads: whether the music is running, and
         * whether there is a queue at all. Media3 rebuilds the notification for neither on this
         * account, because neither changes what the *player* offers — only what this service
         * has decided to pass on.
         */
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                refreshNotificationControls()
            }
        }
    }

    /**
     * The sleep timer.
     *
     * `collectLatest` is what makes re-arming work: changing the deadline cancels the pending
     * delay rather than leaving a second one running behind it.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                player?.pause()
                voiceOver.stop()
                SleepTimer.cancel()
            }
        }
    }

    /**
     * Applies the chosen voice, and reads a line back in it when the choice changes.
     *
     * The first emission is the stored value being restored at startup, which nobody asked to
     * hear; only a later one is someone actually picking from the list.
     */
    private fun watchVoice() {
        scope.launch {
            settings.all
                .map { it.voiceName }
                .distinctUntilChanged()
                .collectIndexed { index, name ->
                    voiceOver.setVoice(name)
                    if (index > 0) voiceOver.speak(VoiceOver.VOICE_SAMPLE)
                }
        }
    }

    /** Keeps the equalizer in step with the stored settings. */
    private fun watchEqualizer() {
        scope.launch {
            settings.all
                .map {
                    EqualizerConfig(
                        it.equalizerEnabled,
                        it.equalizerAutoEq,
                        it.equalizerPreset,
                        it.equalizerBands,
                    )
                }
                .distinctUntilChanged()
                .collect { AudioEffects.apply(it.enabled, it.autoEq, it.preset, it.bands) }
        }
    }

    private data class EqualizerConfig(
        val enabled: Boolean,
        val autoEq: String,
        val preset: Int,
        val bands: List<Int>,
    )

    /**
     * The stock renderers, with the equalizer spliced into the audio sink.
     *
     * Overriding the sink is the only seam Media3 offers for a processor that has to run after
     * decoding and before the device: `setAudioProcessors` on a builder we do not own otherwise.
     */
    private class EqualizedRenderers(
        context: Context,
        private val equalizer: EqualizerProcessor,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(equalizer))
            // Left off after measuring what turning it on actually does here.
            //
            // The sink offers an app's processors either 16-bit or float, never the source's own
            // encoding, and the converter that decides which runs ahead of them. Off, a 24-bit
            // 96 kHz track reaches the equalizer as 16-bit at its full 96 kHz and every filter
            // runs. On, the same track never reaches the equalizer at all — the pipeline is not
            // configured, and the curve silently stops applying to exactly the files someone
            // enabling this would care most about.
            //
            // So the depth is spent to keep the effect, which is the right way round: the
            // equalizer itself no longer refuses float, so this is a statement about the sink
            // rather than about what the processor can do, and it is one flag to flip on a
            // platform where that path behaves.
            .setEnableFloatOutput(false)
            .build()
    }

    /**
     * Holds every controller to the same rules — the click wheel, the notification, a Bluetooth
     * remote, a car head unit and the Assistant all reach the player through here.
     *
     * Only starting is guarded. Pausing, seeking and skipping stay available, because a gate that
     * could trap the music playing would be worse than no gate at all.
     */
    private inner class GuardedPlayer(player: Player) : ForwardingPlayer(player) {

        override fun play() {
            if (PlaybackGate.allowPlayback()) super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (!playWhenReady || PlaybackGate.allowPlayback()) super.setPlayWhenReady(playWhenReady)
        }
    }

    // -- Anonymity ------------------------------------------------------------------------------

    /**
     * ExoPlayer merges tags read out of the stream (ID3, Vorbis comments, embedded artwork) into
     * [Player.getMediaMetadata]. That merged value is what the notification, a car head unit, a
     * smartwatch and the Assistant all read — so it is the one place a title could still escape.
     * Overriding it closes that path for every consumer at once.
     *
     * What replaces it is not blank. The two things the player's own face already shows — which
     * tile the queue came from, and how far through it we are — are not titles, so they are safe
     * to say out loud, and they turn a notification that said only "nullplayer" into one that
     * answers "what am I listening to" as well as this app ever can.
     */
    private class AnonymousPlayer(
        player: Player,
        private val fallback: String,
        private val source: () -> String,
    ) : ForwardingPlayer(player) {

        override fun getMediaMetadata(): MediaMetadata {
            val builder = MediaMetadata.Builder()
                .setTitle(source().ifBlank { fallback })
                .setIsBrowsable(false)
                .setIsPlayable(true)

            // Before the queue is built there is no position to report, and "track 1 of 0" would
            // be worse than saying nothing.
            val total = mediaItemCount
            if (total > 0) {
                builder.setArtist("Track ${currentMediaItemIndex + 1} of $total")
            }
            return builder.build()
        }
    }

    private companion object {
        const val DUCKED_VOLUME = 0.18f
    }
}

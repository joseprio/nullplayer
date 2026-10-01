package com.nullplayer.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null

    private lateinit var repository: VaultRepository
    private lateinit var vault: VaultFiles
    private lateinit var voiceOver: VoiceOver
    private lateinit var outputs: AudioOutputs
    private lateinit var settings: Settings

    /**
     * The haptic generator, on a phone that can play what it makes; null on one that cannot, and
     * then the whole feature is absent rather than present and inert.
     */
    private var haptics: HapticEngine? = null
    private val hapticTracks = HapticTracks()

    /** The vibrator as a subwoofer; see [HapticMode.SUBWOOFER]. */
    private var subwoofer: SubwooferHaptics? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    private var isSpeaking = false

    /** Cleared whenever the notification's controller is new, so it is always told once. */
    private var notificationControls: NotificationControls? = null

    /** The loudness read for the current track, cancelled if the track changes under it. */
    private var loudnessLookup: Job? = null

    /** Writes the resume point down as the music moves. Alive only while it is moving. */
    private var progressJob: Job? = null

    /** The format the renderer was last handed, and the job describing it to the UI. */
    @Volatile
    private var audioFormat: Format? = null
    private var profileLookup: Job? = null

    /** The tile the queue is drawn from. Read from the audio thread's side of the session. */
    @Volatile
    private var sourceName: String = ""

    /**
     * The track playing, by id, as the listener reports it — a flow, so that the notification's
     * naming can be recomputed for a track change and a setting change alike.
     */
    private val currentTrackId = MutableStateFlow<String?>(null)

    /**
     * The current track's real tags, or null while the notification is to stay anonymous. Read
     * by [AnonymousPlayer] on every metadata request, so it is a field rather than a parameter.
     */
    private var revealed: MediaMetadata? = null

    private lateinit var anonymousPlayer: AnonymousPlayer

    override fun onCreate() {
        super.onCreate()

        repository = VaultRepository(this)
        vault = VaultFiles(this)
        outputs = AudioOutputs(this)
        settings = Settings(this)

        // The text-to-speech callback arrives on the engine's own thread, so everything it touches
        // is hopped back onto the main thread where the player lives.
        voiceOver = VoiceOver(this) { speaking ->
            scope.launch { onSpeakingChanged(speaking) }
        }

        // The equalizer is a link in the audio chain now rather than an effect bolted onto a
        // session id, so it has to be handed to the renderers as the player is built. Volume
        // normalisation and crossfeed are further links in the same chain, for the same reason.
        val equalizer = EqualizerProcessor()
        val gain = GainProcessor()
        val crossfeed = CrossfeedProcessor()
        val pulse = PeakProcessor()
        AudioEffects.attach(equalizer, gain, crossfeed)

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(EqualizedRenderers(this, equalizer, gain, crossfeed, pulse, hapticTracks))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(VaultDataSource.Factory(vault))
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

        anonymousPlayer = AnonymousPlayer(
            player = CircularPlayer(exoPlayer),
            fallback = getString(R.string.app_name),
            source = { sourceName },
            reveal = { revealed },
        )
        session = MediaSession.Builder(this, GuardedPlayer(anonymousPlayer))
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

        if (HapticSupport.available()) {
            haptics = HapticEngine(exoPlayer, hapticTracks)
        }
        subwoofer = SubwooferHaptics(this, exoPlayer, scope)

        exoPlayer.addListener(PlaybackTicket())
        exoPlayer.addAnalyticsListener(PlaybackDiagnostics())
        exoPlayer.addAnalyticsListener(FormatWatcher())
        ContextCompat.registerReceiver(
            this,
            becomingNoisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        watchSource()
        watchNotificationInfo()
        watchAudioPolicy()
        watchNotificationControls()
        watchSleepTimer()
        watchEqualizer()
        watchNormalization()
        watchCrossfeed()
        watchHaptics()
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
        haptics?.release()
        subwoofer?.release()
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
        // Read at the moment of speaking rather than held in a field: the announcement happens
        // once, on a press, so there is nothing to keep in step between presses.
        scope.launch {
            val parts = settings.all.first().voiceParts
            voiceOver.speak(VoiceOver.describe(repository.track(id), parts))
        }
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
                // Performed here rather than by the caller so the wrap is decided against the
                // real queue: only this side knows the shuffled order, and only this side is
                // past the command gate that closes at the ends of a non-repeating queue.
                QueueCommands.SKIP_NEXT -> session.player.seekToNextMediaItem()
                QueueCommands.SKIP_PREVIOUS -> session.player.seekToPreviousMediaItem()
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
                QueueCommands.ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
                FormatCommands.ALL.forEach { add(SessionCommand(it, Bundle.EMPTY)) }
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

    /**
     * Keeps [revealed] in step with the track and the setting, and tells the session when it
     * has moved.
     *
     * The tags are read off the disk — the sleeve especially — so the answer lands a moment
     * after the track changes, and the session, which read the metadata on the change itself,
     * has to be told to read it again. `collectLatest` drops a read overtaken by the next track.
     * Switching the setting off empties the field at once, before anything is read, so the
     * notification goes anonymous on the same turn as the switch.
     */
    private fun watchNotificationInfo() {
        scope.launch {
            combine(
                settings.all.map { it.notificationTrackInfo }.distinctUntilChanged(),
                currentTrackId,
            ) { named, id -> id.takeIf { named } }
                .distinctUntilChanged()
                .collectLatest { id ->
                    revealed = null
                    if (id != null) {
                        val track = repository.track(id)
                        if (track != null) {
                            val builder = MediaMetadata.Builder()
                                .setTitle(track.title)
                                .setArtist(track.artist)
                                .setAlbumTitle(track.album)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                            repository.notificationArtwork(id)?.let {
                                builder.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                            revealed = builder.build()
                        }
                    }
                    anonymousPlayer.metadataChanged()
                }
        }
    }

    private fun watchAudioPolicy() {
        scope.launch {
            PlaybackGate.state
                .map { it.preferredDeviceKey to it.outputs }
                .distinctUntilChanged()
                // The list has to be watched, because a preference only becomes applicable when
                // the device it names turns up. But it must not be what decides to *act*: a phone
                // with a watch, a pair of earbuds and a car on its books sees that list change on
                // its own, and re-applying the same device to a playing track is not free. It asks
                // the audio server to reconsider the routing, and if the track is moved as a
                // result the old one is invalidated underneath it — `dead IAudioTrack` in the log,
                // and a gap in the music that has nothing to do with the music.
                //
                // So the resolved device is what the change is judged on. An id is stable while a
                // device stays connected and fresh when it comes back, which is exactly the line
                // between "the list moved" and "our device did".
                .map { (key, _) -> key.takeIf { it.isNotEmpty() }?.let { outputs.resolve(it) } }
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collect { device -> player?.setPreferredAudioDevice(device) }
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
            // A pause is where a session usually ends, so it is the one worth writing down
            // exactly rather than leaving to the next tick.
            saveResumePoint()
        }

        /** The tick is what makes the resume point durable, so it runs only while there is one. */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            trackProgress(isPlaying)
        }

        /**
         * A seek moves the position without the clock having moved it, and a seek made while
         * paused would otherwise not be written down until something else happened to save.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            saveResumePoint()
        }

        /** Every track brings its own level with it, so the gain is re-read on every change. */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyLoudness(mediaItem?.mediaId)
            currentTrackId.value = mediaItem?.mediaId
            // The position the old track reached is of no interest once it has been left.
            saveResumePoint()
            // Also from here, not only from the renderer: two files encoded identically produce
            // one format between them and so only one format change, but whether each of them
            // varies its bitrate is a question about the file rather than about the format.
            publishProfile()
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
     * Why the music broke up, when it does.
     *
     * A stutter has two quite different causes and they are indistinguishable by ear. An underrun
     * is the sink running dry: the audio thread did not refill the track in time, which on a
     * vault of local files means the CPU was elsewhere — the per-sample work in [GainProcessor]
     * and [EqualizerProcessor] is real, and a backgrounded process is scheduled on smaller cores
     * at lower clocks than the one that was on screen a moment ago. A drop to [Player.STATE_BUFFERING]
     * is the opposite: the sink was willing and there was nothing to give it, which would point at
     * the read and decrypt path instead.
     *
     * Both are logged rather than counted. They are supposed to be rare enough that a line each is
     * the right weight, and a listening session with the screen off then answers the question that
     * guessing cannot.
     */
    private inner class PlaybackDiagnostics : AnalyticsListener {

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            // `elapsedSinceLastFeedMs` against `bufferSizeMs` is the whole diagnosis: a gap longer
            // than the buffer is how long the audio thread went unscheduled.
            Log.w(
                TAG,
                "Audio underrun: the sink held ${bufferSizeMs}ms ($bufferSize bytes) and went " +
                    "${elapsedSinceLastFeedMs}ms without a refill",
            )
        }

        override fun onPlaybackStateChanged(
            eventTime: AnalyticsListener.EventTime,
            state: Int,
        ) {
            if (state == Player.STATE_BUFFERING) {
                Log.w(TAG, "Rebuffering at ${eventTime.currentPlaybackPositionMs}ms")
            }
        }

        /** Says what the sink actually settled on, which is not always what its defaults asked for. */
        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            config: AudioSink.AudioTrackConfig,
        ) {
            Log.i(
                TAG,
                "Audio track: ${config.bufferSize} bytes at ${config.sampleRate} Hz, " +
                    "encoding ${config.encoding}, offload ${config.offload}",
            )
        }

        override fun onAudioSinkError(
            eventTime: AnalyticsListener.EventTime,
            audioSinkError: Exception,
        ) {
            Log.w(TAG, "Audio sink error", audioSinkError)
        }
    }

    /** Notices what the renderer was handed, which is the only place the format is knowable. */
    private inner class FormatWatcher : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            audioFormat = format
            publishProfile()
        }
    }

    /**
     * Tells every controller what is playing, in the sense of what kind of file it is.
     *
     * The bitrate question is answered off the audio thread because for an MP3 it means opening
     * the file, and the answer is worth waiting a few milliseconds for -- the line appears a beat
     * after the track starts rather than blocking anything to be exact from the first sample.
     */
    private fun publishProfile() {
        profileLookup?.cancel()
        val format = audioFormat
        if (format == null) {
            broadcastProfile(null)
            return
        }
        val trackId = player?.currentMediaItem?.mediaId
        profileLookup = scope.launch {
            val variable = variableBitrate(format, trackId)
            broadcastProfile(AudioProfile.of(format, variable))
        }
    }

    /**
     * Whether the bitrate varies, asked of whichever authority can answer for this format.
     *
     * Three different answers to one question, because the question is answered in three different
     * places. An MP4 carries both an average and a peak and a gap between them is the file saying
     * so itself. Vorbis and Opus have no constant mode worth the name. And an MP3 carries nothing
     * at all above the bytes, so the bytes are read -- see [Mp3Vbr].
     */
    private suspend fun variableBitrate(format: Format, trackId: String?): Boolean =
        when (format.sampleMimeType) {
            MimeTypes.AUDIO_MPEG ->
                trackId?.let { id ->
                    withContext(Dispatchers.IO) { Mp3Vbr.isVariable(vault.fileFor(id)) }
                } ?: false

            MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_OPUS -> true

            else -> format.averageBitrate != Format.NO_VALUE &&
                format.peakBitrate != Format.NO_VALUE &&
                format.peakBitrate > format.averageBitrate
        }

    private fun broadcastProfile(profile: AudioProfile?) {
        session?.broadcastCustomCommand(
            SessionCommand(FormatCommands.PROFILE_CHANGED, Bundle.EMPTY),
            profile?.toBundle() ?: Bundle.EMPTY,
        )
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
     * Writes down where the music is, so a cold start can pick it up.
     *
     * Here rather than in the view model because this is the half that survives the Activity, and
     * a phone playing in a pocket is exactly the case worth surviving. Every few seconds rather
     * than every frame: a preference file written at the rate the seeker moves would be a disk
     * write per tick, and the cost of being out of date is hearing a few seconds twice.
     *
     * The tick runs only while the music does. A pause, a seek and a track change each write on
     * their own, so a stopped player has nothing left to say and no reason to keep waking up.
     *
     * There is deliberately no write from [onDestroy]. A DataStore write is a suspending one, the
     * scope it would need is being cancelled in the same breath, and a force-stop never runs it
     * anyway -- so the tick is what makes this durable, and the tick is enough.
     */
    private fun trackProgress(playing: Boolean) {
        if (!playing) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                delay(RESUME_SAVE_MS)
                saveResumePoint()
            }
        }
    }

    /** The current track and how far into it, or nothing at all when the queue is empty. */
    private fun saveResumePoint() {
        val current = player ?: return
        val trackId = current.currentMediaItem?.mediaId ?: return
        val position = current.currentPosition.coerceAtLeast(0L)
        scope.launch { settings.setResumePoint(trackId, position) }
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

    /**
     * Keeps volume normalisation in step with the stored setting — and with the equalizer's own
     * switch, which levelling rides along with rather than standing beside.
     */
    private fun watchNormalization() {
        scope.launch {
            settings.all
                .map { it.normalizingVolume }
                .distinctUntilChanged()
                .collect { AudioEffects.setNormalization(it) }
        }
    }

    /**
     * Keeps the generator and the subwoofer in step with the stored mode. Each is told whether it is
     * the one wanted, so a change of mode turns the old one off as it turns the new one on.
     */
    private fun watchHaptics() {
        scope.launch {
            settings.all
                .map { HapticMode.ofOrdinal(it.hapticModeOrdinal) }
                .distinctUntilChanged()
                .collect { mode ->
                    haptics?.configure(mode == HapticMode.AUDIO)
                    subwoofer?.configure(mode == HapticMode.SUBWOOFER)
                }
        }
    }

    /**
     * Keeps crossfeed in step with the stored settings, under the equalizer's switch likewise —
     * and with what is plugged in, when the setting says it should only run on headphones.
     *
     * The outputs come from the gate, which is already watching them for its own reasons; a
     * second callback registered here would be told the same things a moment apart.
     */
    private fun watchCrossfeed() {
        scope.launch {
            combine(settings.all, PlaybackGate.state) { config, gate ->
                val wearing = !config.crossfeedHeadphonesOnly || gate.outputs.headphonesConnected
                CrossfeedConfig(
                    enabled = config.crossfeeding && wearing,
                    strength = CrossfeedStrength.ofOrdinal(config.crossfeedStrengthOrdinal),
                )
            }
                .distinctUntilChanged()
                .collect { AudioEffects.setCrossfeed(it.enabled, it.strength) }
        }
    }

    /**
     * Hands the gain stage the measurement for the track that just became current.
     *
     * The read is a database lookup, so it lands a few milliseconds into the track rather than
     * exactly on its first sample — which the gain's own ramp absorbs. The same slack works the
     * other way at a track boundary: the sink still holds the tail of the previous track when
     * this fires, so the new level starts creeping in slightly before the new track does. At the
     * ramp's length that is well under the gap between two songs, and the alternative — a gain
     * carried per media item through a sink that has no notion of one — does not exist in Media3.
     */
    private fun applyLoudness(trackId: String?) {
        loudnessLookup?.cancel()
        loudnessLookup = scope.launch {
            val track = trackId?.let { repository.track(it) }
            val measured = track?.loudnessLufs?.let { lufs ->
                // A peak that somehow went missing is read as full scale, which forbids any boost
                // rather than permitting one that could clip.
                Loudness(lufs = lufs, peak = track.peakAmplitude ?: 1.0)
            }
            AudioEffects.setTrackLoudness(measured)
        }
    }

    private data class EqualizerConfig(
        val enabled: Boolean,
        val autoEq: String,
        val preset: Int,
        val bands: List<Int>,
    )

    private data class CrossfeedConfig(val enabled: Boolean, val strength: CrossfeedStrength)

    /**
     * The stock renderers, with the equalizer spliced into the audio sink.
     *
     * Overriding the sink is the only seam Media3 offers for a processor that has to run after
     * decoding and before the device: `setAudioProcessors` on a builder we do not own otherwise.
     */
    private class EqualizedRenderers(
        context: Context,
        private val equalizer: EqualizerProcessor,
        private val gain: GainProcessor,
        private val crossfeed: CrossfeedProcessor,
        private val pulse: PeakProcessor,
        private val hapticTracks: HapticTracks,
    ) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            // Normalisation first: a track pulled down to the target reaches the curve with room
            // for its boosts, where the same attenuation after the curve would arrive too late.
            // Crossfeed last: it only ever mixes the two sides of what it is given, so it can add
            // nothing to a peak the curve has already clamped. The peak meter after all of them, so
            // it measures what is heard and counts the frames the track is given.
            .setAudioProcessors(arrayOf(gain, equalizer, crossfeed, pulse))
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
            // Opens each track with its haptic channels unmuted when haptics are on; see
            // [HapticTracks] for why this is the seam.
            .setAudioTrackProvider(hapticTracks)
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

    // -- The queue as a ring --------------------------------------------------------------------

    /**
     * Skipping wraps: next from the last track lands on the first, previous from the first on the
     * last, whatever the repeat mode says.
     *
     * Repeat answers what happens when a track *ends* — whether the queue plays on past its end by
     * itself. Media3 lets it answer the skip buttons as well, which runs two questions together: a
     * press of next is an instruction rather than a request to play on, and it should mean the
     * same thing wherever in the queue it is pressed. Media3 already reads REPEAT_MODE_ONE this
     * way — a skip leaves the track instead of replaying it — and this extends the same reading to
     * REPEAT_MODE_OFF, so the two skip buttons behave identically in all three modes.
     *
     * It sits below [AnonymousPlayer] so that everything reaches it: the player screen through its
     * controller, and a headset or head unit through the session.
     */
    private class CircularPlayer(player: Player) : ForwardingPlayer(player) {

        /**
         * The skip commands, granted for as long as there is a queue at all.
         *
         * ExoPlayer withdraws these at the ends of a non-repeating queue, and a withdrawn command
         * is not merely unadvertised: `MediaSessionLegacyStub` checks it against the controller's
         * granted set and drops the press without ever reaching the player. So a headset, a car
         * head unit, a watch or the lock screen could not wrap the queue even though the buttons
         * on screen could — the screen goes the long way round, through [QueueCommands].
         *
         * Re-granting them here rather than in the session is deliberate: what a controller may do
         * is the intersection of what the session grants and what the player reports, and the
         * player is the half that was saying no.
         *
         * Overriding the getter is enough, and the listener is deliberately left alone. A session
         * treats the commands handed to `onAvailableCommandsChanged` as a signal rather than as
         * the value — it rebuilds its picture of the player from the getters — so the withdrawal
         * ExoPlayer reports at the end of the queue still arrives to trigger the refresh, and the
         * refresh then reads the set below.
         */
        private fun wrap(commands: Player.Commands): Player.Commands =
            if (mediaItemCount == 0) {
                commands
            } else {
                commands.buildUpon().addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                ).build()
            }

        override fun getAvailableCommands(): Player.Commands = wrap(super.getAvailableCommands())

        /**
         * Answered from [getAvailableCommands] rather than passed down, because
         * [ForwardingPlayer.isCommandAvailable] asks the wrapped player directly and would walk
         * straight past the override above.
         */
        override fun isCommandAvailable(command: Int): Boolean =
            availableCommands.contains(command)

        override fun seekToNextMediaItem() = step(forward = true)

        override fun seekToPreviousMediaItem() = step(forward = false)

        override fun seekToNext() = step(forward = true)

        /**
         * A hardware "previous" carries the same restart-first rule as the button on screen, and
         * Media3's own threshold for it is the one both are measured against.
         */
        override fun seekToPrevious() {
            if (currentPosition > maxSeekToPreviousPosition) seekTo(0L) else step(forward = false)
        }

        /**
         * One step through the queue, closing the ring by hand at either end.
         *
         * The neighbour is asked for as if repeat were off, which is the only mode that reports
         * the true ends; an unset answer *is* an end, and the far end is where it goes. The
         * timeline is asked rather than the indices being counted, so with shuffle on "the first"
         * means the first of the shuffled order rather than of the list.
         */
        private fun step(forward: Boolean) {
            val timeline = currentTimeline
            if (timeline.isEmpty) return

            val shuffled = shuffleModeEnabled
            val here = currentMediaItemIndex
            val neighbour = if (forward) {
                timeline.getNextWindowIndex(here, Player.REPEAT_MODE_OFF, shuffled)
            } else {
                timeline.getPreviousWindowIndex(here, Player.REPEAT_MODE_OFF, shuffled)
            }

            val target = when {
                neighbour != C.INDEX_UNSET -> neighbour
                forward -> timeline.getFirstWindowIndex(shuffled)
                else -> timeline.getLastWindowIndex(shuffled)
            }
            if (target != C.INDEX_UNSET) seekTo(target, C.TIME_UNSET)
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
     *
     * [reveal] is the one way round it: the setting that names the track in the notification.
     * It answers with the track's real tags, read by the service rather than merged by
     * ExoPlayer, so what the notification shows is what the dock shows and not whatever the
     * stream's tags happened to contain.
     *
     * The getter is only half of it. The session keeps its own copy of the metadata, taken from
     * the change events rather than from the getter, and the base class forwards those events
     * with ExoPlayer's merged tags in them. So every listener is wrapped, and its metadata events
     * carry this class's answer instead. Without that, the file's own tags reached every
     * controller all the same -- a FLAC's embedded cover at full size among them, over a
     * megabyte, resent to a paired watch on every change of state until its transaction buffer
     * overflowed, and built on the main thread each time.
     */
    private class AnonymousPlayer(
        player: Player,
        private val fallback: String,
        private val source: () -> String,
        private val reveal: () -> MediaMetadata?,
    ) : ForwardingPlayer(player) {

        /**
         * Everyone listening to this player, each with the wrapper it was registered as, kept so
         * that [metadataChanged] can reach them. The session registers through [addListener],
         * and the base class wraps and forwards what the wrapped player says; a change that only
         * this class knows about — the tags arriving from disk — has to be announced from here.
         */
        private val listeners = java.util.concurrent.ConcurrentHashMap<Player.Listener, Masked>()

        /**
         * A listener whose metadata events carry [getMediaMetadata] rather than ExoPlayer's, and
         * which passes everything else through untouched.
         *
         * Every method is forwarded by hand. They are all Java default methods, and Kotlin's `by`
         * delegation does not forward those: a delegating wrapper compiles, but everything but
         * the one override lands on the interface's empty default, and the session never hears
         * that the player is playing, has moved, or can seek.
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        private inner class Masked(private val inner: Player.Listener) : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
                inner.onMediaMetadataChanged(this@AnonymousPlayer.mediaMetadata)

            override fun onEvents(player: Player, events: Player.Events) = inner.onEvents(player, events)
            override fun onTimelineChanged(timeline: Timeline, reason: Int) =
                inner.onTimelineChanged(timeline, reason)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                inner.onMediaItemTransition(mediaItem, reason)
            override fun onTracksChanged(tracks: Tracks) = inner.onTracksChanged(tracks)
            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
                inner.onPlaylistMetadataChanged(mediaMetadata)
            override fun onIsLoadingChanged(isLoading: Boolean) = inner.onIsLoadingChanged(isLoading)
            override fun onLoadingChanged(isLoading: Boolean) = inner.onLoadingChanged(isLoading)
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
                inner.onAvailableCommandsChanged(availableCommands)
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
                inner.onTrackSelectionParametersChanged(parameters)
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
                inner.onPlayerStateChanged(playWhenReady, playbackState)
            override fun onPlaybackStateChanged(playbackState: Int) =
                inner.onPlaybackStateChanged(playbackState)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                inner.onPlayWhenReadyChanged(playWhenReady, reason)
            override fun onPlaybackSuppressionReasonChanged(reason: Int) =
                inner.onPlaybackSuppressionReasonChanged(reason)
            override fun onIsPlayingChanged(isPlaying: Boolean) = inner.onIsPlayingChanged(isPlaying)
            override fun onRepeatModeChanged(repeatMode: Int) = inner.onRepeatModeChanged(repeatMode)
            override fun onShuffleModeEnabledChanged(enabled: Boolean) =
                inner.onShuffleModeEnabledChanged(enabled)
            override fun onPlayerError(error: PlaybackException) = inner.onPlayerError(error)
            override fun onPlayerErrorChanged(error: PlaybackException?) = inner.onPlayerErrorChanged(error)
            override fun onPositionDiscontinuity(reason: Int) = inner.onPositionDiscontinuity(reason)
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = inner.onPositionDiscontinuity(oldPosition, newPosition, reason)
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) =
                inner.onPlaybackParametersChanged(parameters)
            override fun onSeekBackIncrementChanged(ms: Long) = inner.onSeekBackIncrementChanged(ms)
            override fun onSeekForwardIncrementChanged(ms: Long) = inner.onSeekForwardIncrementChanged(ms)
            override fun onMaxSeekToPreviousPositionChanged(ms: Long) =
                inner.onMaxSeekToPreviousPositionChanged(ms)
            override fun onAudioSessionIdChanged(id: Int) = inner.onAudioSessionIdChanged(id)
            override fun onAudioAttributesChanged(attributes: AudioAttributes) =
                inner.onAudioAttributesChanged(attributes)
            override fun onVolumeChanged(volume: Float) = inner.onVolumeChanged(volume)
            override fun onSkipSilenceEnabledChanged(enabled: Boolean) =
                inner.onSkipSilenceEnabledChanged(enabled)
            override fun onDeviceInfoChanged(info: DeviceInfo) = inner.onDeviceInfoChanged(info)
            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
                inner.onDeviceVolumeChanged(volume, muted)
            override fun onVideoSizeChanged(size: VideoSize) = inner.onVideoSizeChanged(size)
            override fun onSurfaceSizeChanged(width: Int, height: Int) =
                inner.onSurfaceSizeChanged(width, height)
            override fun onRenderedFirstFrame() = inner.onRenderedFirstFrame()
            override fun onCues(cues: List<Cue>) = inner.onCues(cues)
            override fun onCues(cueGroup: CueGroup) = inner.onCues(cueGroup)
            override fun onMetadata(metadata: Metadata) = inner.onMetadata(metadata)
        }

        override fun addListener(listener: Player.Listener) {
            val masked = Masked(listener)
            if (listeners.putIfAbsent(listener, masked) == null) super.addListener(masked)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)?.let { super.removeListener(it) }
        }

        /** Tells the session that what [getMediaMetadata] answers has changed. */
        fun metadataChanged() {
            val metadata = mediaMetadata
            listeners.values.forEach { it.onMediaMetadataChanged(metadata) }
        }

        override fun getMediaMetadata(): MediaMetadata {
            reveal()?.let { return it }
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
        const val TAG = "PlaybackService"

        const val DUCKED_VOLUME = 0.18f

        /** How often the resume point is written down while the music is running. */
        const val RESUME_SAVE_MS = 5_000L
    }
}

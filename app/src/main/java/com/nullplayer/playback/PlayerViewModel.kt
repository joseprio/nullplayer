package com.nullplayer.playback

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nullplayer.R
import com.nullplayer.data.AppSettings
import com.nullplayer.data.Settings
import com.nullplayer.data.Track
import com.nullplayer.data.Group
import com.nullplayer.data.GroupSummary
import com.nullplayer.data.VaultFiles
import com.nullplayer.data.VaultRepository
import com.nullplayer.security.AppLock
import com.nullplayer.security.Biometrics
import com.nullplayer.web.WebServerController
import com.nullplayer.web.WebServerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isSpeaking: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val groups: List<GroupSummary> = emptyList(),
    /** The tile whose contents are on screen, which need not be the one playing. Blank = vault. */
    val browseGroupId: String = "",
    val browseTracks: List<Track> = emptyList(),
    /** The groups every currently selected track already belongs to. */
    val sharedGroupIds: Set<String> = emptySet(),
    /** How much is in the vault as a whole, for the ribbon's first tile. */
    val vaultCount: Int = 0,
    /** Position in the queue, or -1 before anything has been chosen. */
    val trackIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 0,
    val maxVolume: Int = 1,
    /** Milliseconds until the sleep timer fires, or null when it is disarmed. */
    val sleepTimerMs: Long? = null,
    val voices: List<VoiceOption> = emptyList(),
    /** Fixed now that the bands are ours rather than the device's. */
    val equalizerSpec: EqualizerSpec = AudioEffects.spec,
    val equalizerLevels: List<Int> = emptyList(),
    /** What the engine is actually running, whichever source defined it. */
    val equalizerCurve: ParametricEq = ParametricEq(),
    /** Why the last pasted config was refused, or null if it was fine. */
    val autoEqError: String? = null,
    val importsInFlight: Int = 0,
    val lastImportFailures: Int = 0,
    val settings: AppSettings = AppSettings(),
    /** False until the stored settings have been read once, so the lock cannot flash past. */
    val settingsLoaded: Boolean = false,
    val outputs: List<AudioOutput> = emptyList(),
    /** False while a required output device is missing, which is enough to refuse play. */
    val outputSatisfied: Boolean = true,
    /** What the user asked to have connected, named the way they chose it. */
    val requiredOutputLabel: String = "headphones",
    val biometricsAvailable: Boolean = false,
    val biometricsUnavailableReason: String? = null,
    /** The UI owes the user a biometric prompt before the music can start. */
    val awaitingPlayAuth: Boolean = false,
    /** A short-lived line explaining why something did not happen. */
    val notice: String? = null,
    val web: WebServerState = WebServerState(),
) {
    val hasTracks: Boolean get() = tracks.isNotEmpty()
    val isImporting: Boolean get() = importsInFlight > 0

    /**
     * The tile the ribbon has selected. Blank means the whole vault, which is also the fallback
     * when a group has been deleted out from under the selection.
     */
    val activeGroupId: String
        get() = settings.activeGroupId.takeIf { id -> groups.any { it.id == id } }.orEmpty()

    val browseGroup: GroupSummary? get() = groups.firstOrNull { it.id == browseGroupId }

    /** The name at the top of whichever list is open. */
    val browseName: String get() = browseGroup?.name ?: Group.VAULT_NAME

    val browseColor: Int get() = browseGroup?.colorArgb ?: Group.VAULT_COLOR

    /** The tile the queue is drawn from, which need not be the one being browsed. */
    val activeName: String
        get() = groups.firstOrNull { it.id == activeGroupId }?.name ?: Group.VAULT_NAME

    /**
     * Why pressing play would be refused, or null if it would not.
     *
     * The biometric gate is deliberately absent. Pressing play is what raises that prompt, so a
     * button greyed out for it would be a button that can never let anyone in. Pausing is never
     * refused either, which is why a running player answers null whatever else is true.
     */
    val playRefusal: String?
        get() = when {
            isPlaying -> null
            !hasTracks -> if (activeGroupId.isEmpty()) {
                "The vault is empty. Add music to start."
            } else {
                "$activeName is empty."
            }
            !outputSatisfied -> "Connect $requiredOutputLabel to play."
            else -> null
        }
    val shuffle: Boolean get() = settings.shuffle
    val repeat: RepeatMode get() = RepeatMode.ofOrdinal(settings.repeatOrdinal)

    /** How far through the track, 0..1. Zero while the duration is still unknown. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val volumeFraction: Float get() = if (maxVolume > 0) volume.toFloat() / maxVolume else 0f
}

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VaultRepository(application)
    private val settings = Settings(application)
    // Uploads land in the vault, tagged into whichever group is open.
    private val webServer = WebServerController(
        context = application,
        repository = repository,
        activeVaultId = { _state.value.browseGroupId },
        onIntrusion = ::onWebServerIntrusion,
    )
    private val audioManager = application.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var noticeJob: Job? = null
    private var tickJob: Job? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeBeforeMute = 0

    /** The tile the live queue was built from, so a switch can be told from an edit to one list. */
    private var queuedGroupId: String? = null

    /** The title handed to the media session. Never a track name. */
    private val anonymousMetadata = MediaMetadata.Builder()
        .setTitle(application.getString(R.string.app_name))
        .build()

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == VoiceCommands.SPEAKING_CHANGED) {
                val speaking = args.getBoolean(VoiceCommands.EXTRA_SPEAKING)
                _state.update { it.copy(isSpeaking = speaking) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = readPlayback()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = readPosition()

        override fun onPlaybackStateChanged(playbackState: Int) {
            readPlayback()
            readPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = readPosition()
    }

    init {
        connectToService()
        viewModelScope.launch {
            repository.observeGroups().collect { groups ->
                _state.update { it.copy(groups = groups) }
            }
        }
        viewModelScope.launch {
            // The vault tile counts everything, whatever tile happens to be selected.
            repository.observeTracks(Group.VAULT_ID).collect { all ->
                _state.update { it.copy(vaultCount = all.size) }
            }
        }
        viewModelScope.launch {
            // Only the selected tile is loaded. Switching tiles is a different queue, not a filter
            // over one big one, which is what keeps shuffle and "track 4 of 96" honest.
            _state
                .map { it.activeGroupId }
                .distinctUntilChanged()
                .flatMapLatest { groupId ->
                    repository.observeTracks(groupId).map { groupId to it }
                }
                .collect { (groupId, tracks) ->
                    // The very first list is not a switch — nothing was playing to carry over.
                    val switched = queuedGroupId != null && groupId != queuedGroupId
                    queuedGroupId = groupId
                    _state.update { it.copy(tracks = tracks) }
                    syncQueue(tracks, switched)
                }
        }
        viewModelScope.launch {
            _state
                .map { it.browseGroupId }
                .distinctUntilChanged()
                .flatMapLatest { groupId -> repository.observeTracks(groupId) }
                .collect { tracks -> _state.update { it.copy(browseTracks = tracks) } }
        }
        viewModelScope.launch {
            webServer.state.collect { web -> _state.update { it.copy(web = web) } }
        }
        viewModelScope.launch {
            settings.all.collect { config ->
                _state.update { it.copy(settings = config, settingsLoaded = true) }
            }
        }
        viewModelScope.launch {
            // The stored preference is the source of truth, so the server comes back after a
            // restart if the user left it switched on. Only a change to *this* preference may
            // touch it: restarting the server on an unrelated edit would throw away the address,
            // the PIN, and any error worth reading.
            settings.all
                .map { it.webServerEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) webServer.start() else webServer.stop() }
        }
        viewModelScope.launch {
            PlaybackGate.state.collect { gate ->
                _state.update {
                    it.copy(
                        outputs = gate.outputs,
                        outputSatisfied = gate.outputSatisfied,
                        requiredOutputLabel = gate.requiredOutputLabel,
                    )
                }
            }
        }
        viewModelScope.launch {
            Voices.available.collect { voices -> _state.update { it.copy(voices = voices) } }
        }
        viewModelScope.launch {
            // What the engine actually holds, not what was asked for: picking a preset moves the
            // faders, and only the engine knows where to.
            AudioEffects.levels.collect { levels ->
                _state.update { it.copy(equalizerLevels = levels) }
            }
        }
        viewModelScope.launch {
            AudioEffects.curve.collect { curve ->
                _state.update { it.copy(equalizerCurve = curve) }
            }
        }
        readVolume()
        observeVolume()
        viewModelScope.launch {
            // A play that was refused somewhere the app cannot raise a prompt — the notification,
            // a car head unit — still gets explained the next time the user looks at the screen.
            PlaybackGate.blocked.collect { block ->
                when (block) {
                    PlaybackGate.Block.OUTPUT_DEVICE -> notify(outputRequirementMessage())
                    PlaybackGate.Block.BIOMETRIC -> notify("Press play in the app to unlock.")
                    null -> return@collect
                }
                PlaybackGate.consumeBlock()
            }
        }
        refreshBiometrics()
    }

    /** Enrolment can change while the app is open, so this is re-read on every resume. */
    fun refreshBiometrics() {
        val context = getApplication<Application>()
        _state.update {
            it.copy(
                biometricsAvailable = Biometrics.available(context),
                biometricsUnavailableReason = Biometrics.unavailableReason(context),
            )
        }
    }

    private fun connectToService() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(controllerListener)
            .buildAsync()
        future.addListener({
            controller = future.get().apply {
                addListener(playerListener)
                shuffleModeEnabled = _state.value.settings.shuffle
                repeatMode = _state.value.repeat.playerValue
            }
            readPlayback()
            syncQueue(_state.value.tracks, switched = false)
            readPosition()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Rebuilds the queue.
     *
     * [switched] means the ribbon moved to another tile, and it is the only case allowed to choose
     * a new starting track. An edit to the list already playing — a track added, or the playing
     * one deleted — deliberately does not: starting something else because a file went away would
     * be a worse surprise than stopping.
     */
    private fun syncQueue(tracks: List<Track>, switched: Boolean) {
        val player = controller ?: return

        val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val wanted = tracks.map { it.id }
        if (existing == wanted) return

        val playingId = player.currentMediaItem?.mediaId
        val resumePosition = player.currentPosition
        val wasPlaying = player.playWhenReady

        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(VaultFiles.uriFor(track.id))
                .setMediaMetadata(anonymousMetadata)
                .build()
        }

        val carriedOver = tracks.indexOfFirst { it.id == playingId }
        val start = when {
            // The track came along with the switch, so it keeps its place and its position.
            carriedOver >= 0 -> carriedOver
            items.isEmpty() || !switched -> null
            // Shuffle is honoured here rather than left to the player: the player's shuffle order
            // decides what comes *next*, not where a queue it has only just been handed begins.
            _state.value.shuffle -> items.indices.random()
            else -> 0
        }

        if (start == null) {
            player.setMediaItems(items)
        } else {
            player.setMediaItems(items, start, if (carriedOver >= 0) resumePosition else 0L)
        }
        player.prepare()
        // Playing carries across a switch whenever there is anything to play. A tile that happens
        // not to hold the current track is still a tile the user just chose, and falling silent
        // reads as a fault rather than as an answer.
        player.playWhenReady =
            wasPlaying && (carriedOver >= 0 || (switched && items.isNotEmpty()))
    }

    // -- Transport --------------------------------------------------------------------------

    fun togglePlay() {
        val player = controller ?: return
        // The state's own answer rather than the player's, so a press during a track change does
        // what the pause icon under the finger promises.
        if (_state.value.isPlaying) {
            player.pause()
            return
        }
        // The button is greyed for these, but it still takes the press: being told what to fix is
        // more use than a control that does nothing and does not say why.
        _state.value.playRefusal?.let {
            notify(it)
            return
        }
        if (player.mediaItemCount == 0) return
        start(player)
    }

    /**
     * Everything that starts the music goes through here.
     *
     * [PlaybackGate] would refuse a blocked play on its own, but asking it first is what turns a
     * silent refusal into either an explanation or a prompt the user can actually answer.
     */
    private fun start(player: MediaController) {
        when (PlaybackGate.blockReason()) {
            PlaybackGate.Block.OUTPUT_DEVICE -> {
                notify(outputRequirementMessage())
                return
            }
            PlaybackGate.Block.BIOMETRIC -> {
                _state.update { it.copy(awaitingPlayAuth = true) }
                return
            }
            null -> Unit
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    /** The biometric prompt said yes. The unlock lasts until playback next stops. */
    fun onPlayAuthorized() {
        _state.update { it.copy(awaitingPlayAuth = false) }
        PlaybackGate.unlockPlayback()
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        start(player)
    }

    fun onPlayAuthCancelled(message: String?) {
        _state.update { it.copy(awaitingPlayAuth = false) }
        if (message != null) notify(message)
    }

    /** The library stayed shut. The player is still on screen, so say why on it. */
    fun onVaultAuthCancelled(message: String?) {
        if (message != null) notify(message)
    }

    private fun outputRequirementMessage(): String =
        "Connect ${PlaybackGate.state.value.requiredOutputLabel} to play."

    /** A line that shows itself for a few seconds and then gets out of the way. */
    private fun notify(message: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = message) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    /**
     * Start one particular track, picked off the vault screen.
     *
     * The queue is whatever tile the ribbon points at, not the one being browsed, so playing from
     * a list the ribbon is not on has to move the ribbon first — otherwise the track would start
     * from a queue it is not a member of and "track 4 of 96" would be a lie. Switching tiles goes
     * through settings and comes back as a flow, so the queue is waited for rather than assumed.
     */
    fun playTrack(track: Track) {
        viewModelScope.launch {
            val browsing = _state.value.browseGroupId
            if (browsing != _state.value.activeGroupId) {
                settings.setActiveGroup(browsing)
                withTimeoutOrNull(QUEUE_SWITCH_MS) {
                    _state.first { snapshot -> snapshot.tracks.any { it.id == track.id } }
                }
            }
            val player = controller ?: return@launch
            val index = (0 until player.mediaItemCount)
                .firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
                ?: return@launch
            player.seekTo(index, 0L)
            start(player)
            readPosition()
        }
    }

    fun next() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
        readPosition()
    }

    /** Restarts the current track first, then steps back — the usual double-press behaviour. */
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > RESTART_THRESHOLD_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
        readPosition()
    }

    /** Held skip buttons rewind and fast-forward rather than stepping through the queue. */
    fun scrub(deltaMs: Long) {
        val player = controller ?: return
        val duration = player.duration
        val upperBound = if (duration > 0) duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, upperBound))
        readPosition()
    }

    /** Dragged on the progress bar, as a fraction of the track. */
    fun seekToFraction(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration
        if (duration <= 0) return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        readPosition()
    }

    // -- Modes ------------------------------------------------------------------------------

    fun toggleShuffle() {
        val next = !_state.value.settings.shuffle
        controller?.shuffleModeEnabled = next
        viewModelScope.launch { settings.setShuffle(next) }
    }

    /** Swaps the seeker's right-hand label between time left and track length. */
    fun toggleTimeMode() {
        val next = !_state.value.settings.showRemainingTime
        viewModelScope.launch { settings.setShowRemainingTime(next) }
    }

    fun cycleRepeat() {
        val next = _state.value.repeat.next()
        controller?.repeatMode = next.playerValue
        viewModelScope.launch { settings.setRepeatOrdinal(next.ordinal) }
    }

    // -- Volume -----------------------------------------------------------------------------

    fun setVolume(level: Int) {
        if (level > 0) volumeBeforeMute = 0
        // No FLAG_SHOW_UI here: the slider under the user's thumb is the feedback.
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                level.coerceIn(0, _state.value.maxVolume),
                0,
            )
        }
        readVolume()
    }

    /**
     * Mute and back again.
     *
     * The level before muting is remembered so unmuting lands where it was, and forgotten as soon
     * as the slider moves — otherwise unmuting would undo a deliberate change.
     */
    fun toggleMute() {
        val current = _state.value.volume
        if (current > 0) {
            volumeBeforeMute = current
            setVolume(0)
        } else {
            val restored = volumeBeforeMute.takeIf { it > 0 }
                ?: (_state.value.maxVolume / 3).coerceAtLeast(1)
            setVolume(restored)
        }
    }

    private fun readVolume() {
        _state.update {
            it.copy(
                volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    .coerceAtLeast(1),
            )
        }
    }

    /**
     * Volume can also move under us — the hardware keys, another app, a Bluetooth remote — and a
     * slider showing a stale number is worse than no slider. There is no public broadcast for
     * this, so the settings provider is the supported way to hear about it.
     */
    private fun observeVolume() {
        val resolver = getApplication<Application>().contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = readVolume()
        }
        resolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
    }

    // -- Sleep timer ------------------------------------------------------------------------

    /** Pass null to disarm. [PlaybackService] does the pausing when the deadline arrives. */
    fun setSleepTimer(minutes: Int?) {
        if (minutes == null) SleepTimer.cancel() else SleepTimer.arm(minutes)
        _state.update { it.copy(sleepTimerMs = SleepTimer.remaining()) }
        if (minutes != null) startTicking()
    }

    // -- Equalizer --------------------------------------------------------------------------

    fun setEqualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setEqualizerEnabled(enabled) }
    }

    fun setEqualizerPreset(preset: Int) {
        viewModelScope.launch { settings.setEqualizerPreset(preset) }
    }

    /**
     * Moving one fader takes the equalizer off its preset, because the curve on screen is no
     * longer the one the preset named. The untouched bands keep the levels the engine currently
     * holds, which are the ones the user can see.
     */
    fun setEqualizerBand(band: Int, millibel: Int) {
        val spec = _state.value.equalizerSpec
        val current = _state.value.equalizerLevels
        val levels = List(spec.bandCount) { index ->
            if (index == band) millibel else current.getOrElse(index) { 0 }
        }
        viewModelScope.launch {
            settings.setEqualizerPreset(-1)
            settings.setEqualizerBands(levels)
        }
    }

    fun resetEqualizer() {
        val bands = _state.value.equalizerSpec.bandCount
        viewModelScope.launch {
            settings.setEqualizerPreset(-1)
            settings.setEqualizerBands(List(bands) { 0 })
        }
    }

    /**
     * Loads a pasted AutoEQ profile.
     *
     * Parsed here rather than on the way out of storage so a bad paste can be refused while the
     * user is still looking at it — and so a config that does not parse never displaces the curve
     * that is currently playing.
     */
    fun setAutoEq(config: String) {
        if (config.isBlank()) {
            clearAutoEq()
            return
        }
        AutoEqParser.parse(config)
            .onSuccess {
                _state.update { state -> state.copy(autoEqError = null) }
                viewModelScope.launch {
                    settings.setEqualizerAutoEq(config)
                    settings.setEqualizerEnabled(true)
                }
            }
            .onFailure { error ->
                _state.update { state ->
                    state.copy(autoEqError = error.message ?: "That config could not be read.")
                }
            }
    }

    /** Drops the profile and hands the curve back to the faders. */
    fun clearAutoEq() {
        _state.update { it.copy(autoEqError = null) }
        viewModelScope.launch { settings.setEqualizerAutoEq("") }
    }

    fun dismissAutoEqError() {
        _state.update { it.copy(autoEqError = null) }
    }

    // -- The ticker -------------------------------------------------------------------------

    /**
     * One coroutine drives both the progress bar and the sleep-timer countdown, and stops itself
     * once neither is moving. A player that polls a paused position forever is a battery drain
     * with a tidy UI.
     */
    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (true) {
                readPosition()
                val current = _state.value
                if (!current.isPlaying && current.sleepTimerMs == null) return@launch
                delay(TICK_MS)
            }
        }
    }

    /**
     * Whether the player is *meant* to be playing, which is what the button reports.
     *
     * `Player.isPlaying` is false for as long as a track change spends buffering, so a button
     * driven off it flashed the play triangle on every skip before settling back on pause. What
     * the user asked for does not blink: `playWhenReady` stays true across the gap. The two
     * answers only genuinely part company at the end of the queue, where nothing is going to
     * start however willing the player is, so a finished queue counts as stopped.
     *
     * Audio focus lost to a call or a notification is deliberately not counted either. Playback
     * is suppressed rather than stopped, it resumes by itself, and a button that flipped to play
     * and back for a ducked notification would be the same flicker in a different costume.
     */
    private fun readPlayback() {
        val player = controller
        val intending = player != null &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_ENDED
        _state.update { if (it.isPlaying == intending) it else it.copy(isPlaying = intending) }
        if (intending) startTicking()
    }

    private fun readPosition() {
        val player = controller
        _state.update {
            it.copy(
                trackIndex = player?.currentMediaItemIndex ?: -1,
                positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                durationMs = player?.duration?.takeIf { duration -> duration > 0 } ?: 0L,
                sleepTimerMs = SleepTimer.remaining(),
            )
        }
    }

    // -- VoiceOver --------------------------------------------------------------------------

    /** Toggles: pressing it while it is talking stops it, same as the notification button. */
    fun announceCurrentTrack() = announce(VoiceCommands.SPEAK_TRACK)

    fun announceQueuePosition() = announce(VoiceCommands.SPEAK_POSITION)

    fun announce(track: Track) = announce(
        VoiceCommands.SPEAK_TRACK,
        Bundle().apply { putString(VoiceCommands.EXTRA_TRACK_ID, track.id) },
    )

    /**
     * Speaks, and says on screen when it cannot be heard.
     *
     * Text-to-speech rides the media stream, so muting the music mutes VoiceOver with it. Since
     * VoiceOver is the only way this app will tell you what a track is, a press that produces
     * neither sound nor explanation is the worst thing it could do — the command still goes out,
     * in case the audio is routed somewhere that can be heard, but the screen says why it might
     * not be.
     */
    private fun announce(action: String, args: Bundle = Bundle.EMPTY) {
        if (_state.value.volume == 0) notify("Media volume is muted.")
        sendVoiceCommand(action, args)
    }

    private fun sendVoiceCommand(action: String, args: Bundle = Bundle.EMPTY) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    // -- The dock ---------------------------------------------------------------------------

    fun importAll(sources: List<Uri>, removeOriginals: Boolean) {
        importInto(sources, removeOriginals, announce = false)
    }

    /**
     * Audio handed to us by another app, through a share or an open.
     *
     * It lands on the selected vault and says so, because the user was looking at a different app
     * when they sent it and has no other way to find out where it went.
     */
    fun importShared(sources: List<Uri>) {
        if (sources.isEmpty()) return
        importInto(sources, removeOriginals = false, announce = true)
    }

    private fun importInto(sources: List<Uri>, removeOriginals: Boolean, announce: Boolean) {
        if (sources.isEmpty()) return
        _state.update { it.copy(importsInFlight = it.importsInFlight + sources.size) }
        viewModelScope.launch {
            // A share that cold-starts the app arrives before the vault list does, so the shelf is
            // resolved here rather than at the call site — dropping the file because a flow had
            // not emitted yet would lose it with nothing on screen to say so.
            // A share that cold-starts the app arrives before anything has been opened, so it
            // lands in the vault untagged rather than nowhere at all.
            val groupId = _state.value.browseGroupId
            val destination = _state.value.groups.firstOrNull { it.id == groupId }?.name
                ?: Group.VAULT_NAME

            var failures = 0
            for (source in sources) {
                val result = repository.import(
                    source = source,
                    groupIds = listOfNotNull(groupId.takeIf { it.isNotEmpty() }),
                    removeOriginal = removeOriginals,
                )
                if (result.isFailure) failures++
                _state.update { it.copy(importsInFlight = it.importsInFlight - 1) }
            }
            _state.update { it.copy(lastImportFailures = failures) }
            if (announce) {
                val added = sources.size - failures
                notify(
                    when {
                        added == 0 -> "Nothing could be read as audio."
                        failures == 0 && added == 1 -> "Added to $destination."
                        failures == 0 -> "Added $added to $destination."
                        else -> "Added $added to $destination, $failures could not be read."
                    }
                )
            }
        }
    }

    // -- Groups -----------------------------------------------------------------------------

    /** Opens a list. Blank is the whole vault. Does not change what is playing. */
    fun openGroup(id: String) {
        _state.update { it.copy(browseGroupId = id) }
    }

    /** The ribbon. Blank is the whole vault. */
    fun selectGroup(id: String) {
        if (id == _state.value.activeGroupId) return
        viewModelScope.launch { settings.setActiveGroup(id) }
    }

    /** Creates a group and opens it, which is what anyone making one means to do. */
    /**
     * Makes a group and leaves it where it lands.
     *
     * The browse pointer deliberately stays put: it is what an upload targets, and moving it to a
     * group the user is not looking at would file the next upload somewhere they never chose.
     */
    fun createGroup() {
        viewModelScope.launch { repository.createGroup() }
    }

    fun updateGroup(id: String, name: String, colorArgb: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameGroup(id, trimmed, colorArgb) }
    }

    /** Unfiles the tracks and drops the tag. Nothing leaves the vault. */
    fun deleteGroup(id: String) {
        viewModelScope.launch {
            repository.deleteGroup(id)
            if (_state.value.browseGroupId == id) {
                _state.update { it.copy(browseGroupId = Group.VAULT_ID) }
            }
        }
    }

    /** Reads which groups a selection already shares, so the tag sheet can show it. */
    fun readSharedGroups(tracks: List<Track>) {
        viewModelScope.launch {
            val shared = repository.groupsSharedBy(tracks.map { it.id })
            _state.update { it.copy(sharedGroupIds = shared) }
        }
    }

    fun setTag(tracks: List<Track>, groupId: String, tagged: Boolean) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            repository.setTag(tracks.map { it.id }, groupId, tagged)
            val shared = repository.groupsSharedBy(tracks.map { it.id })
            _state.update { it.copy(sharedGroupIds = shared) }
        }
    }

    fun setShowSeeker(show: Boolean) {
        viewModelScope.launch { settings.setShowSeeker(show) }
    }

    fun setShowVaultCounts(show: Boolean) {
        viewModelScope.launch { settings.setShowVaultCounts(show) }
    }

    fun setVoice(name: String) {
        viewModelScope.launch { settings.setVoiceName(name) }
    }

    fun markNotificationsAsked() {
        viewModelScope.launch { settings.setAskedForNotifications() }
    }

    /**
     * Deletes one or many. The queue rebuilds itself from the vault, so nothing here has to touch
     * the player — [syncQueue] keeps whatever is still playing where it was.
     */
    fun delete(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch { tracks.forEach { repository.delete(it) } }
    }

    // -- Settings ---------------------------------------------------------------------------

    fun setWebServerEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setWebServerEnabled(enabled) }
    }

    fun setDeleteOriginals(delete: Boolean) {
        viewModelScope.launch { settings.setDeleteOriginals(delete) }
    }

    fun toggleWebServer() = setWebServerEnabled(!_state.value.settings.webServerEnabled)

    /**
     * The server shut itself off after too many wrong PINs.
     *
     * The stored preference has to follow it down, or the collector that keeps the two in step
     * would start it straight back up — with the guesser still there. Arrives on a request thread,
     * so everything it touches is hopped onto the ViewModel's scope.
     */
    private fun onWebServerIntrusion() {
        viewModelScope.launch {
            settings.setWebServerEnabled(false)
            notify("Web management stopped: too many wrong PINs.")
        }
    }

    fun setLockOnLaunch(lock: Boolean) {
        viewModelScope.launch { settings.setLockOnLaunch(lock) }
        // The user is already inside the app; challenging them the instant they flip the switch
        // would only interrupt the settings screen they are still using. The lock takes hold on
        // the next launch, and on the next return from the background.
        if (lock) AppLock.unlock()
    }

    fun setLockOnPlay(lock: Boolean) {
        viewModelScope.launch { settings.setLockOnPlay(lock) }
        // Turning it on mid-session must not leave an unlock lying around from before.
        if (lock) PlaybackGate.relockPlayback()
    }

    fun setLockOnDock(lock: Boolean) {
        viewModelScope.launch { settings.setLockOnDock(lock) }
        // The switch is thrown from inside settings, one step from the dock itself, so the user
        // has effectively just passed whatever check they are turning on.
        if (lock) AppLock.unlockVault()
    }

    fun setRequireOutputDevice(require: Boolean) {
        viewModelScope.launch { settings.setRequireOutputDevice(require) }
    }

    fun setRequiredDevice(key: String) {
        viewModelScope.launch { settings.setRequiredDeviceKey(key) }
    }

    fun setPreferredDevice(key: String) {
        viewModelScope.launch { settings.setPreferredDeviceKey(key) }
    }

    override fun onCleared() {
        volumeObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        webServer.stop()
        super.onCleared()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val NOTICE_MS = 4_000L
        const val TICK_MS = 500L
        const val QUEUE_SWITCH_MS = 2_000L
    }
}

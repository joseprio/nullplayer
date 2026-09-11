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
import com.nullplayer.data.TileModes
import com.nullplayer.data.Track
import com.nullplayer.data.TrackOrder
import com.nullplayer.data.ordered
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How many favourites it takes before the tile is offered.
 *
 * The tile stands as soon as anything is hearted. It is hidden only while nothing is, because a
 * tile that could never be anything but empty is a tile worth nobody's swipe.
 */
private const val FAVORITES_MINIMUM = 0

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isSpeaking: Boolean = false,
    /** What the playing file turned out to be, once the decoder has been handed it. */
    val audioProfile: AudioProfile? = null,
    val tracks: List<Track> = emptyList(),
    val groups: List<GroupSummary> = emptyList(),
    /** The tile whose contents are on screen, which need not be the one playing. Blank = vault. */
    val browseGroupId: String = "",
    val browseTracks: List<Track> = emptyList(),
    /** The groups every currently selected track already belongs to. */
    val sharedGroupIds: Set<String> = emptySet(),
    /** How many tracks are hearted. The Favorites tile stands or falls on this alone. */
    val favoriteCount: Int = 0,
    /** How much is in the vault as a whole, for the ribbon's first tile. */
    val vaultCount: Int = 0,
    /** Position in the queue, or -1 before anything has been chosen. */
    val trackIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** The player is waiting on the file rather than playing it. */
    val isBuffering: Boolean = false,
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
    /** How many tracks volume normalisation has yet to measure. */
    val unmeasuredTracks: Int = 0,
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

    /** Whether the Favorites tile is standing. Everything that names it has to ask first. */
    val hasFavorites: Boolean get() = favoriteCount > FAVORITES_MINIMUM

    /**
     * The tile the queue is drawn from.
     *
     * A stored id is only honoured while the tile it names still exists — a group can be deleted,
     * and Favorites can fall below its threshold, and either would otherwise leave the ribbon
     * pointing at nothing.
     */
    val activeGroupId: String
        get() = settings.activeGroupId.takeIf { id -> tileExists(id) }.orEmpty()

    private fun tileExists(id: String): Boolean = when (id) {
        Group.VAULT_ID -> true
        Group.FAVORITES_ID -> hasFavorites
        else -> groups.any { it.id == id }
    }

    /** What a tile is called, whether or not it has a row of its own. */
    fun tileName(id: String): String = when (id) {
        Group.FAVORITES_ID -> Group.FAVORITES_NAME
        else -> groups.firstOrNull { it.id == id }?.name ?: Group.VAULT_NAME
    }

    fun tileColor(id: String): Int = when (id) {
        Group.FAVORITES_ID -> Group.FAVORITES_COLOR
        else -> groups.firstOrNull { it.id == id }?.colorArgb ?: Group.VAULT_COLOR
    }

    val browseGroup: GroupSummary? get() = groups.firstOrNull { it.id == browseGroupId }

    /** The name at the top of whichever list is open. */
    val browseName: String get() = tileName(browseGroupId)

    val browseColor: Int get() = tileColor(browseGroupId)

    /** The tile the queue is drawn from, which need not be the one being browsed. */
    val activeName: String get() = tileName(activeGroupId)

    /** Its colour, so the mini player's pill matches the ribbon chip it stands for. */
    val activeColor: Int get() = tileColor(activeGroupId)

    /**
     * Whether the track playing is hearted.
     *
     * Read off the queue rather than kept as its own field, so the heart on the player and the
     * heart on that track's row in the dock can never disagree — both are the same row.
     */
    val currentIsFavorite: Boolean
        get() = currentTrack?.favorite == true

    /**
     * The track the queue is standing on, or null when there is nothing to stand on.
     *
     * Read off the queue for the same reason [currentIsFavorite] is: one row, and everything that
     * asks about the current track is asking about that row rather than about a copy of it.
     */
    val currentTrack: Track?
        get() = tracks.getOrNull(trackIndex)

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

    /**
     * Why pressing VoiceOver would be refused, or null if it would not.
     *
     * The same output rule as [playRefusal], for the same reason: VoiceOver is the only thing here
     * that says a track's name aloud, and out of the phone's own speaker it would say it to the
     * room. An empty queue is not a refusal — VoiceOver answers that with what the vault holds,
     * which is exactly the moment it is worth asking. Stopping it is never refused either, so a
     * press while it is talking answers null however the outputs stand.
     */
    val voiceOverRefusal: String?
        get() = when {
            isSpeaking -> null
            !outputSatisfied -> "Connect $requiredOutputLabel for VoiceOver."
            else -> null
        }
    /** The buttons answer for the tile the ribbon is on, which is the queue they would change. */
    val modes: TileModes get() = settings.modesFor(activeGroupId)
    val shuffle: Boolean get() = modes.shuffle
    val repeat: RepeatMode get() = RepeatMode.ofOrdinal(modes.repeatOrdinal)

    /** How the queue on screen is laid out, and whether it runs backwards. */
    val order: TrackOrder get() = modes.order
    val orderReversed: Boolean get() = modes.reversed

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
    private val loudness = LoudnessScanner(repository)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var noticeJob: Job? = null
    private var tickJob: Job? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeBeforeMute = 0

    /** The tile the live queue was built from, so a switch can be told from an edit to one list. */
    private var queuedGroupId: String? = null

    /**
     * Where the last session had got to, spent on the first queue this one builds.
     *
     * Held here rather than read at the point of use because it is only ever true once: the moment
     * a queue exists, the player itself is the authority on where it is, and a stored position
     * from before would be an older answer to a question already settled.
     */
    private var pendingResume: ResumePoint? = null

    /**
     * Whether the app is actually on screen, as told by the Activity's own start and stop.
     *
     * Only the measuring sweep asks. Nothing else here cares where the user is looking, and this
     * is a ViewModel rather than a process-wide observer precisely because it dies with the
     * Activity that feeds it — a stale `true` would be worse than not knowing.
     */
    private val onScreen = MutableStateFlow(false)

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
            if (command.customAction == FormatCommands.PROFILE_CHANGED) {
                // An empty bundle is the service saying it has nothing to report, which reads back
                // as null and takes the line off the screen rather than leaving the last one up.
                val profile = AudioProfile.fromBundle(args)
                _state.update { it.copy(audioProfile = profile) }
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
            repository.observeFavoriteCount().collect { count ->
                _state.update { it.copy(favoriteCount = count) }
            }
        }
        viewModelScope.launch {
            // Only the selected tile is loaded. Switching tiles is a different queue, not a filter
            // over one big one, which is what keeps shuffle and "track 4 of 96" honest.
            //
            // Nothing is queued until the settings have been read once. Which tile is selected is
            // itself a stored setting, so building a queue before they land means building the
            // wrong one and correcting it — and the correction is a race the resume point loses:
            // the first queue would be handed to the player before there was anything to resume
            // into it.
            //
            // The tile's order travels with its id, so re-ordering rebuilds the queue exactly the
            // way switching tiles does. It is emphatically not a switch, though: the same tracks
            // are still in it, so the one playing keeps playing, from where it had got to, at
            // whatever number it now sits at. See [syncQueue].
            _state
                .filter { it.settingsLoaded }
                .map { Ordering(it.activeGroupId, it.order, it.orderReversed) }
                .distinctUntilChanged()
                .flatMapLatest { ordering ->
                    repository.observeTracks(ordering.groupId).map { ordering to it }
                }
                .collect { (ordering, listed) ->
                    val tracks = listed.ordered(ordering.order, ordering.reversed)
                    // The very first list is not a switch — nothing was playing to carry over.
                    val switched = queuedGroupId != null && ordering.groupId != queuedGroupId
                    queuedGroupId = ordering.groupId
                    _state.update { it.copy(tracks = tracks) }
                    syncQueue(tracks, switched)
                }
        }
        viewModelScope.launch {
            // Laid out the same way the queue would be, so the dock lists a group in the order it
            // plays it. A list that disagreed with the queue drawn from it would make "track 41"
            // mean two different things on two screens.
            _state
                .map { state ->
                    val modes = state.settings.modesFor(state.browseGroupId)
                    Ordering(state.browseGroupId, modes.order, modes.reversed)
                }
                .distinctUntilChanged()
                .flatMapLatest { ordering ->
                    repository.observeTracks(ordering.groupId).map { tracks ->
                        tracks.ordered(ordering.order, ordering.reversed)
                    }
                }
                .collect { tracks -> _state.update { it.copy(browseTracks = tracks) } }
        }
        viewModelScope.launch {
            // Shuffle and repeat belong to the tile, so moving the ribbon moves them too. Watched
            // rather than applied at the point of switching, because a tile's modes can also
            // change from the library screen, where no queue is being touched at all.
            _state
                .map { it.modes }
                .distinctUntilChanged()
                .collect { modes ->
                    controller?.shuffleModeEnabled = modes.shuffle
                    controller?.repeatMode = RepeatMode.ofOrdinal(modes.repeatOrdinal).playerValue
                }
        }
        viewModelScope.launch {
            webServer.state.collect { web -> _state.update { it.copy(web = web) } }
        }
        viewModelScope.launch {
            settings.all.collect { config ->
                // Only from the first snapshot: every later one is this session's own writing,
                // and taking a resume point from it would be reading back what is already playing.
                if (!_state.value.settingsLoaded && config.lastTrackId.isNotEmpty()) {
                    pendingResume = ResumePoint(config.lastTrackId, config.lastPositionMs)
                }
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
        viewModelScope.launch {
            loudness.remaining.collect { pending ->
                _state.update { it.copy(unmeasuredTracks = pending) }
            }
        }
        viewModelScope.launch {
            // The measuring sweep, gated on the setting: decoding a whole library in the
            // background is not something to do for a feature nobody has switched on, and the
            // vault backfills itself the moment somebody does.
            //
            // The pending count is narrowed to "is there any" before it reaches here. Left as a
            // number it would re-emit after every single track, and `collectLatest` would cancel
            // the very sweep that produced the change.
            //
            // The third term is what keeps the sweep out of the way. Measuring is decoding, and a
            // decode running against the decode that is feeding the speaker is a contest the
            // listener can hear — but only once the app is off screen, where the process is
            // scheduled on less than it had a moment ago. So the sweep runs whenever the app is
            // being looked at, and whenever nothing is playing, and pauses in the one case that
            // is neither. `collectLatest` stops it mid-track when that case arrives; the track is
            // simply measured again next time, which the sweep already had to survive.
            combine(
                settings.all.map { it.normalizingVolume }.distinctUntilChanged(),
                loudness.remaining.map { it > 0 }.distinctUntilChanged(),
                combine(
                    onScreen,
                    _state.map { it.isPlaying }.distinctUntilChanged(),
                ) { visible, playing -> visible || !playing },
            ) { normalizing, pending, unobtrusive -> normalizing && pending && unobtrusive }
                .distinctUntilChanged()
                .collectLatest { work -> if (work) loudness.drain() }
        }
        readVolume()
        observeVolume()
        viewModelScope.launch {
            // A play that was refused somewhere the app cannot raise a prompt — the notification,
            // a car head unit — still gets explained the next time the user looks at the screen.
            PlaybackGate.blocked.collect { block ->
                when (block) {
                    PlaybackGate.Block.OUTPUT_DEVICE -> notify(outputRequirementMessage())
                    PlaybackGate.Block.VOICE_OVER_OUTPUT -> notify(voiceOverRequirementMessage())
                    PlaybackGate.Block.BIOMETRIC -> notify("Press play in the app to unlock.")
                    null -> return@collect
                }
                PlaybackGate.consumeBlock()
            }
        }
        refreshBiometrics()
    }

    /** Told by the Activity, which is the only thing in a position to know. */
    fun setOnScreen(onScreen: Boolean) {
        this.onScreen.value = onScreen
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
                shuffleModeEnabled = _state.value.shuffle
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

        // An edit to the queue that is playing is patched in place where it can be. Handing the
        // player a whole new list tears its decoders down and refills its buffers, which is
        // audible — and an import emits one of these per track, so a batch uploaded through the
        // web manager would otherwise chew a hole in the song playing for every file that landed.
        if (!switched && playingId != null && patchQueue(player, existing, wanted, items)) return

        val carriedOver = tracks.indexOfFirst { it.id == playingId }

        // The same goes for a switch, or a reordering, that keeps the playing track: the rest of
        // the queue is rewritten around it and it never notices. A player sitting idle has no
        // decoder to protect, and is rebuilt so that it gets prepared.
        if (carriedOver >= 0 && player.playbackState != Player.STATE_IDLE) {
            spliceQueue(player, items, carriedOver)
            return
        }

        // Picking up where the last session stopped, which is only ever the first queue built
        // into a player holding nothing: a service that outlived the Activity already has both
        // the queue and the position, and this would wind it back. Spent whether or not it lands,
        // so a track deleted between sessions costs the resume rather than lying in wait for some
        // later queue that happens to contain it.
        val resuming = pendingResume
            ?.takeIf { existing.isEmpty() }
            ?.let { point ->
                val index = tracks.indexOfFirst { it.id == point.trackId }
                if (index >= 0) index to point.positionMs else null
            }
        if (existing.isEmpty()) pendingResume = null

        val start = when {
            // The track came along with the switch, so it keeps its place and its position.
            carriedOver >= 0 -> carriedOver
            resuming != null -> resuming.first
            items.isEmpty() || !switched -> null
            // Shuffle is honoured here rather than left to the player: the player's shuffle order
            // decides what comes *next*, not where a queue it has only just been handed begins.
            _state.value.shuffle -> items.indices.random()
            else -> 0
        }

        val startPosition = when {
            carriedOver >= 0 -> resumePosition
            resuming != null -> resuming.second
            else -> 0L
        }

        if (start == null) {
            player.setMediaItems(items)
        } else {
            player.setMediaItems(items, start, startPosition)
        }
        player.prepare()
        // Playing carries across a switch whenever there is anything to play. A tile that happens
        // not to hold the current track is still a tile the user just chose, and falling silent
        // reads as a fault rather than as an answer.
        player.playWhenReady =
            wasPlaying && (carriedOver >= 0 || (switched && items.isNotEmpty()))
    }

    /**
     * Swaps out everything except the track being played, which stays where it is: the items in
     * front of it are replaced by those that now precede it, and the ones behind it by those that
     * now follow. The player keeps its current period through both edits, so the audio carries
     * on, the position holds, and playing or paused stays as it was.
     */
    private fun spliceQueue(player: Player, items: List<MediaItem>, carriedOver: Int) {
        val before = items.subList(0, carriedOver)
        val after = items.subList(carriedOver + 1, items.size)
        val current = player.currentMediaItemIndex
        if (current > 0 || before.isNotEmpty()) player.replaceMediaItems(0, current, before)
        // The playing track now sits at [carriedOver], and the front edit is already counted.
        val tail = carriedOver + 1
        val count = player.mediaItemCount
        if (count > tail || after.isNotEmpty()) player.replaceMediaItems(tail, count, after)
    }

    /**
     * Edits the live queue instead of replacing it, and says whether it could.
     *
     * Only insertions and deletions are expressible this way, so the tracks common to both lists
     * have to appear in the same order in each; a genuine reordering is left to the caller's
     * [spliceQueue]. So is the disappearance of the track being played — removing it would have the
     * player slide onto whatever followed, and stopping is the answer [syncQueue] deliberately
     * gives when a file goes away underneath it.
     *
     * Deletions run from the back so that each index is still the one measured; insertions then
     * run from the front, where what remains of the old queue is a subsequence of the new one and
     * every missing track goes in at its final position.
     */
    private fun patchQueue(
        player: Player,
        existing: List<String>,
        wanted: List<String>,
        items: List<MediaItem>,
    ): Boolean {
        val wantedIds = wanted.toSet()
        val existingIds = existing.toSet()
        if (player.currentMediaItem?.mediaId !in wantedIds) return false

        val kept = existing.filter { it in wantedIds }
        if (kept != wanted.filter { it in existingIds }) return false

        for (index in existing.indices.reversed()) {
            if (existing[index] !in wantedIds) player.removeMediaItem(index)
        }
        wanted.forEachIndexed { index, id ->
            if (index >= player.mediaItemCount || player.getMediaItemAt(index).mediaId != id) {
                player.addMediaItem(index, items[index])
            }
        }
        return true
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
            // blockReason() answers for playback alone. VoiceOver's refusal is recorded straight
            // onto the gate by whoever pressed it, and never comes back out of here.
            PlaybackGate.Block.VOICE_OVER_OUTPUT, null -> Unit
        }
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            // A queue that has run out sits in STATE_ENDED with `playWhenReady` still true, so
            // `play()` on its own changes nothing: no field moves, therefore no listener fires,
            // therefore [readPlayback] never runs and the ticker it starts stays dead. The seeker
            // sits at the end of the last track and the button keeps offering a play that does
            // nothing. Sending the player back to the top of the track is what Media3's own
            // `Util.handlePlayButtonAction` does for this state, which is why the notification's
            // play button has always recovered from it and this one did not.
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
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

    private fun voiceOverRequirementMessage(): String =
        "Connect ${PlaybackGate.state.value.requiredOutputLabel} for VoiceOver."

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

    /**
     * Jump to a track by the number written on the hero readout, which counts from one.
     *
     * Whether the music is running carries across the jump untouched: this moves through the
     * queue, it does not start anything. That also keeps it clear of the gate — a queue that was
     * silent stays silent, so there is nothing here for a missing headset to refuse.
     *
     * The number is checked again rather than trusted. The dialog has already refused a bad one,
     * but the queue can be rebuilt by an import or a deletion while the dialog is open.
     */
    fun goToTrack(number: Int) {
        val player = controller ?: return
        val index = number - 1
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        readPosition()
    }

    /**
     * Where in the queue a skip lands is the service's player to decide, not this screen's — it
     * wraps at both ends whatever the repeat mode is; see `CircularPlayer` in [PlaybackService].
     *
     * Asked for as a custom command rather than by calling the player: the ordinary skip is
     * withdrawn from a controller at the ends of a non-repeating queue, which is precisely where
     * the wrap has work to do. See [QueueCommands].
     */
    fun next() {
        if (controller == null) return
        sendCommand(QueueCommands.SKIP_NEXT)
        readPosition()
    }

    /**
     * Restarts the current track first, then steps back — the usual double-press behaviour.
     *
     * The restart is a seek within the track playing, which no queue position can take away, so
     * only the step back has to go the long way round.
     */
    fun previous() {
        val player = controller ?: return
        if (player.currentPosition > RESTART_THRESHOLD_MS) {
            player.seekTo(0L)
        } else {
            sendCommand(QueueCommands.SKIP_PREVIOUS)
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
        val current = _state.value
        val next = !current.shuffle
        controller?.shuffleModeEnabled = next
        viewModelScope.launch {
            settings.setTileModes(current.activeGroupId, current.modes.copy(shuffle = next))
        }
    }

    /** Swaps the seeker's right-hand label between time left and track length. */
    fun toggleTimeMode() {
        val next = !_state.value.settings.showRemainingTime
        viewModelScope.launch { settings.setShowRemainingTime(next) }
    }

    /**
     * How the tile on the ribbon lays its tracks out, and which way round.
     *
     * Both in one call because the dialog asks both questions at once, and writing them separately
     * would rebuild the queue twice for one answer.
     */
    fun setActiveOrder(order: TrackOrder, reversed: Boolean) {
        val current = _state.value
        viewModelScope.launch {
            settings.setTileModes(
                current.activeGroupId,
                current.modes.copy(orderOrdinal = order.ordinal, reversed = reversed),
            )
        }
    }

    fun cycleRepeat() {
        val current = _state.value
        val next = current.repeat.next()
        controller?.repeatMode = next.playerValue
        viewModelScope.launch {
            settings.setTileModes(
                current.activeGroupId,
                current.modes.copy(repeatOrdinal = next.ordinal),
            )
        }
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

    /**
     * Levels every track to the same loudness.
     *
     * Switching it on is also what starts the measuring sweep, so a vault that has never been
     * analysed begins working through itself here rather than at import time — which is why the
     * screen says how much is left rather than claiming the setting took effect at once.
     */
    fun setNormalizeVolume(normalize: Boolean) {
        viewModelScope.launch { settings.setNormalizeVolume(normalize) }
    }

    /** A track and how far into it, as read back from the preferences at startup. */
    private data class ResumePoint(val trackId: String, val positionMs: Long)

    /**
     * A list to load, and the shape to load it in.
     *
     * One value rather than three, because it is what the flows above are made distinct by: any
     * of the three changing is a different list of tracks, and none of them changing is the same
     * one. Comparing them separately would either reload on every unrelated state update or miss
     * a re-ordering of the tile already loaded.
     */
    private data class Ordering(
        val groupId: String,
        val order: TrackOrder,
        val reversed: Boolean,
    )

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
        val buffering = player != null && player.playbackState == Player.STATE_BUFFERING
        _state.update {
            if (it.isPlaying == intending && it.isBuffering == buffering) it
            else it.copy(isPlaying = intending, isBuffering = buffering)
        }
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
        // Greyed for this, but still taking the press, exactly as the play button does: the
        // service would turn the command away anyway, and a line saying what to plug in is worth
        // more than a button that goes quiet without a reason.
        _state.value.voiceOverRefusal?.let {
            notify(it)
            return
        }
        if (_state.value.volume == 0) notify("Media volume is muted.")
        sendCommand(action, args)
    }

    /** Anything the service does on the UI's behalf goes out this way. */
    private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
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
            settings.clearTileModes(id)
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

    /**
     * Writes the tag sheet's answer: [groupIds] is every group the tracks should now all be in.
     *
     * Only the difference from what they shared when the sheet opened is written. A group that
     * holds some of the selection and was left unticked is not one the user said anything about,
     * and emptying it of those tracks would be putting words in their mouth.
     *
     * [newGroup] makes a group and files the tracks into it in the same breath, because the sheet
     * is open precisely because there are tracks waiting to be filed: making the group and then
     * asking the user to tick it would be a ceremony with one possible outcome. It borrows
     * [VaultRepository.createGroup] rather than naming and colouring a group of its own, so a
     * group made from the sheet and a group made from the library's plus are the same kind of
     * thing -- named by its cardinal, coloured a step further along the palette, and renamed by
     * the same pencil when the default stops being good enough.
     */
    fun applyTags(tracks: List<Track>, groupIds: Set<String>, newGroup: Boolean) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            val ids = tracks.map { it.id }
            val before = repository.groupsSharedBy(ids)
            (groupIds - before).forEach { repository.setTag(ids, it, true) }
            (before - groupIds).forEach { repository.setTag(ids, it, false) }
            if (newGroup) repository.setTag(ids, repository.createGroup().id, true)
            _state.update { it.copy(sharedGroupIds = repository.groupsSharedBy(ids)) }
        }
    }

    /** Set from the group's own dialog, for a tile that need not be the one playing. */
    fun setGroupModes(groupId: String, modes: TileModes) {
        viewModelScope.launch { settings.setTileModes(groupId, modes) }
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

    /** One switch per part of the announcement — see [VoicePart]. */
    fun setVoicePart(part: VoicePart, speak: Boolean) {
        viewModelScope.launch {
            when (part) {
                VoicePart.TITLE -> settings.setSpeakTitle(speak)
                VoicePart.ARTIST -> settings.setSpeakArtist(speak)
                VoicePart.ALBUM -> settings.setSpeakAlbum(speak)
                VoicePart.YEAR -> settings.setSpeakYear(speak)
            }
        }
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

    /**
     * Hearts or unhearts the track playing.
     *
     * The queue is not rebuilt for it. Un-hearting from inside the Favorites queue would otherwise
     * pull the track out from under the player mid-song, which is the one thing [syncQueue] goes
     * out of its way not to do — the row leaves the tile, and the queue catches up at the next
     * switch.
     */
    fun toggleFavorite() {
        val track = _state.value.tracks.getOrNull(_state.value.trackIndex) ?: return
        setFavorite(track, !track.favorite)
    }

    fun setFavorite(track: Track, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(track.id, favorite) }
    }

    fun setBlockScreenshots(block: Boolean) {
        viewModelScope.launch { settings.setBlockScreenshots(block) }
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
        loudness.release()
        super.onCleared()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val NOTICE_MS = 4_000L
        const val TICK_MS = 500L
        const val QUEUE_SWITCH_MS = 2_000L
    }
}

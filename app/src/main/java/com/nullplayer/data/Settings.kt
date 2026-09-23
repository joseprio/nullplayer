package com.nullplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferences: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * How one tile plays: its own shuffle, its own repeat, and the order it is laid out in.
 *
 * Per tile rather than per app because a tile is a queue, and the way you want a queue played is
 * a property of what is in it - an album wants its own order and one pass, a big vault wants
 * shuffling for ever. Switching tiles used to carry the last tile's answer along with it, which
 * meant the shuffle button had to be re-checked every time the ribbon moved.
 *
 * The repeat mode is an ordinal of [com.nullplayer.playback.RepeatMode], and the order one of
 * [TrackOrder], both stored as Ints for the same reason [AppSettings.repeatOrdinal] always was:
 * the preference file has no business knowing the enums.
 */
data class TileModes(
    val shuffle: Boolean,
    val repeatOrdinal: Int,
    val orderOrdinal: Int = TrackOrder.ADDED.ordinal,
    /** Last track first, applied to the finished list rather than folded into the sort. */
    val reversed: Boolean = false,
) {

    /** The order as whatever is about to do the sorting wants it. */
    val order: TrackOrder get() = TrackOrder.ofOrdinal(orderOrdinal)

    companion object {

        /**
         * One preference holds the lot, as `id:shuffle:repeat:order:reversed` a line each.
         *
         * A key per tile would be the obvious alternative, and it leaves a pair of orphans behind
         * every deleted group for ever. This is one value, written atomically, that a delete can
         * prune. Neither separator can occur in an id: a group's is a UUID, and the two standing
         * tiles are "" and "~favorites".
         */
        internal fun encode(modes: Map<String, TileModes>): String =
            modes.entries.joinToString("\n") { (id, it) ->
                "$id:${if (it.shuffle) 1 else 0}:${it.repeatOrdinal}:${it.orderOrdinal}:" +
                    "${if (it.reversed) 1 else 0}"
            }

        /**
         * Anything that does not parse is dropped rather than guessed at.
         *
         * A three-field line is what an install written before there was an order to store has.
         * It is read rather than dropped, and answers with the default order — which is the order
         * that install was already playing in, so nothing moves underneath an upgrade.
         */
        internal fun decode(stored: String?): Map<String, TileModes> {
            if (stored.isNullOrEmpty()) return emptyMap()
            return stored.lineSequence().mapNotNull { line ->
                val parts = line.split(':')
                if (parts.size != 3 && parts.size != 5) return@mapNotNull null
                val repeat = parts[2].toIntOrNull() ?: return@mapNotNull null
                val order = if (parts.size == 5) {
                    parts[3].toIntOrNull() ?: return@mapNotNull null
                } else {
                    TrackOrder.ADDED.ordinal
                }
                parts[0] to TileModes(
                    shuffle = parts[1] == "1",
                    repeatOrdinal = repeat,
                    orderOrdinal = order,
                    reversed = parts.size == 5 && parts[4] == "1",
                )
            }.toMap()
        }
    }
}

/**
 * Everything the user can configure, in one value.
 *
 * A single object rather than a flow per key: the audio policy only makes sense read together —
 * "require a device" is meaningless without knowing *which* — and the gate that enforces it wants
 * one consistent snapshot rather than three that arrive separately.
 */
data class AppSettings(
    val webServerEnabled: Boolean = false,
    val deleteOriginals: Boolean = false,
    /** Ask for biometrics before the app is usable at all. */
    val lockOnLaunch: Boolean = false,
    /**
     * Refuse screenshots, screen recordings and casting, and blank the entry in the recent-apps
     * list. Off by default: it is the one setting here that takes something away from the user
     * as readily as from anyone looking over their shoulder.
     */
    val blockScreenshots: Boolean = false,
    /** Ask for biometrics every time playback starts or resumes. */
    val lockOnPlay: Boolean = false,
    /**
     * Ask for biometrics before the dock opens. On by default: the dock is the only screen that
     * can delete the vault, and the vault is the one thing in the app with no copy anywhere else.
     */
    val lockOnDock: Boolean = true,
    /**
     * Ask for biometrics before the settings screen opens. Off by default, because it is the
     * switch that makes the other three mean anything: a lock whose own switch is in the open is
     * a lock anyone holding the phone can turn off.
     */
    val lockOnSettings: Boolean = false,
    /** Refuse to play unless [requiredDeviceKey] — or, when blank, any headset — is connected. */
    val requireOutputDevice: Boolean = false,
    val requiredDeviceKey: String = "",
    /** Route audio here when it is connected. Blank means "whatever the system picks". */
    val preferredDeviceKey: String = "",
    /**
     * What a tile plays like before it has been given an answer of its own - see [modesFor].
     *
     * These two were the whole setting before shuffle and repeat became a property of the tile.
     * They are still read, and deliberately no longer written: an install that had shuffle off
     * keeps it off on every tile it had never touched, rather than being handed the default back.
     */
    val shuffle: Boolean = true,
    /** Stored as an ordinal of [com.nullplayer.playback.RepeatMode]. */
    val repeatOrdinal: Int = 1,
    /** How the tiles that have been given an answer of their own play. Keyed by tile id. */
    val tileModes: Map<String, TileModes> = emptyMap(),
    val equalizerEnabled: Boolean = false,
    /** Index into the device's preset list, or -1 once a fader has been moved by hand. */
    val equalizerPreset: Int = -1,
    /** Band levels in millibels, one per band. Empty until the faders are touched. */
    val equalizerBands: List<Int> = emptyList(),
    /** An AutoEQ ParametricEQ config, verbatim. Blank means the faders define the curve. */
    val equalizerAutoEq: String = "",
    /**
     * Play every track at the same loudness.
     *
     * Stored on its own but only honoured while [equalizerEnabled] is on — see
     * [normalizingVolume]. The switch on the title bar governs everything the app does to the
     * sound on its way out, levelling included, so there is one way to hear a file untouched
     * rather than two things to remember to turn off.
     */
    val normalizeVolume: Boolean = false,
    /**
     * Blend a little of each channel into the other, the way speakers in a room would.
     *
     * Headphones only in spirit — nothing here knows what is plugged in — and, like
     * [normalizeVolume], honoured only while [equalizerEnabled] is on; see [crossfeeding].
     */
    val crossfeed: Boolean = false,
    /** Stored as an ordinal of [com.nullplayer.playback.CrossfeedStrength]. */
    val crossfeedStrengthOrdinal: Int = 0,
    /**
     * Leave crossfeed out of the chain while nothing is plugged in.
     *
     * On by default, because the effect is a statement about headphones: on the phone's own
     * speaker, or a Bluetooth one, it narrows an image the room has already collapsed. Off is
     * for whoever wants it on everything regardless, or whose headphones the phone cannot tell
     * from a speaker.
     */
    val crossfeedHeadphonesOnly: Boolean = true,
    /**
     * Vibrate with the music, through the platform's haptic generator.
     *
     * Only ever honoured on a phone whose audio path carries haptic channels; elsewhere the
     * switch is shown disabled.
     */
    val haptics: Boolean = false,
    /** Which tile the ribbon has selected. Blank is the whole vault. */
    val activeGroupId: String = "",
    /**
     * Where the music had got to, so a cold start can pick it up again.
     *
     * Written by the service rather than by the screen, because the screen is the half that is
     * not there for most of the playing. Blank when nothing has played yet, and stale rather than
     * wrong when the process was killed mid-track: the saving is periodic, so the worst case is a
     * few seconds of the song heard twice.
     */
    val lastTrackId: String = "",
    val lastPositionMs: Long = 0L,
    /**
     * The album art, title and artist on the player.
     *
     * Off by default, because the player is built to name nothing: everywhere but the dock the
     * track is only ever spoken. This is the one switch that changes that, and it changes it for
     * the screen alone — the notification and whatever else reads the session stay anonymous.
     */
    val showTrackInfo: Boolean = false,
    /**
     * The title, artist and sleeve on the notification — and so on the lock screen, a watch, a
     * car, and anything else that reads the media session. Its own switch rather than a part of
     * [showTrackInfo]: the screen is in your hand and the notification is on the lock screen,
     * and wanting one named is no reason to have the other named too.
     */
    val notificationTrackInfo: Boolean = false,
    /**
     * The ribbon of tiles across the top of the player.
     *
     * Off, the player is the one queue and nothing else; a tile is chosen from the library
     * instead, which is why every row there has a play button.
     */
    val showRibbon: Boolean = true,
    val showSeeker: Boolean = true,
    /** The play button's glow swelling with the bass and the kick while the music plays. */
    val pulseGlow: Boolean = true,
    /**
     * Which way the label on the right of the seeker reads: counting down to the end of the
     * track, or simply stating how long it is. Tapping it swaps the two.
     */
    val showRemainingTime: Boolean = true,
    /** The "12 items" line on each ribbon tile. */
    val showVaultCounts: Boolean = true,
    /** The text-to-speech voice VoiceOver uses. Blank means whatever the engine picks. */
    val voiceName: String = "",
    /**
     * Which parts of a track VoiceOver names, one switch each.
     *
     * All four on by default, which is what the announcement said before there was anything to
     * turn off. They are stored as four flags rather than one packed value because that is what
     * they are on the screen, and because a switch added later then has a default of its own
     * instead of changing the meaning of what is already written down. See
     * [com.nullplayer.playback.VoicePart].
     */
    val speakTitle: Boolean = true,
    val speakArtist: Boolean = true,
    val speakAlbum: Boolean = true,
    val speakYear: Boolean = true,
    /** Whether the notification permission has been asked for once. It is never asked twice. */
    val askedForNotifications: Boolean = false,
) {
    /**
     * Whether levelling is actually running, as opposed to merely asked for.
     *
     * The one place the two switches are combined: the audio chain, the measuring sweep and the
     * screen all read this, so a track's gain, whether the library gets decoded in the background,
     * and what the row says can never disagree.
     */
    val normalizingVolume: Boolean get() = equalizerEnabled && normalizeVolume

    /** Whether crossfeed is actually running, under the same master switch as [normalizingVolume]. */
    val crossfeeding: Boolean get() = equalizerEnabled && crossfeed

    /**
     * How [tileId] plays, falling back to the app-wide pair for a tile never asked about.
     *
     * Every tile has an answer here, including the two that have no row of their own - the vault
     * and Favorites play like anything else and are switched to the same way.
     */
    fun modesFor(tileId: String): TileModes =
        tileModes[tileId] ?: TileModes(shuffle = shuffle, repeatOrdinal = repeatOrdinal)
}

/** The handful of things the user can actually configure. */
class Settings(private val context: Context) {

    val all: Flow<AppSettings> = context.preferences.data.map { prefs ->
        AppSettings(
            webServerEnabled = prefs[WEB_SERVER_ENABLED] ?: false,
            deleteOriginals = prefs[DELETE_ORIGINALS] ?: false,
            blockScreenshots = prefs[BLOCK_SCREENSHOTS] ?: false,
            lockOnLaunch = prefs[LOCK_ON_LAUNCH] ?: false,
            lockOnPlay = prefs[LOCK_ON_PLAY] ?: false,
            lockOnDock = prefs[LOCK_ON_DOCK] ?: true,
            lockOnSettings = prefs[LOCK_ON_SETTINGS] ?: false,
            requireOutputDevice = prefs[REQUIRE_OUTPUT_DEVICE] ?: false,
            requiredDeviceKey = prefs[REQUIRED_DEVICE_KEY].orEmpty(),
            preferredDeviceKey = prefs[PREFERRED_DEVICE_KEY].orEmpty(),
            shuffle = prefs[SHUFFLE] ?: true,
            repeatOrdinal = prefs[REPEAT_MODE] ?: 1,
            tileModes = TileModes.decode(prefs[TILE_MODES]),
            equalizerEnabled = prefs[EQ_ENABLED] ?: false,
            equalizerPreset = prefs[EQ_PRESET] ?: -1,
            equalizerBands = decodeBands(prefs[EQ_BANDS]),
            equalizerAutoEq = prefs[EQ_AUTOEQ].orEmpty(),
            normalizeVolume = prefs[NORMALIZE_VOLUME] ?: false,
            crossfeed = prefs[CROSSFEED] ?: false,
            crossfeedStrengthOrdinal = prefs[CROSSFEED_STRENGTH] ?: 0,
            crossfeedHeadphonesOnly = prefs[CROSSFEED_HEADPHONES_ONLY] ?: true,
            haptics = prefs[HAPTICS] ?: false,
            activeGroupId = prefs[ACTIVE_GROUP].orEmpty(),
            lastTrackId = prefs[LAST_TRACK].orEmpty(),
            lastPositionMs = prefs[LAST_POSITION] ?: 0L,
            showTrackInfo = prefs[SHOW_TRACK_INFO] ?: false,
            notificationTrackInfo = prefs[NOTIFICATION_TRACK_INFO] ?: false,
            showRibbon = prefs[SHOW_RIBBON] ?: true,
            showSeeker = prefs[SHOW_SEEKER] ?: true,
            pulseGlow = prefs[PULSE_GLOW] ?: true,
            showRemainingTime = prefs[SHOW_REMAINING_TIME] ?: true,
            showVaultCounts = prefs[SHOW_VAULT_COUNTS] ?: true,
            voiceName = prefs[VOICE_NAME].orEmpty(),
            speakTitle = prefs[SPEAK_TITLE] ?: true,
            speakArtist = prefs[SPEAK_ARTIST] ?: true,
            speakAlbum = prefs[SPEAK_ALBUM] ?: true,
            speakYear = prefs[SPEAK_YEAR] ?: true,
            askedForNotifications = prefs[ASKED_FOR_NOTIFICATIONS] ?: false,
        )
    }

    suspend fun setWebServerEnabled(enabled: Boolean) = put(WEB_SERVER_ENABLED, enabled)

    suspend fun setDeleteOriginals(delete: Boolean) = put(DELETE_ORIGINALS, delete)

    suspend fun setBlockScreenshots(block: Boolean) = put(BLOCK_SCREENSHOTS, block)

    suspend fun setLockOnLaunch(lock: Boolean) = put(LOCK_ON_LAUNCH, lock)

    suspend fun setLockOnPlay(lock: Boolean) = put(LOCK_ON_PLAY, lock)

    suspend fun setLockOnDock(lock: Boolean) = put(LOCK_ON_DOCK, lock)

    suspend fun setLockOnSettings(lock: Boolean) = put(LOCK_ON_SETTINGS, lock)

    suspend fun setRequireOutputDevice(require: Boolean) = put(REQUIRE_OUTPUT_DEVICE, require)

    /** Blank means "any headset will do". */
    suspend fun setRequiredDeviceKey(key: String) = put(REQUIRED_DEVICE_KEY, key)

    /** Blank means "leave routing to the system". */
    suspend fun setPreferredDeviceKey(key: String) = put(PREFERRED_DEVICE_KEY, key)

    /**
     * How one tile plays. Read-modify-write inside one edit, so two tiles answered in quick
     * succession cannot lose each other.
     */
    suspend fun setTileModes(tileId: String, modes: TileModes) = context.preferences.edit { prefs ->
        prefs[TILE_MODES] = TileModes.encode(
            TileModes.decode(prefs[TILE_MODES]) + (tileId to modes)
        )
    }

    /** Called when a group goes, so nothing is left behind for a later tile to inherit. */
    suspend fun clearTileModes(tileId: String) = context.preferences.edit { prefs ->
        prefs[TILE_MODES] = TileModes.encode(TileModes.decode(prefs[TILE_MODES]) - tileId)
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) = put(EQ_ENABLED, enabled)

    /** Pass a preset below zero to mean "the stored band levels are the truth". */
    suspend fun setEqualizerPreset(preset: Int) = put(EQ_PRESET, preset)

    suspend fun setEqualizerAutoEq(config: String) = put(EQ_AUTOEQ, config)

    suspend fun setNormalizeVolume(normalize: Boolean) = put(NORMALIZE_VOLUME, normalize)

    suspend fun setCrossfeed(crossfeed: Boolean) = put(CROSSFEED, crossfeed)

    suspend fun setCrossfeedStrength(ordinal: Int) = put(CROSSFEED_STRENGTH, ordinal)

    suspend fun setCrossfeedHeadphonesOnly(only: Boolean) = put(CROSSFEED_HEADPHONES_ONLY, only)

    suspend fun setHaptics(haptics: Boolean) = put(HAPTICS, haptics)


    suspend fun setEqualizerBands(bands: List<Int>) =
        put(EQ_BANDS, bands.joinToString(","))

    suspend fun setActiveGroup(id: String) = put(ACTIVE_GROUP, id)

    /** Where to pick up from. Both halves in one edit, so they can never name different tracks. */
    suspend fun setResumePoint(trackId: String, positionMs: Long) = context.preferences.edit {
        it[LAST_TRACK] = trackId
        it[LAST_POSITION] = positionMs
    }

    suspend fun setShowTrackInfo(show: Boolean) = put(SHOW_TRACK_INFO, show)

    suspend fun setNotificationTrackInfo(show: Boolean) = put(NOTIFICATION_TRACK_INFO, show)

    suspend fun setShowRibbon(show: Boolean) = put(SHOW_RIBBON, show)

    suspend fun setShowSeeker(show: Boolean) = put(SHOW_SEEKER, show)

    suspend fun setPulseGlow(pulse: Boolean) = put(PULSE_GLOW, pulse)

    suspend fun setShowRemainingTime(show: Boolean) = put(SHOW_REMAINING_TIME, show)

    suspend fun setShowVaultCounts(show: Boolean) = put(SHOW_VAULT_COUNTS, show)

    suspend fun setVoiceName(name: String) = put(VOICE_NAME, name)

    suspend fun setSpeakTitle(speak: Boolean) = put(SPEAK_TITLE, speak)

    suspend fun setSpeakArtist(speak: Boolean) = put(SPEAK_ARTIST, speak)

    suspend fun setSpeakAlbum(speak: Boolean) = put(SPEAK_ALBUM, speak)

    suspend fun setSpeakYear(speak: Boolean) = put(SPEAK_YEAR, speak)

    suspend fun setAskedForNotifications() = put(ASKED_FOR_NOTIFICATIONS, true)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.preferences.edit { it[key] = value }
    }

    private companion object {
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val DELETE_ORIGINALS = booleanPreferencesKey("delete_originals")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val LOCK_ON_LAUNCH = booleanPreferencesKey("lock_on_launch")
        val LOCK_ON_PLAY = booleanPreferencesKey("lock_on_play")
        val LOCK_ON_DOCK = booleanPreferencesKey("lock_on_dock")
        val LOCK_ON_SETTINGS = booleanPreferencesKey("lock_on_settings")
        val REQUIRE_OUTPUT_DEVICE = booleanPreferencesKey("require_output_device")
        val REQUIRED_DEVICE_KEY = stringPreferencesKey("required_device_key")
        val PREFERRED_DEVICE_KEY = stringPreferencesKey("preferred_device_key")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val EQ_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQ_PRESET = intPreferencesKey("equalizer_preset")
        val EQ_BANDS = stringPreferencesKey("equalizer_bands")
        val EQ_AUTOEQ = stringPreferencesKey("equalizer_autoeq")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val CROSSFEED = booleanPreferencesKey("crossfeed")
        val CROSSFEED_STRENGTH = intPreferencesKey("crossfeed_strength")
        val CROSSFEED_HEADPHONES_ONLY = booleanPreferencesKey("crossfeed_headphones_only")
        val HAPTICS = booleanPreferencesKey("haptics")
        val TILE_MODES = stringPreferencesKey("tile_modes")
        val ACTIVE_GROUP = stringPreferencesKey("active_group")
        val LAST_TRACK = stringPreferencesKey("last_track")
        val LAST_POSITION = longPreferencesKey("last_position")
        val SHOW_TRACK_INFO = booleanPreferencesKey("show_track_info")
        val NOTIFICATION_TRACK_INFO = booleanPreferencesKey("notification_track_info")
        val SHOW_RIBBON = booleanPreferencesKey("show_ribbon")
        val SHOW_SEEKER = booleanPreferencesKey("show_seeker")
        val PULSE_GLOW = booleanPreferencesKey("pulse_glow")
        val SHOW_REMAINING_TIME = booleanPreferencesKey("show_remaining_time")
        val SHOW_VAULT_COUNTS = booleanPreferencesKey("show_vault_counts")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val SPEAK_TITLE = booleanPreferencesKey("speak_title")
        val SPEAK_ARTIST = booleanPreferencesKey("speak_artist")
        val SPEAK_ALBUM = booleanPreferencesKey("speak_album")
        val SPEAK_YEAR = booleanPreferencesKey("speak_year")
        val ASKED_FOR_NOTIFICATIONS = booleanPreferencesKey("asked_for_notifications")

        /** Band levels are one preference, not one per band, so they are written atomically. */
        fun decodeBands(stored: String?): List<Int> =
            stored?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    }
}

package com.nullplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferences: DataStore<Preferences> by preferencesDataStore("settings")

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
    /** Ask for biometrics every time playback starts or resumes. */
    val lockOnPlay: Boolean = false,
    /**
     * Ask for biometrics before the dock opens. On by default: the dock is the only screen that
     * can delete the vault, and the vault is the one thing in the app with no copy anywhere else.
     */
    val lockOnDock: Boolean = true,
    /** Refuse to play unless [requiredDeviceKey] — or, when blank, any headset — is connected. */
    val requireOutputDevice: Boolean = false,
    val requiredDeviceKey: String = "",
    /** Route audio here when it is connected. Blank means "whatever the system picks". */
    val preferredDeviceKey: String = "",
    val shuffle: Boolean = true,
    /** Stored as an ordinal of [com.nullplayer.playback.RepeatMode]. */
    val repeatOrdinal: Int = 1,
    val equalizerEnabled: Boolean = false,
    /** Index into the device's preset list, or -1 once a fader has been moved by hand. */
    val equalizerPreset: Int = -1,
    /** Band levels in millibels, one per band. Empty until the faders are touched. */
    val equalizerBands: List<Int> = emptyList(),
    /** An AutoEQ ParametricEQ config, verbatim. Blank means the faders define the curve. */
    val equalizerAutoEq: String = "",
    /** Which tile the ribbon has selected. Blank is the whole vault. */
    val activeGroupId: String = "",
    val showSeeker: Boolean = true,
    /**
     * Which way the label on the right of the seeker reads: counting down to the end of the
     * track, or simply stating how long it is. Tapping it swaps the two.
     */
    val showRemainingTime: Boolean = true,
    /** The "12 items" line on each ribbon tile. */
    val showVaultCounts: Boolean = true,
    /** The text-to-speech voice VoiceOver uses. Blank means whatever the engine picks. */
    val voiceName: String = "",
    /** Whether the notification permission has been asked for once. It is never asked twice. */
    val askedForNotifications: Boolean = false,
)

/** The handful of things the user can actually configure. */
class Settings(private val context: Context) {

    val all: Flow<AppSettings> = context.preferences.data.map { prefs ->
        AppSettings(
            webServerEnabled = prefs[WEB_SERVER_ENABLED] ?: false,
            deleteOriginals = prefs[DELETE_ORIGINALS] ?: false,
            lockOnLaunch = prefs[LOCK_ON_LAUNCH] ?: false,
            lockOnPlay = prefs[LOCK_ON_PLAY] ?: false,
            lockOnDock = prefs[LOCK_ON_DOCK] ?: true,
            requireOutputDevice = prefs[REQUIRE_OUTPUT_DEVICE] ?: false,
            requiredDeviceKey = prefs[REQUIRED_DEVICE_KEY].orEmpty(),
            preferredDeviceKey = prefs[PREFERRED_DEVICE_KEY].orEmpty(),
            shuffle = prefs[SHUFFLE] ?: true,
            repeatOrdinal = prefs[REPEAT_MODE] ?: 1,
            equalizerEnabled = prefs[EQ_ENABLED] ?: false,
            equalizerPreset = prefs[EQ_PRESET] ?: -1,
            equalizerBands = decodeBands(prefs[EQ_BANDS]),
            equalizerAutoEq = prefs[EQ_AUTOEQ].orEmpty(),
            activeGroupId = prefs[ACTIVE_GROUP].orEmpty(),
            showSeeker = prefs[SHOW_SEEKER] ?: true,
            showRemainingTime = prefs[SHOW_REMAINING_TIME] ?: true,
            showVaultCounts = prefs[SHOW_VAULT_COUNTS] ?: true,
            voiceName = prefs[VOICE_NAME].orEmpty(),
            askedForNotifications = prefs[ASKED_FOR_NOTIFICATIONS] ?: false,
        )
    }

    suspend fun setWebServerEnabled(enabled: Boolean) = put(WEB_SERVER_ENABLED, enabled)

    suspend fun setDeleteOriginals(delete: Boolean) = put(DELETE_ORIGINALS, delete)

    suspend fun setLockOnLaunch(lock: Boolean) = put(LOCK_ON_LAUNCH, lock)

    suspend fun setLockOnPlay(lock: Boolean) = put(LOCK_ON_PLAY, lock)

    suspend fun setLockOnDock(lock: Boolean) = put(LOCK_ON_DOCK, lock)

    suspend fun setRequireOutputDevice(require: Boolean) = put(REQUIRE_OUTPUT_DEVICE, require)

    /** Blank means "any headset will do". */
    suspend fun setRequiredDeviceKey(key: String) = put(REQUIRED_DEVICE_KEY, key)

    /** Blank means "leave routing to the system". */
    suspend fun setPreferredDeviceKey(key: String) = put(PREFERRED_DEVICE_KEY, key)

    suspend fun setShuffle(shuffle: Boolean) = put(SHUFFLE, shuffle)

    suspend fun setRepeatOrdinal(ordinal: Int) = put(REPEAT_MODE, ordinal)

    suspend fun setEqualizerEnabled(enabled: Boolean) = put(EQ_ENABLED, enabled)

    /** Pass a preset below zero to mean "the stored band levels are the truth". */
    suspend fun setEqualizerPreset(preset: Int) = put(EQ_PRESET, preset)

    suspend fun setEqualizerAutoEq(config: String) = put(EQ_AUTOEQ, config)

    suspend fun setEqualizerBands(bands: List<Int>) =
        put(EQ_BANDS, bands.joinToString(","))

    suspend fun setActiveGroup(id: String) = put(ACTIVE_GROUP, id)

    suspend fun setShowSeeker(show: Boolean) = put(SHOW_SEEKER, show)

    suspend fun setShowRemainingTime(show: Boolean) = put(SHOW_REMAINING_TIME, show)

    suspend fun setShowVaultCounts(show: Boolean) = put(SHOW_VAULT_COUNTS, show)

    suspend fun setVoiceName(name: String) = put(VOICE_NAME, name)

    suspend fun setAskedForNotifications() = put(ASKED_FOR_NOTIFICATIONS, true)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.preferences.edit { it[key] = value }
    }

    private companion object {
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val DELETE_ORIGINALS = booleanPreferencesKey("delete_originals")
        val LOCK_ON_LAUNCH = booleanPreferencesKey("lock_on_launch")
        val LOCK_ON_PLAY = booleanPreferencesKey("lock_on_play")
        val LOCK_ON_DOCK = booleanPreferencesKey("lock_on_dock")
        val REQUIRE_OUTPUT_DEVICE = booleanPreferencesKey("require_output_device")
        val REQUIRED_DEVICE_KEY = stringPreferencesKey("required_device_key")
        val PREFERRED_DEVICE_KEY = stringPreferencesKey("preferred_device_key")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val EQ_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQ_PRESET = intPreferencesKey("equalizer_preset")
        val EQ_BANDS = stringPreferencesKey("equalizer_bands")
        val EQ_AUTOEQ = stringPreferencesKey("equalizer_autoeq")
        val ACTIVE_GROUP = stringPreferencesKey("active_group")
        val SHOW_SEEKER = booleanPreferencesKey("show_seeker")
        val SHOW_REMAINING_TIME = booleanPreferencesKey("show_remaining_time")
        val SHOW_VAULT_COUNTS = booleanPreferencesKey("show_vault_counts")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val ASKED_FOR_NOTIFICATIONS = booleanPreferencesKey("asked_for_notifications")

        /** Band levels are one preference, not one per band, so they are written atomically. */
        fun decodeBands(stored: String?): List<Int> =
            stored?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    }
}

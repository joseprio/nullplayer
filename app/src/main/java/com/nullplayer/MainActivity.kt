package com.nullplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.nullplayer.playback.PlayerViewModel
import com.nullplayer.security.AppLock
import com.nullplayer.security.Biometrics
import com.nullplayer.ui.EqualizerScreen
import com.nullplayer.ui.PlayerScreen
import com.nullplayer.ui.SettingsScreen
import com.nullplayer.ui.LibraryScreen
import com.nullplayer.ui.MiniPlayer
import com.nullplayer.ui.TracksScreen

private enum class Screen { PLAYER, LIBRARY, TRACKS, SETTINGS, EQUALIZER }

/**
 * A [FragmentActivity] rather than a plain `ComponentActivity` only because `BiometricPrompt`
 * hangs its dialog off the fragment manager. Nothing else here uses fragments.
 */
@UnstableApi
class MainActivity : FragmentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system theme is, so the bars are told so explicitly.
        // Left to `auto`, a phone in light mode gets dark status icons drawn on our black
        // background, and the clock and notifications vanish rather than merely going edge to edge.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Only on a genuinely new launch. A rotation replays the same intent, and re-importing
        // the shared file every time the screen turned would be its own kind of bug.
        if (savedInstanceState == null) consumeIncomingAudio(intent)

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()
            val vaultUnlocked by AppLock.vaultUnlocked.collectAsStateWithLifecycle()

            var screen by rememberSaveable { mutableStateOf(Screen.PLAYER) }
            var lockMessage by rememberSaveable { mutableStateOf<String?>(null) }
            var askingForVault by rememberSaveable { mutableStateOf(false) }
            // Where the pending unlock is headed: null is the library, a tile id is that tile's
            // own screen. Both go through the same prompt, so it is one flag plus a destination
            // rather than two prompts that could race each other.
            var vaultDestination by rememberSaveable { mutableStateOf<String?>(null) }
            // A tile's screen is reachable from the library and from the ribbon, and back has to
            // return the way the user came rather than always landing them in the library.
            var tracksFromRibbon by rememberSaveable { mutableStateOf(false) }

            val notifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* Playback works either way; without it there is simply no notification. */ }

            // Asked for exactly once in the life of the install.
            //
            // Declaring POST_NOTIFICATIONS in the manifest is what makes the request legal, not
            // what answers it — since Android 13 it is a runtime grant. And dismissing the dialog
            // is not a denial, so an unconditional request on every launch is a prompt on every
            // launch, for ever. Playback works either way; without it there is simply no
            // notification, and a system settings screen to turn it on later.
            LaunchedEffect(state.settingsLoaded) {
                if (!state.settingsLoaded) return@LaunchedEffect
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                if (state.settings.askedForNotifications) return@LaunchedEffect

                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

                viewModel.markNotificationsAsked()
                if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            // Applied to the window rather than set once at startup, so the switch takes hold on
            // the screen the user threw it from instead of on the next launch. Nothing is drawn
            // before the settings arrive — see the `settingsLoaded` branch below — so there is no
            // moment where the vault is on screen and the flag is not yet on.
            LaunchedEffect(state.settingsLoaded, state.settings.blockScreenshots) {
                if (!state.settingsLoaded) return@LaunchedEffect
                if (state.settings.blockScreenshots) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Both locks fail open when nothing is enrolled. A phone whose enrolment was removed
            // after the switch went on would otherwise be locked out of its own vault, and there
            // is no export path to recover it from.
            val canLock = state.biometricsAvailable
            val locked = state.settingsLoaded && state.settings.lockOnLaunch && canLock && !unlocked
            val vaultLocked = state.settings.lockOnDock && canLock && !vaultUnlocked

            // Checked while rendering, not only on the way in, so a restored instance state
            // cannot land straight in the library without passing the prompt.
            val guarded = screen == Screen.LIBRARY || screen == Screen.TRACKS
            val visible = if (guarded && vaultLocked) Screen.PLAYER else screen

            LaunchedEffect(locked) {
                if (locked) askToUnlock { lockMessage = it }
            }

            LaunchedEffect(state.awaitingPlayAuth) {
                if (state.awaitingPlayAuth) {
                    Biometrics.authenticate(
                        activity = this@MainActivity,
                        title = "Unlock playback",
                        subtitle = "Confirm it is you before the music starts.",
                        onSucceeded = viewModel::onPlayAuthorized,
                        onFailed = viewModel::onPlayAuthCancelled,
                    )
                }
            }

            LaunchedEffect(askingForVault) {
                if (askingForVault) {
                    Biometrics.authenticate(
                        activity = this@MainActivity,
                        title = "Unlock the library",
                        subtitle = "The dock names every track and can delete them.",
                        onSucceeded = {
                            AppLock.unlockVault()
                            askingForVault = false
                            val tile = vaultDestination
                            vaultDestination = null
                            if (tile == null) {
                                screen = Screen.LIBRARY
                            } else {
                                viewModel.openGroup(tile)
                                screen = Screen.TRACKS
                            }
                        },
                        onFailed = {
                            askingForVault = false
                            vaultDestination = null
                            viewModel.onVaultAuthCancelled(it)
                        },
                    )
                }
            }

            MaterialTheme(colorScheme = NullPlayerColors) {
                when {
                    // Nothing is drawn until the stored settings are in: the lock must not be
                    // something the user watches slide into place over the screen.
                    !state.settingsLoaded -> Box(
                        Modifier.fillMaxSize().background(NullPlayerColors.background)
                    )

                    locked -> LockScreen(
                        message = lockMessage,
                        onUnlock = { askToUnlock { lockMessage = it } },
                    )

                    else -> {
                        // One strip, built once, handed to every sub-screen that hosts one. The
                        // screens themselves stay ignorant of the transport: they are given a slot
                        // to place at their foot, not eight more callbacks to forward.
                        val miniPlayer: @Composable (Modifier) -> Unit = { glass ->
                            MiniPlayer(
                                state = state,
                                onPlayPause = viewModel::togglePlay,
                                onNext = viewModel::next,
                                onPrevious = viewModel::previous,
                                onScrub = viewModel::scrub,
                                onVoiceOver = viewModel::announceCurrentTrack,
                                onVoiceOverLong = viewModel::announceQueuePosition,
                                onOpenPlayer = { screen = Screen.PLAYER },
                                modifier = glass,
                            )
                        }

                        BackHandler(enabled = visible != Screen.PLAYER) {
                            screen = when {
                                visible != Screen.TRACKS -> Screen.PLAYER
                                tracksFromRibbon -> Screen.PLAYER
                                else -> Screen.LIBRARY
                            }
                        }

                        when (visible) {
                            Screen.PLAYER -> PlayerScreen(
                                state = state,
                                onPlayPause = viewModel::togglePlay,
                                onNext = viewModel::next,
                                onPrevious = viewModel::previous,
                                onScrub = viewModel::scrub,
                                onSeek = viewModel::seekToFraction,
                                onToggleTimeMode = viewModel::toggleTimeMode,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onGoToTrack = viewModel::goToTrack,
                                onSetOrder = viewModel::setActiveOrder,
                                onVoiceOver = viewModel::announceCurrentTrack,
                                onVoiceOverLong = viewModel::announceQueuePosition,
                                onToggleShuffle = viewModel::toggleShuffle,
                                onCycleRepeat = viewModel::cycleRepeat,
                                onSetVolume = viewModel::setVolume,
                                onToggleMute = viewModel::toggleMute,
                                onSetSleepTimer = viewModel::setSleepTimer,
                                onToggleWebServer = viewModel::toggleWebServer,
                                onOpenEqualizer = { screen = Screen.EQUALIZER },
                                onReadSharedGroups = viewModel::readSharedGroups,
                                onApplyTags = viewModel::applyTags,
                                onSelectVault = viewModel::selectGroup,
                                // Tapping a ribbon tile manages that tile. It reaches the same
                                // named screen the dock does, so it passes the same prompt.
                                onOpenVault = { id ->
                                    tracksFromRibbon = true
                                    if (vaultLocked) {
                                        vaultDestination = id
                                        askingForVault = true
                                    } else {
                                        viewModel.openGroup(id)
                                        screen = Screen.TRACKS
                                    }
                                },
                                onOpenDock = {
                                    if (vaultLocked) {
                                        vaultDestination = null
                                        askingForVault = true
                                    } else {
                                        screen = Screen.LIBRARY
                                    }
                                },
                                onOpenSettings = { screen = Screen.SETTINGS },
                            )

                            Screen.LIBRARY -> LibraryScreen(
                                state = state,
                                onOpenGroup = { id ->
                                    tracksFromRibbon = false
                                    viewModel.openGroup(id)
                                    screen = Screen.TRACKS
                                },
                                // Creating a group leaves you in the library: the new row appears
                                // in the list you are already looking at, ready to be renamed.
                                onCreateGroup = viewModel::createGroup,
                                onUpdateGroup = viewModel::updateGroup,
                                onGroupModes = viewModel::setGroupModes,
                                onDeleteGroup = viewModel::deleteGroup,
                                onClose = { screen = Screen.PLAYER },
                                miniPlayer = miniPlayer,
                            )

                            Screen.TRACKS -> TracksScreen(
                                state = state,
                                onImport = { uris ->
                                    viewModel.importAll(uris, state.settings.deleteOriginals)
                                },
                                onPlay = { track ->
                                    viewModel.playTrack(track)
                                    screen = Screen.PLAYER
                                },
                                onSetFavorite = viewModel::setFavorite,
                                onDelete = viewModel::delete,
                                onReadSharedGroups = viewModel::readSharedGroups,
                                onApplyTags = viewModel::applyTags,
                                onClose = {
                                    screen = if (tracksFromRibbon) Screen.PLAYER else Screen.LIBRARY
                                },
                                miniPlayer = miniPlayer,
                            )

                            Screen.SETTINGS -> SettingsScreen(
                                state = state,
                                onLockOnLaunch = viewModel::setLockOnLaunch,
                                onLockOnPlay = viewModel::setLockOnPlay,
                                onLockOnDock = viewModel::setLockOnDock,
                                onBlockScreenshots = viewModel::setBlockScreenshots,
                                onShowSeeker = viewModel::setShowSeeker,
                                onShowVaultCounts = viewModel::setShowVaultCounts,
                                onVoice = viewModel::setVoice,
                                onVoicePart = viewModel::setVoicePart,
                                onRequireOutputDevice = viewModel::setRequireOutputDevice,
                                onRequiredDevice = viewModel::setRequiredDevice,
                                onPreferredDevice = viewModel::setPreferredDevice,
                                onClose = { screen = Screen.PLAYER },
                                miniPlayer = miniPlayer,
                            )

                            Screen.EQUALIZER -> EqualizerScreen(
                                state = state,
                                onEnabled = viewModel::setEqualizerEnabled,
                                onPreset = viewModel::setEqualizerPreset,
                                onBand = viewModel::setEqualizerBand,
                                onReset = viewModel::resetEqualizer,
                                onAutoEq = viewModel::setAutoEq,
                                onClearAutoEq = viewModel::clearAutoEq,
                                onDismissAutoEqError = viewModel::dismissAutoEqError,
                                onNormalizeVolume = viewModel::setNormalizeVolume,
                                onClose = { screen = Screen.PLAYER },
                                miniPlayer = miniPlayer,
                            )
                        }
                    }
                }
            }
        }
    }

    /** The activity is `singleTask`, so a second share arrives here rather than in a new one. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingAudio(intent)
    }

    /**
     * Audio arriving from another app, by ACTION_SEND, ACTION_SEND_MULTIPLE or ACTION_VIEW.
     *
     * The intent is emptied once read so the same files cannot be imported twice — `singleTask`
     * hands the same instance back, and the launch intent sticks around on the activity.
     */
    private fun consumeIncomingAudio(intent: Intent?) {
        if (intent == null) return
        val sources = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                ).orEmpty().filterNotNull()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (sources.isEmpty()) return

        intent.action = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        viewModel.importShared(sources)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground()
        // Enrolment can be added or removed while the app sits in the background.
        viewModel.refreshBiometrics()
        // Background work that can wait is told it no longer has to.
        viewModel.setOnScreen(true)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onBackground()
        viewModel.setOnScreen(false)
    }

    private fun askToUnlock(onMessage: (String?) -> Unit) {
        onMessage(null)
        Biometrics.authenticate(
            activity = this,
            title = "nullplayer",
            subtitle = "Unlock to reach the vault.",
            onSucceeded = AppLock::unlock,
            onFailed = onMessage,
        )
    }
}

/** What the app is while it is locked: a name, a reason, and one way forward. */
@Composable
private fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(ComposeColor(0xFF0B0B0D)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Text(
                text = "nullplayer",
                color = ComposeColor(0xFFE8E8EA),
                fontSize = 16.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message ?: "Locked",
                color = ComposeColor(0xFF8B8D94),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Unlock",
                color = ComposeColor(0xFF43B061),
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onUnlock() }
                    .padding(horizontal = 26.dp, vertical = 12.dp),
            )
        }
    }
}

private val NullPlayerColors = darkColorScheme(
    primary = ComposeColor(0xFF43B061),
    background = ComposeColor(0xFF0B0B0D),
    surface = ComposeColor(0xFF141519),
    onBackground = ComposeColor(0xFFE8E8EA),
    onSurface = ComposeColor(0xFFE8E8EA),
)

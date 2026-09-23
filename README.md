# nullplayer

An Android music player whose library lives in a private, encrypted vault, and whose screen never
names a track — tapping where it would be says it out loud instead.

## The idea

Three rules drive the whole design:

1. **A track that goes in stops existing elsewhere on the phone.** Not in the gallery, not in a file
   manager, not in another app's media picker, not in a cloud backup.
2. **Titles and albums are never rendered where anyone else can see them.** Not on the player, not
   in the notification, not on a car head unit, not on a smartwatch. The exception is a vault's
   own screen — the one for managing what is on it, behind a biometric prompt by default —
   because a library you cannot read is a library you cannot manage. (A setting can put the
   sleeve and the title on the player too, for a phone that is nobody else's business; it is
   off until asked for.)
3. **Everywhere else the information is still there when you want it** — spoken, once, by
   VoiceOver: a tap on the player's big number.

## Using it

The player fills the screen rather than drawing a device in the middle of it. The vault ribbon and
the progress bar stretch to whatever width there is, the control rows stay anchored at the bottom,
and past a comfortable width — a landscape phone, a tablet, a freeform window — the whole thing
splits into two columns — the ribbon stood upright down the left edge, one tile wide, and the
controls taking everything to the right of it — instead of growing a band of empty space down the
sides.

The hero readout is a queue position and a clock, because a title is the one thing it may not be.

| Control | Action |
| --- | --- |
| Centre button | Play / pause |
| Skip buttons | Previous / next track |
| Hold a skip button | Rewind / fast-forward |
| Progress bar | Drag or tap to seek |
| Shuffle | Play the vault in a random order |
| Repeat | Off → the whole vault → this track |
| Timer | Stop playing after 15 / 30 / 45 / 60 / 90 minutes; badged with the minutes left |
| Volume | Opens a slider in place, with a mute button that remembers the level |
| Equalizer | Presets and per-band faders; the import icon takes an AutoEQ profile. Crossfeed and volume normalization live here too |
| The big number | Tap to speak the current track; hold for "track 4 of 96" and the length |
| Vault ribbon | Under the top bar. Swipe to switch the queue; tap a tile to manage it |
| Top left | The upload server; a red dot means somebody is connected. Its address once running |
| Top right | The vaults, the equalizer, settings |
| Bottom row | Tag, order, volume, the sleep timer |

The ribbon is the only thing on the player that carries words, and it carries two: the tile's name
and how much is on it. Both the item counts and the progress bar can be switched off in settings.

Tiles are filled with their own colour, so the text on them is black or white by whichever gives
the better WCAG contrast ratio against the fill actually behind the letters — a fixed light-on-dark
rule breaks the moment somebody picks amber.

Transport sits in the middle of the screen with the progress bar under it, and the button row is
pinned to the bottom edge so it is always in the same place under the thumb. The ribbon is a
carousel: chips are a fixed width and the row is padded by half a viewport either side, so the
selected tile — including the vault, which always leads — sits in the centre rather
than against an edge.

Swiping is what selects. Whatever the snap settles on becomes the queue, so the tile in the middle
of the screen is always the one playing and choosing costs one gesture rather than a gesture and a
tap. That leaves the tap free to mean "manage this one", which opens the tile's own screen — behind
the same biometric prompt the library sits behind, and backing out of it returns to the player
rather than to the library you never went through.

Opening a vault is the only place the library can be managed, and the only screen that names
anything. Rows carry the title, the artist, the album, the year and the length, and each one has
a play button that starts that track and drops you back on the player. VoiceOver is not offered
here — this screen already prints what it would read — so the player's readout remains the way
to hear a track without looking.

Every row carries a checkbox, always: tagging and bulk deletion are what the screen is for, so the
selection is standing rather than something to switch on first. Tapping a row body ticks it.

Deleting is per-track: the bin on a row, or check off several and delete them together.
Both ask first, because there is no copy anywhere else. There is deliberately no "erase
everything" — a single button that empties an unrecoverable vault is a mis-tap waiting to happen.

## The vault, and groups

There is one vault. Everything lives in it, and it is the first tile on the ribbon — light grey,
always there, always the whole library.

Groups are tags laid over it. A track can be in any number of them or in none, and a group is a
view rather than a home: deleting one unfiles its tracks and nothing leaves the vault. That is why
deleting a group needs no confirmation while deleting a track still does.

The **+** beside the group count makes one immediately, with no dialog in the way: it is named
after its cardinal — the second is "Group #2" — and takes the next colour off the palette. It
leaves you in the library, where the new row is already on screen. The pencil on a row renames it
and opens a hue-and-shade picker for anything the eight presets do not cover.

Tagging happens where the tracks are: open the vault or a group, tick what you want, then *Tag*.
A group is ticked when every selected track is already in it, so a tap reads as "put all of these here" or
"take all of these out" — a per-track toggle would need a third state to be honest about a mixed
selection.

Tapping a row opens it to show what is inside. That is browsing, not selecting: the level-meter
mark shows the tile the player is drawing its queue from, and only the ribbon moves it, so a group
never stops music coming out of somewhere else. New music lands in the vault and is tagged into a
group afterwards — a group is a view over the vault, so *Add music from this device* appears on the
vault alone.

Selecting a tile changes the queue outright rather than filtering one big list, which is what keeps
shuffle and "track 4 of 96" honest.

## Getting music in

**From the device.** Open a vault → *Add music from this device*. This is the system file picker,
so it can reach internal storage, an SD card, Drive, Dropbox, anything with a documents provider.

**From another app.** Share audio to nullplayer, or open an audio file with it — `ACTION_SEND`,
`ACTION_SEND_MULTIPLE` and `ACTION_VIEW`. It lands on the selected vault and says so along the
bottom of the player, because you were looking at a different app when you sent it and there is
otherwise no way to find out where it went.

Metadata is read through a file descriptor rather than `setDataSource(Context, Uri)`, which refuses
a good many `content://` URIs — a share from another app, or anything MediaStore serves — with a
bare EINVAL.

**From a browser.** The globe in the top bar, or Settings → *Web management*. A red dot on the
globe means somebody is on the page right now, and the info button says how many. "Connected"
means present, not merely admitted: a session is held against the moment it was last heard from
and ages out after forty-five seconds, so a browser closed without logging out stops being
counted. The manager page beats every fifteen seconds for exactly this reason — nothing else on
it polls, so a page left open and idle would otherwise drop out from under someone still looking
at it.

While the server is running an info button appears beside the globe — the one place the address
lives — holding it
and a six-digit PIN; open
the address on a computer on the same network, enter the PIN, and drag files onto the page. Uploads
run one at a time with a progress bar each, and the page doubles as a library manager. They land on
whichever vault is open.

The switch is on the player, not buried in settings, because it is the one setting that costs
something while it is on — and the globe glows for as long as it does. The server only listens
while the toggle is on. It is plain HTTP on a local address, so treat it as "trusted network only".

**Five wrong PINs and the server switches itself off**, stored preference and all, and says so on
the player. Six digits is only a million guesses, which a script on the same LAN would work through
given time; rather than slow that down with a backoff that grinds on unattended, the guesser gets
five tries, total. Turning it back on is a deliberate act at the phone, and it mints a fresh PIN —
so an interrupted attempt starts again from a million.

## Settings

Reached from the gear in the player's top bar. Everything here can be left alone.

**Biometrics.** Four independent locks, all backed by `BiometricPrompt` with device credential as
the fallback:

* *Unlock the app* — asked on launch, and again after thirty seconds in the background. The grace
  period exists because the file picker stops the Activity, and being re-prompted on the way back
  from choosing a file teaches people to stop reading the prompt.
* *Unlock playback* — asked every time the music starts or resumes, wherever the press came from.
  The unlock is spent the moment playback stops, so a pause, the end of the queue, or the
  headphones coming out all mean the next press asks again.
* *Unlock the library* — asked before the vaults open, and **on by default**. They are the only
  screens that name tracks and the only ones that can delete them, and there is no copy anywhere
  else. Like the app lock, it holds for the session and re-arms after thirty seconds in the
  background.
* *Unlock settings* — asked before this screen opens. It is the lock on the other three: without
  it, anyone holding the phone can walk in here and switch them off. Off by default, and it holds
  and re-arms the same way the library lock does.

The switches grey out when nothing is enrolled, and a lock already switched on fails open if the
enrolment is later removed — a phone that could not authenticate would otherwise be a phone locked
out of a vault with no export path.

**Haptics.** *Feel the music* makes the phone vibrate with what it plays. How strongly follows
the system's media vibration setting. The switch is disabled, and says why, on a phone whose
audio has no haptic channels.

**Audio output.** *Only play to headphones* refuses to start unless a private listening device is
connected: any headset, or one particular device chosen from the list. Whatever is stored stays in
the list while it is unplugged, marked as such, so the setting cannot be silently rewritten by a
stray tap. *Preferred output* picks where the music goes when several devices are connected, and is
re-applied whenever that device reconnects.

The rules are enforced in `PlaybackGate`, and every controller is held to them — the play button,
the notification, a Bluetooth remote, a car head unit. Only *starting* is guarded: pause, seek and
skip always work, because a gate that could trap the music playing would be worse than no gate.

**The equalizer** is ours rather than the device's. `EqualizerProcessor` is a Media3
`AudioProcessor` spliced into ExoPlayer's audio sink, running a cascade of biquad sections built
from the Audio EQ Cookbook forms. It replaced `android.media.audiofx.Equalizer`, which could only
offer the handful of bands a given phone happened to ship — usually five — could not express a
parametric profile at all, and had to be rebound every time the sink was rebuilt and handed out a
new audio session.

Two things can define the curve and only one can win. The faders are ten octave-spaced ISO bands
realised as peaking filters, and a flat band is dropped rather than realised as a zero-gain
section, since a section that does nothing still costs arithmetic on every sample. A pasted
**AutoEQ profile** takes over while it is loaded: its filters sit at centres and Qs that ten fixed
faders cannot represent, so rather than draw a curve that is not the one playing, the profile is
listed where the faders would be — under the same heading, because it is the same question
answered a different way. It arrives through the import icon on that heading, and the bin on the
profile puts the faders back.

`AutoEqParser` reads the ParametricEQ dialect AutoEQ publishes — `Preamp:` plus `Filter N: ON
PK|LSC|HSC Fc … Gain … Q …`, which is every filter type a real export contains. It scans the whole
paste rather than matching whole lines, so a config that arrives with its line breaks eaten still
loads; what it will not do is silently accept a paste that yielded nothing, because a config that
does not parse must not displace the curve already playing.

The preamp is not decoration. AutoEQ profiles boost far more often than they cut, and a curve with
+9 dB of bass clips on loud material unless the signal is pulled down by at least as much as its
tallest peak first. A published `Preamp:` line is used as given; a curve typed in by hand gets one
computed for it.

**Crossfeed** is a third link in the same chain, behind the curve. A record is mixed on speakers,
where each ear hears both channels — the far one a few decibels quieter below about 700 Hz,
shadowed above it, and a couple of hundred microseconds late — and headphones take all of that
away, which is what makes a hard-panned sixties mix tiring on them. `CrossfeedProcessor` puts it
back with the Bauer arrangement bs2b made standard: one first-order low-pass per channel, whose
passband delay is the head's width, fed across and subtracted from its own side by the same
amount, so the middle of the image passes through untouched and only the difference between the
sides is narrowed. The three strengths are bs2b's own — *Natural* at 700 Hz and 4.5 dB, *Chu Moy*
at 6 dB, *Meier* at 650 Hz and 9.5 dB — and moving between them slides rather than steps. It rides
the equalizer's switch like normalization does, does nothing to mono or surround, and by default
steps out of the chain while nothing is plugged in, since on a speaker it would be narrowing an
image the room has already collapsed. (Android reports Bluetooth speakers and Bluetooth headphones
alike, which is what the *Only on headphones* switch is for.)

**Haptics** are the platform's own `HapticGenerator` (Android 12+), an audio effect on the
player's session that works out the vibration from the audio as it plays. Nothing is analysed or
stored ahead of time. The effect writes into the haptic channels of the audio stream, so two
things have to line up: the phone's audio path has to carry haptic channels
(`AudioManager.isHapticPlaybackSupported()`), and the track has to be opened with them unmuted.
Media3 has no switch for the second, so `HapticTracks` sets it on the `AudioTrack.Builder`
Media3 hands over just before building. Flipping the switch mid-track seeks in place, so the
sink opens a new track under the new setting. Few phones have haptic channels, and on those that
do the channels belong to the built-in output, so output routed to Bluetooth or USB headphones
will usually play without vibration.

**The sleep timer** is not persisted. One that survived a force-stop and silently paused the music
the next morning would be a bug, not a feature. `PlaybackService` holds the deadline and does the
pausing, so it keeps counting with no Activity on screen.

**VoiceOver rides the media stream**, which is what text-to-speech plays on, so muting the music
mutes the announcements with it. Since VoiceOver is the only way this app will tell you what a
track is, a press that made no sound and gave no reason would be the worst thing it could do — the
player says "Media volume is muted." along the bottom instead.

**The voice** VoiceOver speaks with can be chosen from whatever the phone's speech engine offers
for its language — picking one reads a line back so the choice can be judged by ear. Engines list
several hundred voices across every language they ship, so the list is filtered to the one the
phone is set to; without that it is not a setting, it is a haystack.

**The player** section is where rule 2 can be bent, one switch per place. *Show track info*, off
by default, puts the sleeve from the track's tags — read out of the encrypted file on demand,
never stored apart from it — on the player with the title and artist under it, above the queue
position, and the same three things on the mini player's strip. In landscape the sleeve takes a
column of its own between the ribbon and the controls. *Name the track in the notification*, also
off by default, does the same for the media session: the notification, the lock screen, and a
watch or a car. Each changes its own place alone. *Show the ribbon* takes the tile carousel off
the player, after which a tile is picked with the play button on its row in the library. The
last two are on by default: *Show the seeker bar* hides the scrub bar and the times with it, and
*Show item counts* drops the "12 items" line from the ribbon, leaving only names.

**Pulling the headphones out** pauses, via `ACTION_AUDIO_BECOMING_NOISY`. ExoPlayer can do that
pause itself, but the receiver in `PlaybackService` also spends the biometric unlock and cuts off
VoiceOver — which would otherwise read a track title out of the phone's speaker to the room.

## How the vault works

```
import → read tags → AES-256-CTR → filesDir/vault/<uuid>.bin
              ↓
         Room database (titles, artists — never shown, only spoken)
```

* **Location.** `filesDir/vault/` is app-private internal storage. MediaStore does not index it, no
  other app can read it, and `dataExtractionRules` excludes it from cloud backup and device
  transfer. Filenames are random UUIDs, so even the names leak nothing.
* **Encryption.** Every file is AES-256-CTR, keyed from the Android Keystore — the key material
  never enters the app's process memory. CTR is a deliberate choice over GCM or CBC: it is a stream
  cipher, so `VaultDataSource` can start decrypting at any byte offset without touching the bytes
  before it. That is what makes seeking work without ever writing a plaintext copy to disk.
* **The notification.** ExoPlayer merges ID3 tags and embedded artwork into the metadata that the
  notification, Bluetooth, Wear and the Assistant all read. `PlaybackService` wraps the player in a
  `ForwardingPlayer` whose `getMediaMetadata()` returns a constant, which closes that leak for every
  consumer at once.

## Building

Needs Android Studio, or a command-line SDK with platform 35 and build-tools 35.0.0.

```
./gradlew :app:assembleDebug
```

Android Studio writes `local.properties` for you on first open. From the command line, point it at
your SDK yourself:

```
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

Min SDK 26 (Android 8.0), target 35.

## Layout

```
data/     Track + Group + TrackGroup + Room, VaultCrypto (Keystore + CTR),
          VaultRepository (import, tagging), VaultDataSource (decrypt-on-seek), Settings
playback/ PlaybackService (MediaSessionService), PlayerViewModel, VoiceOver (TTS), RepeatMode,
          PlaybackGate (who may start the music), AudioOutputs (what is plugged in),
          AudioEffects (the equalizer's curve), EqualizerProcessor (the audio-path DSP),
          GainProcessor (levelling), CrossfeedProcessor (headphones as speakers),
          Biquad (cookbook sections), ParametricEq + AutoEqParser (AutoEQ profiles),
          TrackScan + TrackScanner (the background loudness sweep),
          LoudnessMeter (LUFS), HapticEngine (the platform haptic generator),
          SleepTimer (the deadline)
security/ Biometrics (the prompt), AppLock (is the app, the vault, or settings unlocked)
ui/       PlayerScreen, LibraryScreen (the vault and its groups), TracksScreen (one list),
          SettingsScreen, EqualizerScreen, ColorPicker, Panels, Glyphs (the drawn marks)
web/      VaultWebServer (NanoHTTPD + PIN auth), WebServerController
assets/   manager.html — the drag-and-drop uploader
```

## Known trade-offs

* **The track list and the web manager both show titles.** They are the two management
  surfaces — one behind a biometric prompt, one PIN-gated and off by default — and managing a
  library blind is not workable. If you want them opaque too, render only durations in `TrackRow`
  and `manager.html`.
* **There is no way to empty the vault in one action**, by design. Deleting a large library means
  selecting it and confirming. Uninstalling the app, or clearing its data, still takes everything.
* **Database migrations are written by hand and never destructive.** Version 2 introduced vaults;
  version 3 turned them inside out into one vault plus groups, each old vault becoming a group with
  the same tracks filed under it. The rows are the only index into an encrypted store with no
  export path, so dropping them on a schema change is not an option.
* **The server dies with the app process.** Android may reclaim it in the background. Keep the app
  foregrounded while uploading, or promote it to a foreground service if you want it always-on.
* **The notification permission is asked for once and never again.** Declaring
  `POST_NOTIFICATIONS` in the manifest is what makes the request legal, not what answers it —
  since Android 13 it is a runtime grant. Dismissing that dialog is not a denial, so asking on
  every launch means prompting on every launch for ever. If it was dismissed, playback still
  works; the media notification comes back from the system's app settings.
* **"Delete originals after import" is best-effort.** Many document providers refuse deletion; the
  vault copy is made either way, and a refusal is logged rather than surfaced.
* Rotating the Keystore key or clearing app data makes existing vault files unreadable. There is no
  export path by design.

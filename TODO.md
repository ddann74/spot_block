# To do

Planned, not yet built. Nothing in this file has any code behind it yet.
Both features below rely on the same authorized, deliberate boundary
change recorded in README.md's **Design philosophy** section
(2026-08-09): device-level, local-only audio control during a detected
ad is in scope; Spotify's own stream, network traffic, and ad-completion
accounting are still never touched, unconditionally.

## Auto-mute during ads - BUILT (2026-08-09), unverified on a real device

**What it does:** when an ad is detected, `AdAudioController` requests
transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`), which causes Spotify
to receive a focus-loss callback and pause/duck its own playback -
standard behavior for any well-behaved media app. When the ad clears, the
focus request is abandoned and Spotify reacquires focus and resumes.

**Design correction made during implementation:** the original sketch
above (now superseded) called for `AudioManager.setStreamVolume()` on
`STREAM_MUSIC` directly. Checking Android's own documentation
(developer.android.com/media/platform/output) before writing code turned
up that this is explicitly discouraged: `AudioManager` mixes every app
sharing a stream together, so muting `STREAM_MUSIC` directly would
silence *every* app using that stream, not just Spotify. Audio focus is
the real, targeted mechanism - this is why "check real docs before
implementing an open question" mattered here, not just as a formality.

**Still true from the original reasoning:** this never touches Spotify's
own stream, network traffic, or ad-completion accounting - Spotify still
plays every ad in full and gets credited for it, it just isn't the app
with audio focus while that happens. See `AdAudioController`'s doc
comment and README.md's Design philosophy section for the full boundary
statement.

**What's actually built:**
- `app/src/main/java/com/spotblock/app/audio/AdAudioController.kt` -
  `startMuting()`/`stopMuting()`, both idempotent (safe to call every
  accessibility event without their own dedup tracking), handles both
  the modern `AudioFocusRequest` API (26+) and the legacy
  `requestAudioFocus(listener, streamType, durationHint)` path (24-25,
  since minSdk is 24).
- `SettingsRepository.isAutoMuteEnabled` - off by default, same
  reasoning as `isDiagnosticLoggingEnabled`.
- Wired into `SpotifyAdSkipService`: starts on first ad detection
  (alongside the existing `hasAttemptedCurrentAd` gate), stops when the
  ad clears, AND stops on `onInterrupt`/`onUnbind` - without that last
  part, disabling the service mid-ad would leave Spotify permanently
  ducked/paused with nothing left to ever release the focus request.
- `StatsRepository.recordMuteOutcome()` / `MuteOutcome` (`ENGAGED`,
  `FOCUS_REQUEST_DENIED`) and matching Stats screen counters/UI toggle,
  mirroring the existing `SkipOutcome`/`DownloadOutcome` pattern.
- Diagnostic Log gets a new `MUTE` tag paralleling `SKIP`/`DOWNLOAD`.

**What's genuinely verified vs. not:** every file above was compiled
(via a standalone Kotlin 1.9.24 compiler run against a real Android 14
API jar - `org.robolectric:android-all` from Maven Central, since this
sandbox can't reach `dl.google.com` to run the actual Gradle/Android
build) and type-checks correctly against the real
`AudioManager`/`AudioFocusRequest`/`AudioAttributes` API surface. That's
real, meaningful verification - it is NOT the same as confirming Spotify
actually pauses/ducks in response on a real device, which still needs a
real-device diagnostic-log session before this can be called "confirmed"
the way the ad/skip keyword defaults now are. `MainActivity.kt`'s two-line
UI wiring couldn't be compiled the same way (needs the Gradle-generated
`ActivityMainBinding` class) but follows the exact existing pattern of
three already-working switches.

**Resolved open questions (were listed here, now answered):**
1. ~~Does `setStreamVolume` need `MODIFY_AUDIO_SETTINGS`?~~ Moot - this
   no longer calls `setStreamVolume` at all, see above.
2. ~~Manual volume change during the mute window?~~ Moot for the same
   reason - there's no captured/restored volume value anymore, only a
   focus request that's held then released.
3. **Still open:** no visual overlay indicator for "currently silencing an
   ad" - Stats-only feedback for now (`autoMuteEngagedText`/
   `autoMuteFocusDeniedText`), consistent with how Skip/Download outcomes
   are surfaced. Add a visual indicator later if Stats-only turns out to
   be insufficient in practice.

## Play a local song during ads (instead of muting)

**What it does:** the same ad-detected/ad-cleared reaction as auto-mute
above, but instead of going silent, plays a track from the user's own
locally stored music for the duration of the ad, then hands audio back to
Spotify once the ad clears.

**Not literal audio layering.** "Insert a song over the top of the ad
while it's playing" reads like mixing two audio streams simultaneously -
that's not what this should do; two things playing at once through one
speaker just sounds like noise. The actual mechanism: request Android
audio focus (which causes Spotify to duck/pause its own output - normal
OS-level behavior, nothing Spot Block does to Spotify directly), play the
local track, then abandon focus so Spotify resumes. Net effect for the
listener - ad audio replaced by their own music - without a messy overlay.

**Doesn't need ad-length detection.** Reacts to the same "ad
detected"/"ad no longer detected" signal `AdDetector`/`SpotifyAdSkipService`
already produce, the same as auto-mute above - starts and stops based on
live detection state, not a pre-parsed duration. (Ad length/countdown
*is* present as unparsed text in `screenTexts` if a future feature
actually needs it for something else, e.g. a Stats display - just not
required here.)

**Relationship to auto-mute (now built, see above):** this feature
supersedes auto-mute when both a track is configured AND enabled;
auto-mute (or normal Spotify audio) is the fallback when no local track
is configured/available. `AdAudioController` already holds the
focus-request/abandon lifecycle this needs (`startMuting`/`stopMuting`,
called from the same `SpotifyAdSkipService` ad-detected/cleared points) -
extend it to optionally start local playback alongside taking focus,
rather than adding a second, parallel controller that duplicates the
same lifecycle.

**Rough design:**
- New `SettingsRepository` fields: `isLocalMusicDuringAdsEnabled`
  (default **off**, same reasoning as every other toggle here) and a
  stored URI (or list of URIs, if multiple tracks should be picked
  from) for the local track(s) - chosen via the Storage Access Framework
  (`ACTION_OPEN_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE`) so this doesn't
  need a broad storage-read permission, just a persisted URI permission
  for whatever the user explicitly picks.
- Playback via `MediaPlayer` (simplest fit for "play this one local
  file") - `ExoPlayer` only worth it if this grows playlist/queue
  features later, not needed for a v1.
- Audio focus: request transient focus
  (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` or plain
  `AUDIOFOCUS_GAIN_TRANSIENT`, decide which during implementation - MAY_DUCK
  lets Spotify duck instead of fully pausing, which may or may not be
  desired here) on ad-detected, `abandonAudioFocus`/
  `AudioManager.OnAudioFocusChangeListener` release on ad-cleared.
- **Fade in/out on both transitions is a stated requirement, not a
  nicety** - the point of this feature is to preserve the listening
  experience through an ad break, and a hard cut in or out undermines
  that as much as the ad itself would. Concretely:
  - Local track fades **in** as Spotify is ducked/paused going into the
    ad (not after - the two should overlap, so there's no dead silence
    between "ad starts" and "local track audible").
    Symmetrically, local track fades **out** as Spotify's own audio
    fades back **in** once the ad clears - a crossfade on exit, not a
    fade-to-silence-then-resume.
  - Ramp via `MediaPlayer.setVolume(left, right)` on a `Handler`-driven
    step timer (or `ValueAnimator`), starting around ~300-500ms each
    way - longer than the original ~200-300ms placeholder, since too
    fast still reads as an abrupt cut; tune against how it actually
    feels on a real device rather than picking one number and locking
    it in blind.
  - Use an equal-power (roughly logarithmic/S-curve) fade rather than a
    plain linear volume ramp - a linear ramp on `setVolume` sounds like
    it changes loudness unevenly (perceived loudness isn't linear in
    the volume parameter), so a straight `0.0 -> 1.0` step schedule
    reads as a slower start / faster end than intended.
  - Normalize the local track's playback volume to roughly match
    typical Spotify listening volume before the fade-in even starts
    (a quiet track fading "in" to something quieter than the ad was,
    or a loud track overpowering it, both break the intended
    continuity) - likely a user-configurable gain/normalization value
    rather than a hardcoded guess, since source tracks vary widely in
    mastered loudness.
- Fallback behavior, in order, if this feature is enabled but can't
  actually play: no track configured -> fall back to auto-mute (if that's
  also enabled) or do nothing; configured track's URI no longer resolves
  (file moved/deleted, permission revoked) -> log a Stats entry saying so
  (mirroring `recordSkipOutcome`'s "found but disabled" honesty - never
  silently do nothing without saying why) and fall back the same way.
- New `DiagnosticLog`/`StatsRepository` entries paralleling the existing
  `SKIP`/`DOWNLOAD` pattern - e.g. a `LOCAL_AUDIO` tag with outcomes like
  `PLAYED`, `NO_TRACK_CONFIGURED`, `TRACK_UNAVAILABLE`.
- Needs its own real-device diagnostic-log validation before calling it
  confirmed, same standard as everything else in this file.

**Open questions to resolve during implementation, not now:**
1. Single configured track (loops if the ad break outlasts it) vs. a
   folder/playlist to pick from (random, or in order)? Simpler v1 is
   probably one track, looped - decide before building the picker UI.
2. Should the local track resume from where it left off across separate
   ad breaks (feels more like "your own background music"), or always
   restart from the beginning (simpler, more predictable)?
3. `AUDIOFOCUS_GAIN_TRANSIENT` (Spotify fully pauses, cleanest swap) vs.
   `..._MAY_DUCK` (Spotify keeps playing quietly underneath - defeats the
   point of not hearing the ad) - this isn't actually a toss-up once
   stated plainly; leaning `AUDIOFOCUS_GAIN_TRANSIENT`, but confirm
   against real behavior since focus handling varies by Android version.
4. Does this need `FOREGROUND_SERVICE`/media-session integration to play
   reliably from an `AccessibilityService` context, or does simple
   `MediaPlayer` playback work fine from there? Needs checking against
   real Android docs/a real device, not assumed.

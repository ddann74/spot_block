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

**Queue decision (resolved 2026-08-09):** a folder, not a single track -
picked once via `ACTION_OPEN_DOCUMENT_TREE`, every audio file inside
becomes the queue, played in filename order. Each new ad occurrence
restarts the queue at track 1 (not a "resume where the last ad left off"
model - simpler, more predictable, matches "an ad break gets some music,
not a persistent background stream"). If a track finishes before the ad
clears, the next one in the folder starts automatically; if the whole
queue finishes first, it wraps back to track 1 rather than going silent.

**Rough design:**
- New `SettingsRepository` fields: `isLocalMusicDuringAdsEnabled`
  (default **off**, same reasoning as every other toggle here) and a
  single persisted folder URI (`ACTION_OPEN_DOCUMENT_TREE`, via
  `ContentResolver.takePersistableUriPermission` so it survives reboots
  without re-picking) - not a list of individual file URIs, since the
  queue is "everything in this folder," not a hand-picked set.
- Enumerate the folder's contents via `DocumentFile.fromTreeUri(...).listFiles()`,
  filtered to audio MIME types, sorted by display name - this is the
  queue. Re-enumerate each time the setting is opened/changed (files
  may be added/removed on disk between ad breaks) rather than caching
  the list indefinitely.
- Playback via `MediaPlayer` (simplest fit here) with
  `setOnCompletionListener` advancing to the next queue index (wrapping
  to 0 at the end) and starting that track immediately - this is what
  makes it a queue rather than one looping file. `ExoPlayer` isn't
  needed for this; `MediaPlayer`'s completion callback is sufficient for
  sequential playback.
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
- **Requires a real foreground service - resolved 2026-08-09, see
  question 4 below.** `MediaPlayer` playback is started from
  `AdAudioController`, which is driven by `SpotifyAdSkipService` (an
  `AccessibilityService`) - there's no visible Activity on screen when
  this fires (Spotify is in front, not Spot Block). A dedicated
  `mediaPlayback`-typed foreground service must be started immediately
  before playback begins and stopped when it ends (or the whole ad
  window has cleared with the queue not currently playing) - not one
  long-lived service for the app's whole lifetime, only for the actual
  local-playback window, mirroring how `AdAudioController`'s focus
  request/abandon is itself scoped to the ad window.
- Fallback behavior, in order, if this feature is enabled but can't
  actually play: no folder configured -> fall back to auto-mute (if
  that's also enabled) or do nothing; folder configured but empty / no
  recognizable audio files inside -> its own distinct outcome, not
  collapsed into "no folder configured" (see question 5, resolved
  below) - same "never lie about the outcome" principle
  `SkipOutcome`/`DownloadOutcome`/`MuteOutcome` already follow; folder's
  persisted URI permission revoked -> also its own distinct outcome, not
  the same message as "empty folder" (a revoked permission is fixable by
  re-picking the folder; an empty folder is fixable by adding files -
  different fixes need different messages).
- New `DiagnosticLog`/`StatsRepository` entries paralleling the existing
  `SKIP`/`DOWNLOAD`/`MUTE` pattern - a `LOCAL_AUDIO` tag with an outcome
  enum covering (at least) `PLAYED`, `NO_FOLDER_CONFIGURED`,
  `FOLDER_EMPTY`, `PERMISSION_REVOKED`.
- Needs its own real-device diagnostic-log validation before calling it
  confirmed, same standard as everything else in this file.

**Open questions, resolved so far:**
1. ~~Single track vs. folder/playlist?~~ Resolved above: folder, in
   filename order, restarts at track 1 each new ad, wraps if it outlasts
   the ad break.
2. ~~Resume across ad breaks vs. restart?~~ Resolved above: always
   restarts at track 1.
3. ~~`AUDIOFOCUS_GAIN_TRANSIENT` vs. `..._MAY_DUCK`?~~ Already decided
   and built this way for auto-mute (`AdAudioController` uses plain
   `AUDIOFOCUS_GAIN_TRANSIENT`), so this feature inherits that choice by
   extending the same controller rather than re-deciding it.
4. ~~Does this need `FOREGROUND_SERVICE`/media-session integration?~~
   Resolved via real research (developer.android.com/about/versions/17/changes/bg-audio,
   developer.android.com/develop/background-work/services/fgs/service-types),
   not assumed: **yes.** Two separate, compounding reasons:
   - Since Android 14 (this app's current `targetSdk`), any foreground
     service used for media playback must declare
     `android:foregroundServiceType="mediaPlayback"` and the app must
     hold the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission - a
     baseline requirement for this kind of FGS regardless of the point
     below.
   - Android's newer "Background Audio Hardening" (rolling out in
     Android 17 beta as of this research) specifically targets exactly
     this app's scenario: an app with no visible Activity starting
     audio playback from a background/service context. Apps must either
     be on-screen or run a proper `mediaPlayback` foreground service -
     there's no third option once that hardening is active on a user's
     device. Given that, building this correctly from the start (rather
     than "it happens to work today, deal with it breaking later") is
     the right call, independent of exactly which Android version a
     given install is running.
   - Per Google's own lifecycle guidance: keep the FGS active through
     transient interruptions (e.g. `AUDIOFOCUS_LOSS_TRANSIENT`, under
     ~10 minutes) rather than tearing it down and restarting on every
     brief pause; stop it for real once the ad clears and the queue
     isn't playing.
   - `ExoPlayer`/media3's `MediaSessionService` would handle a lot of
     this lifecycle automatically per Google's own recommendation - this
     may be reason enough to revisit "plain `MediaPlayer`, `ExoPlayer`
     only if this grows playlist features" above, since a real
     foreground-service + queue-advancement lifecycle is arguably
     already the more-than-trivial case that guidance was hedging on.
     Worth reconsidering at implementation time rather than treating
     the earlier "MediaPlayer is enough" call as final.

**Still open:**
5. What if the picked folder is empty or contains no recognizable audio
   files - resolved above to get its own distinct outcome
   (`FOLDER_EMPTY`), not collapsed into "no folder configured." Still
   open: the exact user-facing wording, and whether it's worth
   proactively validating the folder (and showing a warning) at pick
   time in Setup, rather than only discovering it's empty the first
   time an ad tries to use it.

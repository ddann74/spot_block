# To do

Planned, not yet built. Nothing in this file has any code behind it yet.
Both features below rely on the same authorized, deliberate boundary
change recorded in README.md's **Design philosophy** section
(2026-08-09): device-level, local-only audio control during a detected
ad is in scope; Spotify's own stream, network traffic, and ad-completion
accounting are still never touched, unconditionally.

## Auto-mute during ads

**What it does:** when `AdDetector` reports an ad is playing, automatically
silence the device's media volume; when the ad is no longer detected,
restore the volume to whatever it was right before muting.

**Why this is a different, lesser boundary than what the README currently
rules out.** The README's existing "no audio manipulation, no muting the
stream" line was written about *Spotify's own audio stream/playback* -
touching what Spotify sends, intercepting it, or interfering with its
ad-completion tracking, which would be a real step toward circumventing
Spotify's ad system, not just automating a tap. Device-level volume control
is different in kind: the ad still plays start to finish, at whatever volume
Spotify set it to, exactly as if nothing were watching - Spotify's own
playback and ad-completion accounting are untouched. The only difference
from a human not paying attention (or muting their phone themselves,
something anyone can already do with zero help from this app) is that it
happens automatically. Worth building on that basis, but this is a genuine
scope expansion from what shipped, so it needs its own README section (not
just quietly folded into the existing "no muting" line) explaining
precisely what it does and doesn't touch, the same way every other
feature here documents its own boundary.

**Rough design:**
- New `SettingsRepository` toggle, `isAutoMuteEnabled` - almost certainly
  default **off** until confirmed working against a real device, same
  reasoning as `isDiagnosticLoggingEnabled` defaulting off: an unrequested
  behavior change on first install is worse than an extra toggle to find.
- Target `AudioManager.STREAM_MUSIC` specifically (what Spotify actually
  plays over) - never `STREAM_RING`, `STREAM_NOTIFICATION`, etc.
- Capture the current `STREAM_MUSIC` volume the moment an ad is first
  detected (mirrors `hasAttemptedCurrentAd`'s "first detection of this ad"
  edge, in `SpotifyAdSkipService`), mute (`setStreamVolume(..., 0, ...)`
  or `adjustStreamVolume(ADJUST_MUTE, ...)`), then restore the captured
  value - not just "unmute" - once "ad no longer detected" fires. Restoring
  the literal captured value, not relying on whatever an unmute call
  defaults to, matters: those don't reliably agree.
- Needs its own real-device diagnostic-log validation before calling it
  confirmed - same standard the ad/skip keyword lists were just held to,
  not a different, lower bar just because it's a newer feature.

**Open questions to resolve during implementation, not now:**
1. Does `setStreamVolume`/`adjustStreamVolume` on `STREAM_MUSIC` need the
   `MODIFY_AUDIO_SETTINGS` permission on the SDK levels this app targets
   (min 24 / target 34)? Needs checking against real Android docs/a real
   device at implementation time, not assumed either way.
2. What should happen if the user manually adjusts volume *while* an ad
   is playing (and thus already muted by this feature) - restore the
   captured pre-ad volume anyway once the ad ends (overwriting their
   manual change), or treat a manual change during the mute window as the
   new value to restore? Either is defensible; needs a decision before
   writing the restore logic, not an implicit answer buried in whichever
   gets coded first.
3. Interaction with the existing overlay: does the floating Download
   button (or its container view) need any visual indicator that
   auto-mute is currently active, or is Stats-only feedback (a new
   "Muted for ad" stat, paralleling `recordSkipOutcome`) enough?

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

**Relationship to auto-mute above:** this feature supersedes auto-mute
when both a track is configured AND enabled; auto-mute (or normal Spotify
audio) is the fallback when no local track is configured/available. They
should share the same detection wiring in `SpotifyAdSkipService`, not
duplicate it - implement auto-mute first since it's the simpler primitive
this builds on (capture/restore volume, ad-detected/cleared lifecycle),
then extend rather than reimplementing that lifecycle.

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

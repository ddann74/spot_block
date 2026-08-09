# To do

Planned, not yet built. Nothing in this file has any code behind it yet.

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

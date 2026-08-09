# Spot Block

An Android accessibility-service app that watches Spotify's on-screen text for
signs an ad is playing and, if it finds one, tries tapping the Skip/Next
control - the same control you'd tap yourself. It never intercepts or
reverse-engineers Spotify's network traffic, never modifies Spotify's own
audio stream, and never acts on anything outside Spotify itself. It can
also (optionally, off by default) request audio focus to pause/duck
Spotify's own playback during an ad, and can optionally play music from a
folder you choose during that same window instead of silence - see
**Design philosophy** below for why that's a deliberate, authorized
exception, not a contradiction.

Planned (not yet built) work is tracked in [`TODO.md`](TODO.md).

## Design philosophy

Principles this project holds itself to:

1. **Only automate a tap you could already make yourself.** Nothing here
   does more than what a human could do by hand on the same screen.
2. **Never lie about the outcome.** `SkipOutcome`/`DownloadOutcome` are
   multi-state specifically so "tried and blocked" is never reported the
   same as "worked."
3. **Respect Spotify's own anti-skip design as intentional**, not a bug to
   route around - `BLOCKED_DISABLED` is the expected outcome on Free, not
   a failure state.
4. **Never touch Spotify's own audio stream, network traffic, or data** -
   no interception, no reverse-engineering, nothing that affects Spotify's
   own playback or ad-completion accounting.
5. **Never act outside the app's own configured scope** (target packages
   only; no reading or acting on anything else on the device).
6. **Heuristics must be transparent and correctable, not a black box** -
   every keyword list is user-editable without a rebuild, and Diagnostic
   Log exists specifically so a miss can be diagnosed and fixed.
7. **Never claim more than what's actually verified** - a keyword list is
   documented as "confirmed against a real device" only once it actually
   has been, with the real numbers, not before.
8. **A user-facing off switch for anything intrusive** - every added
   capability gets its own toggle, defaulting to the least-surprising
   state, rather than being bundled in as unavoidable.

**Authorized expansion (2026-08-09):** principle 4 originally read "never
modifies audio" without qualification. The project owner explicitly
authorized broadening it to allow *device-level, local-only* audio
control - built today as requesting transient audio focus during a
detected ad (causing Spotify to pause/duck itself, standard behavior for
any well-behaved media app - not a direct volume/stream manipulation),
and extending that to (optionally) play a locally-stored song during that
same window instead of silence, once a folder is picked (see `TODO.md`
for build details). Both stay inside every other
principle above unchanged: Spotify's own stream, network traffic, and
ad-completion accounting are never touched - Spotify still plays the ad
in full and gets credited for it exactly as if the app didn't exist. What
changed is narrowly what happens to the device's own concurrent audio
output during that window, which a user could already do manually (mute
Spotify, open a different player, switch back) - this only automates
that, per principle 1. Principle 4 is retired as originally worded; its
network/stream/data half is now covered by this list's other principles
(none of which changed), and its audio half is superseded by this note.

## Read this before installing

**Spotify's free tier deliberately disables Skip/Next/Previous during an ad.**
This is a documented, intentional anti-skip mechanism, not a bug - ads are
the actual exchange for free access, and Spotify designs against exactly
this kind of tool working via its own UI. That means, honestly:

- On **Spotify Free**, this app will often find the Skip control on
  screen but discover it's *disabled*, and log that instead of a fake
  "success" - though not always: one real session's diagnostic log
  showed 4 of 6 real ad occurrences blocked this way, and 2 genuinely
  tapped (see Known Open Items). Check **Stats** - if "Skip blocked" is
  climbing and "Skip tapped" stays at zero, that's Spotify's own
  anti-skip design working as intended, not a bug in this app.
- On **Spotify Premium**, there are no ads at all, so this app has nothing
  to do.
- This app deliberately does **not** modify Spotify's own audio stream,
  intercept or reverse-engineer its network traffic, or touch anything it
  sends your device - see **Design philosophy** above for the full list
  of boundaries, including one authorized, deliberate exception (local,
  device-only audio control during ads - muting, or optionally playing
  your own local music instead, both detailed in `TODO.md`) and why it
  doesn't cross the others.
- Using any tool to circumvent ads likely violates Spotify's Terms of Use.
  This only automates a tap you already have the ability to make yourself
  when the control is enabled; it does not, and is not intended to,
  guarantee ad-free listening on the free tier.

## How it works

- **Accessibility Service** (`SpotifyAdSkipService`) gets notified whenever
  Spotify's screen content changes, reads every piece of text currently on
  screen (via the accessibility node tree - no screenshots, no screen
  recording), and hands that off to a pure decision function.
- **`AdDetector`** decides, from that text alone, whether an ad is currently
  playing - a case-insensitive substring match against your configured **Ad
  Keywords**, same shape as TikTok Feed Filter's ad-keyword matching (a
  sibling project). Unlike a scrolling feed, Spotify's Now Playing/ad screen
  is a single view, so there's no "preloaded next item" text to accidentally
  match against.
- When an ad is detected, the service searches for a control matching one of
  your **Skip Control Labels**, then:
  - if it's found and **enabled**, taps it and logs "Skip tapped";
  - if it's found but **disabled**, logs "Skip blocked" - this is the
    expected outcome on Spotify Free, not a failure of this app;
  - if nothing matching is found at all, logs "Skip control not found" -
    usually a sign your Skip Control Labels need a wording tweak.
- Every outcome is written to **Stats**, and (if enabled) the **Diagnostic
  Log** additionally records the raw on-screen text for that screen - the
  detail actually needed to tune a keyword list.

## Silence Ads (off by default)

When enabled (**Setup > Silence Ads (Audio Focus)**), the moment an ad is
detected the app requests transient audio focus - the same mechanism a
navigation app's turn-by-turn prompt or a notification sound uses to duck
whatever's currently playing. Android delivers a focus-loss callback to
Spotify, which (being a well-behaved media app) pauses or ducks its own
playback in response. When the ad clears, the app releases focus and
Spotify reacquires it and resumes.

This never touches Spotify's own stream, network traffic, or volume -
see **Design philosophy** above for the full reasoning, and
`AdAudioController`'s doc comment in source for the implementation
detail on why this replaced an earlier, discarded design that would have
called `AudioManager.setStreamVolume()` directly (Android's own docs
recommend against that specifically because it affects every app sharing
a stream, not just Spotify).

**Not yet confirmed on a real device** - unlike the ad/skip keyword
defaults (see Known Open Items), this hasn't been validated against an
actual ad session yet. It compiles correctly against the real Android
API and the logic has been reviewed carefully, but whether Spotify
actually pauses/ducks the way expected needs a real diagnostic-log
session to confirm. Stats screen shows "Ads silenced (audio focus)" /
"Silence attempt denied" counters once you've tried it.

## Play Local Music During Ads (off by default)

An extension of Silence Ads above: instead of just going quiet, the app
can play music from a folder on your device for the length of the ad,
then hand focus back to Spotify once the ad clears.

1. In **Setup**, tap **Choose Ad Music Folder** and pick a folder
   containing audio files (via the system folder picker - no broad
   storage permission needed, just scoped access to that one folder,
   which persists across reboots).
2. Turn on **Play Local Music During Ads**. It supersedes plain Silence
   Ads whenever a folder is configured; if the folder becomes unavailable
   partway through (permission revoked, or it turns out to be empty),
   the app falls back to silence rather than pretending it played
   something, and Stats/Diagnostic Log record which of those happened.
3. Every audio file in the folder becomes the queue, played in filename
   order. Each new ad restarts the queue at the first track; if the
   queue runs out before the ad clears, it loops back to the start
   rather than going silent partway through.
4. Both transitions crossfade (roughly 350ms, equal-power curve) rather
   than cutting hard - Spotify fades out as the local track fades in
   going into the ad, and the reverse coming out of it.

Playback runs through a dedicated `androidx.media3` (`ExoPlayer` +
`MediaSessionService`) foreground service, required since Android has no
visible Spot Block screen on-screen when an ad starts (Spotify is the
foreground app) - see `TODO.md` for the full reasoning, including why
`ExoPlayer` was chosen over the legacy `MediaPlayer` API.

**Not yet confirmed on a real device**, same caveat as Silence Ads above
- and with one additional verification gap specific to this feature: the
`androidx.media3` dependency itself couldn't be compiled in this
sandbox (Google-Maven-only, unreachable here), so `AdMusicPlaybackService`
is unverified beyond careful manual review, unlike the rest of this
feature's code which did compile against the real Android API. See
`TODO.md`'s "What's genuinely verified vs. not" for the full breakdown.
Stats screen shows "Local music played" / "No folder configured" /
"Folder empty" / "Folder permission lost" counters once you've tried it.

## Download button (Spotify's own offline download)

A floating **Download** button is drawn over Spotify (the same accessibility
overlay window TikTok Feed Filter's Block/Download buttons use, so it needs
no separate "display over other apps" permission). Tapping it searches the
current screen for Spotify's own **"Download for offline" toggle** - a real
Spotify Premium feature - and taps it if found.

**What this deliberately is not**: this never downloads anything itself,
never writes a file, and never touches the network. It only finds and taps
a control Spotify already put on screen, the same as you tapping it
yourself - the actual download (and the DRM-protected offline file Spotify
manages) is entirely Spotify's own feature, requires Premium, and only
plays back inside the Spotify app. If you don't have Premium, or aren't on
a screen with a Download toggle (it lives on playlist/album/podcast
screens, not Now Playing), the button will find nothing and log "Download
control not found" rather than pretending to succeed.

Turn off **Show Floating Download Button** in Setup if you'd rather not
have anything drawn over Spotify at all - this doesn't affect ad-skip
detection, which keeps working either way.

## This is inherently heuristic - read this before relying on it

There is no official API for "is an ad currently playing" or "skip this ad."
This app reads on-screen text and taps on-screen buttons by pattern-matching
against their labels, same as TikTok Feed Filter's approach for a different
app. That means:

- **It will miss things whose wording doesn't match your configured
  keywords.** All three keyword lists (Ad Keywords, Skip Control Labels,
  Download Control Labels) are editable in the app without a rebuild for
  exactly this reason.
- **The default ad/skip keyword lists have now been confirmed against a
  real device** (one real session, 6/6 real ad occurrences correctly
  detected and skip-handled - see Known Open Items below for the exact
  numbers). The Download Control Labels default has not been exercised
  by real data yet, same open-item status as before. If something stops
  matching in a future session, turn on **Diagnostic Log**, let it
  happen, and check what text Spotify actually rendered - then add that
  wording to the relevant keyword list.
- **A Spotify UI update can silently break this** the same way a TikTok
  update could break the sibling project - if it stops working, the
  Diagnostic Log is the way to see what changed.
- **Silence Ads (audio focus) is unconfirmed on a real device** - built,
  compiles against the real Android API, but not yet run against an
  actual ad session the way the ad/skip keyword defaults have been. If
  you try it, check Stats' "Ads silenced" / "Silence attempt denied"
  counters and, ideally, a Diagnostic Log session afterward.
- **Play Local Music During Ads is also unconfirmed on a real device**,
  with a wider verification gap than the rest of this app - the
  `androidx.media3` dependency it's built on couldn't be compiled in this
  project's sandbox at all (unlike everything else, which did compile
  against a real Android API jar). See `TODO.md` for the exact
  file-by-file breakdown of what was and wasn't verified.

## Setup

1. **Open in Android Studio**: `File → Open`, select this project's root
   folder, let Gradle sync. `gradlew`/`gradlew.bat` are the genuine Gradle
   wrapper (not a stub), so `./gradlew build` from a terminal works too,
   without needing Gradle installed separately.
2. **Run it**, then in the app:
   - Tap **Open Accessibility Settings**, find "Spot Block" in the list, and
     switch it on.
   - Return to the app; **Accessibility Service** should read "Granted."
   - Leave **Enable Ad Skip Attempts** on (default).
3. Open Spotify and let an ad play. Check **Stats** afterward to see what
   actually happened.
4. If nothing gets detected, turn on **Enable Diagnostic Logging**, let
   another ad play, then **Share Diagnostic Log** (or read it directly) to
   see the raw on-screen text and tune **Ad Keywords** / **Skip Control
   Labels** to match.

## Known open items

- **The ad and skip keyword lists are now confirmed against a real
  device.** A real diagnostic log (2,523 lines, one real session) showed
  6 genuine ad occurrences. Every one was detected by the default
  `Advertisement` keyword (6/6, including a video-ad variant that
  renders `"Advertisement • 1 of 1"` with a bullet separator instead of
  the audio-ad format's comma - both still matched, since it's a
  substring check). Every one also found a Skip/Next control using the
  default labels (6/6, zero `CONTROL_NOT_FOUND`) - **2 were actually
  tapped, 4 came back `BLOCKED_DISABLED`.** That 2/6 "skip really was
  enabled" rate is itself a real finding worth having: it's not the
  rare exception the line below used to imply. Representative real
  on-screen text from that log is now in
  `AdDetectorTest.kt` (ad-related text only - the log's song/artist
  text was excluded from what's committed here since that's personal
  listening history, not needed to test ad detection). This was one
  real session on one real device/account/region - it confirms the
  defaults work, not that they're exhaustive; a UI variant, region, or
  Spotify version this session didn't hit could still need a keyword
  added later, the same way the sibling TikTok Feed Filter project's
  lists have grown over time.
- **Whether Spotify's Skip control is ever actually enabled during an
  ad** is answered above for one real session: yes, sometimes - 2 of 6
  real ad occurrences had it enabled, 4 didn't. Whether that ratio holds
  generally (by region, ad format, Spotify version, or Free vs. one of
  Spotify's other tiers) is still open - the Stats screen is the way to
  keep tracking it for your own account over time.
- **Spotify Lite / regional builds** may ship under a different package
  name than `com.spotify.music` - add it under Target App Packages in
  Setup if so.
- **The Download Control Labels default (`Download`) is still
  unconfirmed** - the real log analyzed above didn't include any use of
  the floating Download button, so this gap isn't closed the way the ad/
  skip keywords are. If it logs "control not found" while you're
  genuinely on a playlist/album screen with Premium active, check
  Diagnostic Log's `OVERLAY`/`DOWNLOAD` entries for the real on-screen
  text and add the actual wording.

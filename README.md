# Spot Block

An Android accessibility-service app that watches Spotify's on-screen text for
signs an ad is playing and, if it finds one, tries tapping the Skip/Next
control - the same control you'd tap yourself. It never modifies audio, never
intercepts or reverse-engineers Spotify's network traffic, and never acts on
anything outside Spotify itself.

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
- This app deliberately does **not** attempt anything beyond tapping an
  on-screen control that's already there for you to tap - no audio
  manipulation, no muting the stream, no modifying what Spotify sends your
  device. That's a real, considered boundary, not a missing feature.
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

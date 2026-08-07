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

- On **Spotify Free**, this app will very likely find the Skip control on
  screen but discover it's *disabled*, and log that instead of a fake
  "success." Check **Stats** - if "Skip blocked" is climbing and "Skip
  tapped" stays at zero, that's Spotify's own anti-skip design working as
  intended, not a bug in this app.
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
- **The default keyword lists are best-effort guesses, not confirmed
  against a real device.** Unlike TikTok Feed Filter (whose keyword lists
  were tuned against real diagnostic logs during development), this project
  has not yet been run against an actual Spotify installation. If ads don't
  get detected at all, turn on **Diagnostic Log**, let an ad play, and check
  what text Spotify actually rendered - then add that wording to Ad
  Keywords.
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

- **No keyword list here has been confirmed against a real device.** This
  is the single biggest gap versus the TikTok Feed Filter sibling project,
  whose keywords were tuned from real diagnostic logs during development.
  Expect to add wording after the first real use.
- **Whether Spotify's Skip control is ever actually enabled during an ad**
  (vs. always disabled on Free) is itself unconfirmed - it may vary by
  region, ad format, or Spotify version. The Stats screen is the way to
  find out for your actual account.
- **Spotify Lite / regional builds** may ship under a different package
  name than `com.spotify.music` - add it under Target App Packages in
  Setup if so.
- **The Download Control Labels default (`Download`) is also unconfirmed.**
  Same situation as the ad/skip keywords - if the floating Download button
  logs "control not found" while you're genuinely on a playlist/album
  screen with Premium active, check Diagnostic Log's `OVERLAY`/`DOWNLOAD`
  entries for the real on-screen text and add the actual wording.

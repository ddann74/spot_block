package com.spotblock.app.audio

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * A real foreground service (declared `android:foregroundServiceType="mediaPlayback"`
 * in AndroidManifest.xml) hosting the ExoPlayer instance that plays local
 * audio during an ad. See docs/TODO.md's "Play a local song during ads"
 * design for the full reasoning; short version: `AdAudioController`
 * triggers this from `SpotifyAdSkipService` (an `AccessibilityService`),
 * and there is no visible Activity on screen at that moment (Spotify is
 * in front, not Spot Block) - Android's Background Audio Hardening
 * requires exactly this kind of app to be on-screen OR run a proper
 * `mediaPlayback` foreground service to start audio playback at all.
 * There's no third option.
 *
 * `MediaSessionService` (not a plain `Service`) specifically because
 * Google's own guidance names it as the component that "assists in
 * managing the playback lifecycle" for that hardening - even though this
 * app has no external `MediaController` client (no notification
 * transport controls or lock-screen integration is actually needed
 * here), the `MediaSession` this class creates satisfies that
 * system-level requirement, and `MediaSessionService` handles promoting
 * itself to a real foreground service (with the system-required
 * notification) automatically once the player starts actually playing -
 * this class does not call `startForeground()` itself.
 *
 * `AdAudioController` talks to this service directly in-process (via
 * [instance]) rather than through a `MediaController` - both live in the
 * same app/process, and there's no cross-process or system-UI control
 * surface this feature actually needs.
 *
 * `handleAudioFocus = false` (in the `ExoPlayer.Builder.setAudioAttributes`
 * call below) is deliberate: `AdAudioController` already owns the single
 * audio-focus request for the whole ad window (used for both the
 * mute-only and local-music paths) - letting `ExoPlayer` also request
 * focus here would be a second, redundant request for the same logical
 * action, and could interact with the first unpredictably.
 *
 * Compiled once for real (2026-08-09, first Gradle build) - caught one
 * genuine bug: `onDestroy()` originally used `mediaSession?.run { player
 * = null; ... }`, and `MediaSession` itself has a non-null `player:
 * Player` property that shadowed the outer class's nullable `player`
 * field inside that lambda's implicit receiver scope, so `player = null`
 * resolved to the wrong property and failed to compile ("Null can not be
 * a value of a non-null type Player"). Fixed by dropping the `run` block
 * and setting both fields explicitly with no implicit receiver. Still
 * not run on a real device - confirm a legitimate system notification
 * appears while local music plays (MediaSessionService's default
 * behavior, expected but not yet confirmed).
 */
class AdMusicPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
            .build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_ALL
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Replaces the queue with [uris] and starts playback from track 0 -
      * always a fresh queue/position for each new ad, per the resolved
      * "restart at track 1" design (docs/TODO.md) - never resumes
      * wherever a previous ad's playback happened to stop. Safe to call
      * even if a queue is already playing (e.g. a stray duplicate call);
      * it simply replaces the current queue. */
    fun playQueue(uris: List<Uri>) {
        val exoPlayer = player ?: return
        exoPlayer.setMediaItems(uris.map { MediaItem.fromUri(it) })
        exoPlayer.prepare()
        exoPlayer.seekToDefaultPosition(0)
        exoPlayer.volume = 0f // AdAudioController fades this up - see its fade-in logic
        exoPlayer.play()
    }

    fun stopQueue() {
        player?.stop()
    }

    /** 0f-1f, clamped. Used by AdAudioController's fade in/out ramp -
      * kept as a plain setter/getter pair here rather than exposing the
      * ExoPlayer instance itself, so callers outside this file never
      * need to import any media3 type. */
    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun currentVolume(): Float = player?.volume ?: 0f

    override fun onDestroy() {
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Same-process direct reference - see the class doc for why this
          * skips the MediaController indirection most media3 apps use.
          * Null until the service has actually been created (onCreate has
          * run) - a caller that starts the service and immediately reads
          * this may still see null for one brief window; see
          * AdAudioController's retry-on-next-event handling of that. */
        @Volatile
        var instance: AdMusicPlaybackService? = null
            private set
    }
}

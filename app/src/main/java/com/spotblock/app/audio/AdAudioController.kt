package com.spotblock.app.audio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import kotlin.math.cos
import kotlin.math.sin

/** Whether a mute attempt actually engaged, and why not if it didn't -
  * same shape as [com.spotblock.app.ad.SkipOutcome]: a failure is
  * reported honestly, never silently treated the same as success. */
enum class MuteOutcome { ENGAGED, FOCUS_REQUEST_DENIED }

/** Outcome of trying to start local-music-during-ads playback. `null`
  * (returned by [AdAudioController.startLocalMusic], not a member here)
  * is reserved separately for "the playback service hasn't finished
  * starting up yet, try again on the next event" - a transient,
  * self-resolving condition that isn't a real failure worth logging,
  * unlike every state in this enum, which is stable for the rest of the
  * current ad and IS worth recording exactly once. */
enum class LocalAudioOutcome { PLAYED, NO_FOLDER_CONFIGURED, FOLDER_EMPTY, PERMISSION_REVOKED }

/**
 * Silences Spotify during a detected ad by requesting transient audio
 * focus - deliberately NOT by calling AudioManager.setStreamVolume() on
 * STREAM_MUSIC. Android's own guidance
 * (developer.android.com/media/platform/output) recommends against
 * that: AudioManager mixes every app sharing a stream together, so
 * muting STREAM_MUSIC directly would silence every app currently using
 * it, not just Spotify - a real, considered reason to prefer audio
 * focus here, discovered by checking Android's actual documentation
 * rather than assuming the original TODO.md sketch (direct volume
 * manipulation) was right.
 *
 * Audio focus is the standard, targeted mechanism instead: requesting
 * AUDIOFOCUS_GAIN_TRANSIENT causes Android to deliver a focus-loss
 * callback to whichever app currently holds focus (Spotify, during an
 * ad), and well-behaved media apps - Spotify included - respond by
 * pausing or ducking their own playback. Abandoning focus lets Spotify
 * reacquire it and resume. This class never touches any stream's volume
 * directly - it only participates in the same focus-negotiation
 * contract every media app already implements.
 *
 * Also drives the local-music-during-ads feature ([startLocalMusic] /
 * [stopLocalMusic]): once focus is held (Spotify is quiet), this can
 * additionally start [AdMusicPlaybackService] playing a queue of local
 * files instead of leaving silence - see that class's doc comment for
 * why local playback needs its own foreground service, and
 * docs/TODO.md for the full feature design (queue = a user-picked
 * folder's audio files, filename order, restarts per ad).
 *
 * UNVERIFIED ON A REAL DEVICE: this sandbox has no Android SDK/emulator
 * access (same network restriction that blocks the Gradle/Android
 * build elsewhere in this project - see AdDetectorTest.kt's real-data
 * tests for the same caveat). The audio-focus portion of this file was
 * compiled against a real Android 14 API jar (org.robolectric:android-all,
 * fetched from Maven Central) to confirm it's valid Kotlin against the
 * real AudioManager/AudioFocusRequest API surface - a real, meaningful
 * check, but not the same as confirming Spotify actually pauses/ducks on
 * a real device. The local-music portion additionally references
 * [AdMusicPlaybackService], which depends on androidx.media3 - a
 * dependency only published on Google's Maven repo, unreachable from
 * this sandbox, so that portion could not be compiled here at all (see
 * AdMusicPlaybackService's own doc comment). Both portions need real
 * device confirmation before being called "confirmed" the way the ad/
 * skip keyword defaults now are.
 */
class AdAudioController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // No-op today - this app doesn't need to react to losing focus back
    // (nothing plays here to interrupt), but both the legacy and
    // AudioFocusRequest APIs require a listener to be supplied.
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private var focusRequest: AudioFocusRequest? = null
    private var holdingFocus = false

    private var fadeAnimator: ValueAnimator? = null
    private var isLocalMusicActive = false

    /** Idempotent: calling this repeatedly while already holding focus
      * (e.g. once per accessibility event while the same ad is still
      * playing) is a safe no-op, so callers don't need their own
      * "have I already done this for this ad" tracking the way
      * SpotifyAdSkipService's skip-tap logic needs hasAttemptedCurrentAd. */
    fun startMuting(): MuteOutcome {
        if (holdingFocus) return MuteOutcome.ENGAGED

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                // This app IS an accessibility service reacting to
                // on-screen content, not a media player - this usage
                // describes that relationship more accurately than
                // USAGE_MEDIA would, and lets the system route/treat the
                // interruption as accessibility-driven.
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        holdingFocus = granted
        return if (granted) MuteOutcome.ENGAGED else MuteOutcome.FOCUS_REQUEST_DENIED
    }

    /** Idempotent, same reasoning as [startMuting]. */
    fun stopMuting() {
        if (!holdingFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        focusRequest = null
        holdingFocus = false
    }

    /**
     * Attempts to start the local-music queue for the currently-detected
     * ad. [folderUri] is [SettingsRepository.localMusicFolderUri]'s
     * value (the caller reads settings, not this class - see
     * [SpotifyAdSkipService] for why: `AdAudioController` stays focused
     * on audio mechanics, not settings lookup).
     *
     * Returns `null` specifically when [AdMusicPlaybackService] hasn't
     * finished starting up yet - a transient condition, not a real
     * failure (see [LocalAudioOutcome]'s doc comment). Callers should
     * keep calling this on subsequent accessibility events (idempotent,
     * safe to call repeatedly) until it returns a real
     * [LocalAudioOutcome], not just once.
     */
    fun startLocalMusic(folderUri: Uri?, targetVolume: Float = LOCAL_MUSIC_DEFAULT_VOLUME): LocalAudioOutcome? {
        if (folderUri == null) return LocalAudioOutcome.NO_FOLDER_CONFIGURED
        if (isLocalMusicActive) return LocalAudioOutcome.PLAYED

        val service = AdMusicPlaybackService.instance
        if (service == null) {
            val intent = Intent(appContext, AdMusicPlaybackService::class.java)
            // Manual SDK check rather than androidx.core's ContextCompat.
            // startForegroundService() convenience wrapper - that's an
            // androidx.core artifact, kept out of this file's dependency
            // surface the same deliberate way LocalAudioLibrary avoids
            // androidx.documentfile (see that class's doc comment); this
            // is the same two-line check that wrapper does internally.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            return null
        }

        return when (val result = LocalAudioLibrary.listAudioFiles(appContext, folderUri)) {
            is LocalAudioFolderResult.PermissionRevoked -> LocalAudioOutcome.PERMISSION_REVOKED
            is LocalAudioFolderResult.FolderEmpty -> LocalAudioOutcome.FOLDER_EMPTY
            is LocalAudioFolderResult.Files -> {
                service.playQueue(result.uris)
                isLocalMusicActive = true
                fadeVolume(service, from = 0f, to = targetVolume.coerceIn(0f, 1f))
                LocalAudioOutcome.PLAYED
            }
        }
    }

    /** Idempotent: a no-op if local music isn't currently active. Fades
      * out (see docs/TODO.md's crossfade requirement - this should
      * overlap with Spotify's own fade back in, not leave a silent gap)
      * then stops the queue once the fade completes. */
    fun stopLocalMusic() {
        val service = AdMusicPlaybackService.instance
        if (service == null || !isLocalMusicActive) {
            isLocalMusicActive = false
            return
        }
        isLocalMusicActive = false
        fadeVolume(service, from = service.currentVolume(), to = 0f, onEnd = { service.stopQueue() })
    }

    /** Equal-power (sin/cos) crossfade ramp, not a plain linear
      * `Float` step schedule - see docs/TODO.md's fade requirement for
      * why linear reads as uneven (perceived loudness isn't linear in
      * the volume parameter). Runs on the calling thread's Looper (the
      * main thread, in practice - both [startLocalMusic]/[stopLocalMusic]
      * are called from SpotifyAdSkipService's onAccessibilityEvent,
      * which dispatches on the main thread), since ValueAnimator needs
      * one. Cancels any fade already in progress first, so a rapid
      * ad-detected/ad-cleared flicker can't leave two competing
      * animators fighting over the same player's volume. */
    private fun fadeVolume(
        service: AdMusicPlaybackService,
        from: Float,
        to: Float,
        durationMillis: Long = FADE_DURATION_MILLIS,
        onEnd: (() -> Unit)? = null,
    ) {
        fadeAnimator?.cancel()
        val fadingIn = to > from
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMillis
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            val curve = if (fadingIn) sin(t * (Math.PI / 2)).toFloat() else cos(t * (Math.PI / 2)).toFloat()
            service.setVolume((from + (to - from) * curve).coerceIn(0f, 1f))
        }
        if (onEnd != null) {
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
        }
        fadeAnimator = animator
        animator.start()
    }

    companion object {
        // ~300-500ms per docs/TODO.md's fade requirement - the low end of
        // that range; tune against how it actually feels on a real device.
        private const val FADE_DURATION_MILLIS = 350L

        // Default when a caller doesn't pass targetVolume explicitly -
        // SpotifyAdSkipService always passes SettingsRepository's actual
        // (user-configurable, per docs/TODO.md's normalization
        // requirement) value, so this only matters as a fallback. 0.7 is
        // a reasonable "audible but not overpowering" starting point,
        // not a confirmed "matches typical Spotify volume" value.
        const val LOCAL_MUSIC_DEFAULT_VOLUME = 0.7f
    }
}

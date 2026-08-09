package com.spotblock.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/** Whether a mute attempt actually engaged, and why not if it didn't -
  * same shape as [com.spotblock.app.ad.SkipOutcome]: a failure is
  * reported honestly, never silently treated the same as success. */
enum class MuteOutcome { ENGAGED, FOCUS_REQUEST_DENIED }

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
 * reacquire it and resume. This class never plays any audio of its own
 * (that's the separate, still-unbuilt "local song during ads" feature
 * in TODO.md) and never touches any stream's volume directly - it only
 * participates in the same focus-negotiation contract every media app
 * already implements.
 *
 * UNVERIFIED ON A REAL DEVICE: this sandbox has no Android SDK/emulator
 * access (same network restriction that blocks the Gradle/Android
 * build elsewhere in this project - see AdDetectorTest.kt's real-data
 * tests for the same caveat). This was compiled against a real Android
 * 14 API jar (org.robolectric:android-all, fetched from Maven Central)
 * to confirm it's valid Kotlin against the real AudioManager/
 * AudioFocusRequest API surface, which is a real, meaningful check -
 * but it has NOT been run, and audio focus behavior (whether Spotify
 * actually pauses vs. ducks vs. ignores it) can only be confirmed on a
 * real device with Spotify installed. Treat ENGAGED as "we successfully
 * requested focus," not "we confirmed Spotify went quiet."
 */
class AdAudioController(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // No-op today - this app doesn't need to react to losing focus back
    // (nothing plays here to interrupt), but both the legacy and
    // AudioFocusRequest APIs require a listener to be supplied.
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private var focusRequest: AudioFocusRequest? = null
    private var holdingFocus = false

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
}

package com.spotblock.app

import android.content.Context
import android.content.SharedPreferences
import com.spotblock.app.ad.DownloadOutcome
import com.spotblock.app.ad.SkipOutcome
import com.spotblock.app.audio.MuteOutcome
import java.text.SimpleDateFormat
import java.util.Locale

/** Counters plus a capped, newest-first activity log - the log exists mainly so a
  * heuristic, best-effort tool like this one is auditable: if it seems like it isn't
  * working, you can see exactly what was detected and what happened, rather than
  * just trusting a black box. */
class StatsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val adsDetected: Int get() = prefs.getInt(KEY_ADS_DETECTED, 0)
    val skipTapped: Int get() = prefs.getInt(KEY_SKIP_TAPPED, 0)
    val skipBlocked: Int get() = prefs.getInt(KEY_SKIP_BLOCKED, 0)
    val skipControlNotFound: Int get() = prefs.getInt(KEY_SKIP_CONTROL_NOT_FOUND, 0)
    val downloadTapped: Int get() = prefs.getInt(KEY_DOWNLOAD_TAPPED, 0)
    val downloadControlNotFound: Int get() = prefs.getInt(KEY_DOWNLOAD_CONTROL_NOT_FOUND, 0)
    val autoMuteEngaged: Int get() = prefs.getInt(KEY_AUTO_MUTE_ENGAGED, 0)
    val autoMuteFocusDenied: Int get() = prefs.getInt(KEY_AUTO_MUTE_FOCUS_DENIED, 0)

    fun recentLog(): List<String> {
        val raw = prefs.getString(KEY_LOG, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun recordAdDetected(matchedKeyword: String) {
        increment(KEY_ADS_DETECTED)
        appendLogEntry("Ad detected (matched \"$matchedKeyword\")")
    }

    fun recordSkipOutcome(outcome: SkipOutcome) {
        val entry = when (outcome) {
            SkipOutcome.TAPPED -> {
                increment(KEY_SKIP_TAPPED)
                "Skip control tapped"
            }
            SkipOutcome.BLOCKED_DISABLED -> {
                increment(KEY_SKIP_BLOCKED)
                "Skip control found but disabled - Spotify is blocking skip during this ad"
            }
            SkipOutcome.CONTROL_NOT_FOUND -> {
                increment(KEY_SKIP_CONTROL_NOT_FOUND)
                "No skip control found on screen - check Skip Control Labels in Setup"
            }
        }
        appendLogEntry(entry)
    }

    fun recordDownloadOutcome(outcome: DownloadOutcome) {
        val entry = when (outcome) {
            DownloadOutcome.TAPPED -> {
                increment(KEY_DOWNLOAD_TAPPED)
                "Download control tapped"
            }
            DownloadOutcome.CONTROL_NOT_FOUND -> {
                increment(KEY_DOWNLOAD_CONTROL_NOT_FOUND)
                "No Download control found on screen - check Download Control Labels in Setup, " +
                    "or you may not be on a playlist/album/podcast screen"
            }
        }
        appendLogEntry(entry)
    }

    fun recordMuteOutcome(outcome: MuteOutcome) {
        val entry = when (outcome) {
            MuteOutcome.ENGAGED -> {
                increment(KEY_AUTO_MUTE_ENGAGED)
                "Requested audio focus to silence ad"
            }
            MuteOutcome.FOCUS_REQUEST_DENIED -> {
                increment(KEY_AUTO_MUTE_FOCUS_DENIED)
                "Audio focus request denied - another app may be holding it"
            }
        }
        appendLogEntry(entry)
    }

    fun recordEvent(message: String) {
        appendLogEntry(message)
    }

    private fun increment(key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun appendLogEntry(message: String) {
        val entry = timeFormat.format(java.util.Date()) + " - " + message
        val updatedLog = (listOf(entry) + recentLog()).take(MAX_LOG_ENTRIES)
        prefs.edit().putString(KEY_LOG, updatedLog.joinToString("\n")).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "spot_block_stats"
        private const val KEY_ADS_DETECTED = "ads_detected"
        private const val KEY_SKIP_TAPPED = "skip_tapped"
        private const val KEY_SKIP_BLOCKED = "skip_blocked"
        private const val KEY_SKIP_CONTROL_NOT_FOUND = "skip_control_not_found"
        private const val KEY_DOWNLOAD_TAPPED = "download_tapped"
        private const val KEY_DOWNLOAD_CONTROL_NOT_FOUND = "download_control_not_found"
        private const val KEY_AUTO_MUTE_ENGAGED = "auto_mute_engaged"
        private const val KEY_AUTO_MUTE_FOCUS_DENIED = "auto_mute_focus_denied"
        private const val KEY_LOG = "recent_log"
        private const val MAX_LOG_ENTRIES = 50
    }
}

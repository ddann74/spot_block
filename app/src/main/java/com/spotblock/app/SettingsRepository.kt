package com.spotblock.app

import android.content.Context
import android.content.SharedPreferences

/**
 * All user-configurable settings - comma-separated strings rather than
 * SharedPreferences' StringSet, deliberately, since StringSet doesn't preserve
 * insertion order and these lists are meant to be readable/editable as an
 * ordered list in the UI.
 */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAdSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_SKIP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_SKIP_ENABLED, value).apply()

    /** Off by default - the diagnostic log is verbose (every screen evaluation, raw
      * on-screen text, every skip attempt and its outcome) by design, since that
      * detail is exactly what's needed to tell why a skip did or didn't happen, but
      * it's more than most day-to-day use needs. Turn on when actually troubleshooting
      * or tuning keyword lists, then share/clear it when done. */
    var isDiagnosticLoggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTIC_LOGGING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAGNOSTIC_LOGGING_ENABLED, value).apply()

    /** Which app package(s) the service is allowed to read/act on - a list (not one
      * value) in case a regional/Lite build ships under a different package name. */
    var targetPackages: List<String>
        get() = parseList(prefs.getString(KEY_TARGET_PACKAGES, null) ?: DEFAULT_TARGET_PACKAGES.joinToString(","))
        set(value) = prefs.edit().putString(KEY_TARGET_PACKAGES, joinList(value)).apply()

    /** On-screen text that means an ad is currently playing - case-insensitive
      * substring match against whatever the accessibility tree returns. NOT
      * confirmed against a real device (see README) - these are best-effort
      * guesses at what Spotify's ad screen actually shows, editable without a
      * rebuild for exactly that reason. */
    var adKeywords: List<String>
        get() = parseList(prefs.getString(KEY_AD_KEYWORDS, null) ?: DEFAULT_AD_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_AD_KEYWORDS, joinList(value)).apply()

    /** Candidate labels for the Skip/Next control, tried in order - the first one
      * found on screen during a detected ad is the one that gets checked/tapped. */
    var skipControlKeywords: List<String>
        get() = parseList(prefs.getString(KEY_SKIP_CONTROL_KEYWORDS, null) ?: DEFAULT_SKIP_CONTROL_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_SKIP_CONTROL_KEYWORDS, joinList(value)).apply()

    /** The floating Download button drawn over Spotify - on by default since that's
      * the whole point of the feature, but it's a visible overlay on top of another
      * app, so a way to turn it off without disabling ad-skip detection is worth
      * having, same reasoning as TikTok Feed Filter's overlay toggle. */
    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /** Candidate labels for Spotify's own "Download for offline" toggle (a real
      * Premium feature - this only ever taps that existing control, never touches
      * the network or writes any file itself). Tried in order; the first one found
      * on the current screen is the one that gets tapped. NOT confirmed against a
      * real device - see README. */
    var downloadControlKeywords: List<String>
        get() = parseList(prefs.getString(KEY_DOWNLOAD_CONTROL_KEYWORDS, null) ?: DEFAULT_DOWNLOAD_CONTROL_KEYWORDS.joinToString(","))
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_CONTROL_KEYWORDS, joinList(value)).apply()

    fun addAdKeyword(keyword: String) = addKeyword(::adKeywords, keyword)
    fun removeAdKeyword(keyword: String) = removeKeyword(::adKeywords, keyword)
    fun addSkipControlKeyword(keyword: String) = addKeyword(::skipControlKeywords, keyword)
    fun removeSkipControlKeyword(keyword: String) = removeKeyword(::skipControlKeywords, keyword)
    fun addDownloadControlKeyword(keyword: String) = addKeyword(::downloadControlKeywords, keyword)
    fun removeDownloadControlKeyword(keyword: String) = removeKeyword(::downloadControlKeywords, keyword)

    /** Shared add/remove for the keyword lists above, which are all plain "unique,
      * case-insensitive, order-preserving" lists - takes a property reference so
      * each list doesn't need its own near-identical pair of methods. */
    private fun addKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        if (list.get().any { it.equals(trimmed, ignoreCase = true) }) return
        list.set(list.get() + trimmed)
    }

    private fun removeKeyword(list: kotlin.reflect.KMutableProperty0<List<String>>, keyword: String) {
        list.set(list.get().filterNot { it.equals(keyword, ignoreCase = true) })
    }

    fun addTargetPackage(pkg: String) {
        val trimmed = pkg.trim()
        if (trimmed.isEmpty() || trimmed in targetPackages) return
        targetPackages = targetPackages + trimmed
    }

    fun removeTargetPackage(pkg: String) {
        targetPackages = targetPackages.filterNot { it == pkg }
    }

    private fun parseList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun joinList(items: List<String>): String = items.joinToString(",")

    companion object {
        private const val PREFS_NAME = "spot_block_settings"
        private const val KEY_AD_SKIP_ENABLED = "ad_skip_enabled"
        private const val KEY_DIAGNOSTIC_LOGGING_ENABLED = "diagnostic_logging_enabled"
        private const val KEY_TARGET_PACKAGES = "target_packages"
        private const val KEY_AD_KEYWORDS = "ad_keywords"
        private const val KEY_SKIP_CONTROL_KEYWORDS = "skip_control_keywords"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_DOWNLOAD_CONTROL_KEYWORDS = "download_control_keywords"

        // Spotify's official Android package. A regional/Lite build under a
        // different package name would need adding manually in Setup.
        val DEFAULT_TARGET_PACKAGES = listOf("com.spotify.music")

        // Best-effort guesses, NOT confirmed against a real device - see README's
        // "Known open items" section. Add whatever wording your build actually
        // shows if these don't match.
        val DEFAULT_AD_KEYWORDS = listOf("Advertisement")

        // "Skip" alone is deliberately last and most generic - "Skip Ad" is more
        // specific and preferred when both match the same screen.
        val DEFAULT_SKIP_CONTROL_KEYWORDS = listOf("Skip Ad", "Skip", "Next")

        // Best-effort guess at Spotify's Download toggle contentDescription/label,
        // NOT confirmed against a real device - see README. Spotify's own Download
        // control lives on playlist/album/podcast screens (not Now Playing), so
        // this only ever finds anything while viewing one of those.
        val DEFAULT_DOWNLOAD_CONTROL_KEYWORDS = listOf("Download")
    }
}

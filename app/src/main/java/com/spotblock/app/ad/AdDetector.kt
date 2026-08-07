package com.spotblock.app.ad

/** The outcome of trying to act on a detected ad - kept as three distinct states
  * rather than a plain boolean specifically so "we tried and it didn't work" is
  * never silently reported the same as "nothing to do here". [BLOCKED_DISABLED] is
  * the expected, common case on Spotify Free: the control exists but Spotify itself
  * has disabled it for the duration of the ad. [CONTROL_NOT_FOUND] means the ad was
  * detected but none of the configured skip-control labels matched anything on
  * screen - a wording mismatch, not a "Spotify blocked this" signal. */
enum class SkipOutcome { TAPPED, BLOCKED_DISABLED, CONTROL_NOT_FOUND }

/** [matchedKeyword] is null when no ad is detected, otherwise whichever configured
  * keyword actually matched - carried through so the Activity/Diagnostic log can
  * say exactly what fired, the same way TikTok Feed Filter's ad-keyword match does. */
data class AdEvaluation(val isAdPlaying: Boolean, val matchedKeyword: String?)

/**
 * Pure decision logic - no Android/AccessibilityNodeInfo dependencies, so it's
 * directly unit-testable. The accessibility service is responsible for turning the
 * current screen into a flat list of on-screen text strings and calling [evaluate];
 * this only decides whether that amounts to "an ad is playing" and which keyword
 * triggered it.
 *
 * Unlike TikTok Feed Filter's ad detection, there's no "current item vs. preloaded
 * next item" scoping needed here - Spotify's Now Playing / ad screen is a single,
 * non-scrolling view, not a feed with several items' text all present at once.
 */
object AdDetector {

    fun evaluate(screenTexts: List<String>, adKeywordsEnabled: Boolean, adKeywords: List<String>): AdEvaluation {
        if (!adKeywordsEnabled) return AdEvaluation(false, null)
        val matchedKeyword = adKeywords.firstOrNull { keyword ->
            keyword.isNotBlank() && screenTexts.any { it.contains(keyword, ignoreCase = true) }
        }
        return AdEvaluation(matchedKeyword != null, matchedKeyword)
    }
}

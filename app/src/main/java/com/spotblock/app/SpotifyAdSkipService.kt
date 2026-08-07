package com.spotblock.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.spotblock.app.ad.AdDetector
import com.spotblock.app.ad.SkipOutcome
import com.spotblock.app.diagnostics.DiagnosticLog

/**
 * Reads whatever text Spotify is currently rendering (via the accessibility tree)
 * and, if it looks like an ad is playing, searches for a Skip/Next control and
 * inspects it before acting: taps it if it's actually enabled, but logs "blocked" -
 * not a false "success" - if the control exists but is disabled, which is the
 * expected, common case on Spotify Free (skip is deliberately disabled for the
 * duration of an ad on that tier). This only ever taps a control already visible on
 * screen; it never modifies audio, never touches the network, and never acts on
 * anything outside the configured target package(s).
 */
class SpotifyAdSkipService : AccessibilityService() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var diagnosticLog: DiagnosticLog

    // True once a skip has been attempted (tapped, found-but-disabled, or
    // not-found) for whichever ad is CURRENTLY on screen - reset the moment the
    // ad text is no longer detected, so the next ad gets its own fresh attempt
    // instead of this one silently re-triggering on every screen update while
    // the same ad is still playing.
    private var hasAttemptedCurrentAd = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)
        diagnosticLog.log("SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName !in settingsRepository.targetPackages) return

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectText(root, texts)

        val evaluation = AdDetector.evaluate(
            screenTexts = texts,
            adKeywordsEnabled = settingsRepository.isAdSkipEnabled,
            adKeywords = settingsRepository.adKeywords
        )

        if (!evaluation.isAdPlaying) {
            if (hasAttemptedCurrentAd) {
                diagnosticLog.log("AD", "ad no longer detected - ready for the next one")
            }
            hasAttemptedCurrentAd = false
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }

        diagnosticLog.log("AD", "ad detected - matched=\"${evaluation.matchedKeyword}\" - texts=$texts")

        if (hasAttemptedCurrentAd) {
            // Already tried for this ad - don't re-search/re-tap on every single
            // screen update while it's still playing.
            @Suppress("DEPRECATION")
            root.recycle()
            return
        }
        hasAttemptedCurrentAd = true
        statsRepository.recordAdDetected(evaluation.matchedKeyword!!)

        val controlNode = findControlNode(root, settingsRepository.skipControlKeywords)
        val outcome = when {
            controlNode == null -> SkipOutcome.CONTROL_NOT_FOUND
            !controlNode.isEnabled -> SkipOutcome.BLOCKED_DISABLED
            else -> {
                controlNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SkipOutcome.TAPPED
            }
        }
        diagnosticLog.log("SKIP", "outcome=$outcome, keywords=${settingsRepository.skipControlKeywords}")
        statsRepository.recordSkipOutcome(outcome)

        @Suppress("DEPRECATION")
        root.recycle()
    }

    override fun onInterrupt() {
        diagnosticLog.log("SERVICE", "onInterrupt")
    }

    /** Depth-first collection of every text/contentDescription string in the current
      * window - the closest available substitute for "what does this screen say",
      * since accessibility nodes don't expose anything richer than that. */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > MAX_TREE_DEPTH) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, out, depth + 1)
            @Suppress("DEPRECATION")
            child?.recycle()
        }
    }

    /** Depth-first search for a node whose text or contentDescription contains any of
      * [keywords] (case-insensitive), returning the first clickable node at or above
      * it in the tree - not clicked here, since the caller needs to inspect
      * isEnabled() first to tell a real tap from a control Spotify has disabled. */
    private fun findControlNode(node: AccessibilityNodeInfo?, keywords: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_TREE_DEPTH || keywords.isEmpty()) return null

        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val isMatch = keywords.any { keyword ->
            keyword.isNotBlank() && (text.contains(keyword, ignoreCase = true) || description.contains(keyword, ignoreCase = true))
        }
        if (isMatch) {
            val clickable = findClickableSelfOrAncestor(node)
            if (clickable != null) return clickable
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findControlNode(child, keywords, depth + 1)
            if (found != null) return found
            @Suppress("DEPRECATION")
            child.recycle()
        }
        return null
    }

    /** Spotify's clickable target is often an ancestor of the node that actually holds
      * the matched text (an icon + label wrapped in one clickable row) - walks up a
      * bounded number of hops looking for the first node Android considers clickable. */
    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null) {
            if (current.isClickable) return current
            if (hops >= MAX_ANCESTOR_HOPS) return null
            current = current.parent
            hops++
        }
        return null
    }

    companion object {
        private const val MAX_TREE_DEPTH = 60
        private const val MAX_ANCESTOR_HOPS = 6
    }
}

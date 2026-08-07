package com.spotblock.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.spotblock.app.ad.AdDetector
import com.spotblock.app.ad.DownloadOutcome
import com.spotblock.app.ad.SkipOutcome
import com.spotblock.app.diagnostics.DiagnosticLog
import com.spotblock.app.overlay.OverlayController

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
    private lateinit var overlayController: OverlayController

    // True once a skip has been attempted (tapped, found-but-disabled, or
    // not-found) for whichever ad is CURRENTLY on screen - reset the moment the
    // ad text is no longer detected, so the next ad gets its own fresh attempt
    // instead of this one silently re-triggering on every screen update while
    // the same ad is still playing.
    private var hasAttemptedCurrentAd = false

    private val mainHandler = Handler(Looper.getMainLooper())
    // A single reusable Runnable so scheduling it again (or cancelling it) always
    // targets every currently-pending instance - same debounce pattern as TikTok
    // Feed Filter's OverlayController, so a stray non-target-package event doesn't
    // flash the overlay away while Spotify is still genuinely in front.
    private val hideOverlayRunnable = Runnable { overlayController.hide() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)
        overlayController = OverlayController(
            service = this,
            diagnosticLog = diagnosticLog,
            onDownloadTapped = { handleOverlayDownloadTapped() }
        )
        diagnosticLog.log("SERVICE", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        // The overlay's own button is drawn in a window that belongs to this app,
        // so interacting with it (or the window just redrawing) can itself
        // generate an accessibility event tagged with this app's package -
        // reacting to that as "the user left Spotify" would hide the overlay out
        // from under the tap that's still landing on it. Never a real signal
        // either way, so just ignore it.
        if (packageName == this.packageName) return

        if (packageName !in settingsRepository.targetPackages) {
            // Don't hide the instant a single event from some other package shows
            // up - system UI, a keyboard, a notification icon updating, etc. can
            // all fire one while Spotify is still genuinely in front. A real
            // switch away from Spotify is followed by silence from Spotify's own
            // events, so a short delayed hide - cancelled below the moment a
            // Spotify event arrives - tells the two apart without visibly
            // flashing the overlay for every unrelated event in between.
            mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MILLIS)
            return
        }
        mainHandler.removeCallbacks(hideOverlayRunnable)
        if (settingsRepository.isOverlayEnabled) overlayController.show() else overlayController.hide()

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
        mainHandler.removeCallbacks(hideOverlayRunnable)
        overlayController.hide()
    }

    /** The overlay button tap arrives outside the normal accessibility-event flow, so
      * this reads a fresh snapshot of the current screen itself rather than relying on
      * whatever the last onAccessibilityEvent call happened to see - the screen may
      * well be different by the time a tap actually lands. Only ever searches for and
      * taps Spotify's own existing "Download for offline" control (a real Premium
      * feature); this never writes a file, starts a network request, or does anything
      * beyond that single tap. */
    private fun handleOverlayDownloadTapped() {
        val root = rootInActiveWindow
        if (root == null) {
            statsRepository.recordEvent("Download tapped but no screen content was available")
            diagnosticLog.log("OVERLAY", "Download tapped, rootInActiveWindow was null")
            return
        }
        val controlNode = findControlNode(root, settingsRepository.downloadControlKeywords)
        val outcome = if (controlNode != null) {
            controlNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            DownloadOutcome.TAPPED
        } else {
            DownloadOutcome.CONTROL_NOT_FOUND
        }
        diagnosticLog.log("DOWNLOAD", "outcome=$outcome, keywords=${settingsRepository.downloadControlKeywords}")
        statsRepository.recordDownloadOutcome(outcome)

        @Suppress("DEPRECATION")
        root.recycle()
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
        private const val OVERLAY_HIDE_DELAY_MILLIS = 800L
    }
}

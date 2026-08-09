package com.spotblock.app.ad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdDetectorTest {

    @Test
    fun `detects an ad when a configured keyword matches on-screen text`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("Advertisement", "0:03", "Skip"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Advertisement")
        )
        assertEquals(true, result.isAdPlaying)
        assertEquals("Advertisement", result.matchedKeyword)
    }

    @Test
    fun `no match means no ad detected`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("Song Title", "Artist Name", "2:14"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Advertisement")
        )
        assertEquals(false, result.isAdPlaying)
        assertNull(result.matchedKeyword)
    }

    @Test
    fun `matching is case-insensitive`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("ADVERTISEMENT"),
            adKeywordsEnabled = true,
            adKeywords = listOf("advertisement")
        )
        assertEquals(true, result.isAdPlaying)
    }

    @Test
    fun `disabled never matches regardless of on-screen text`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("Advertisement"),
            adKeywordsEnabled = false,
            adKeywords = listOf("Advertisement")
        )
        assertEquals(false, result.isAdPlaying)
        assertNull(result.matchedKeyword)
    }

    @Test
    fun `blank keywords in the list are ignored, not treated as a wildcard match`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("Song Title", "Artist Name"),
            adKeywordsEnabled = true,
            adKeywords = listOf("", "  ", "Advertisement")
        )
        assertEquals(false, result.isAdPlaying)
    }

    @Test
    fun `first matching keyword wins when multiple are configured`() {
        val result = AdDetector.evaluate(
            screenTexts = listOf("Sponsored content"),
            adKeywordsEnabled = true,
            adKeywords = listOf("Advertisement", "Sponsored")
        )
        assertEquals(true, result.isAdPlaying)
        assertEquals("Sponsored", result.matchedKeyword)
    }

    @Test
    fun `empty screen produces no match`() {
        val result = AdDetector.evaluate(
            screenTexts = emptyList(),
            adKeywordsEnabled = true,
            adKeywords = listOf("Advertisement")
        )
        assertEquals(false, result.isAdPlaying)
    }

    // Everything below is transcribed from a real device's diagnostic log (2,523
    // real log lines, 6 real ad occurrences, run against the default keyword
    // config) rather than authored to fit this app's own patterns - closing the
    // README's "Known open items" gap ("no keyword list here has been confirmed
    // against a real device"). Result: the default "Advertisement" ad keyword
    // matched all 6/6 real ad occurrences with zero misses, and the default skip
    // labels found a control 6/6 times (0 CONTROL_NOT_FOUND) - 2 TAPPED, 4
    // BLOCKED_DISABLED (Spotify itself disabling skip, the expected Free-tier
    // outcome, not an app failure). Only the ad-related on-screen text is used
    // here, not the surrounding song/artist text from the same log, since that
    // would be real personal listening history and isn't needed to test ad
    // detection.

    @Test
    fun `real device text - audio-only ad, comma-separated format`() {
        // Actual texts= from the log: an audio ad mid-break, no video unit.
        val result = AdDetector.evaluate(
            screenTexts = listOf("Tap to show controls", "Advertisement", "1 of 2", "0:00", "15s left in the break", "0:15", "Learn more"),
            adKeywordsEnabled = true,
            adKeywords = SettingsRepositoryDefaults.AD_KEYWORDS
        )
        assertEquals(true, result.isAdPlaying)
        assertEquals("Advertisement", result.matchedKeyword)
    }

    @Test
    fun `real device text - video ad brand card uses a bullet separator, not a comma`() {
        // Actual texts= from the log: "Advertisement • 1 of 1" (Spotify's video-ad
        // unit renders this differently from the audio-ad "Advertisement, 1 of 2"
        // format above) - a real UI variant the substring match has to survive.
        val result = AdDetector.evaluate(
            screenTexts = listOf("Tap to show controls", "Dyson", "Advertisement • 1 of 1", "0:13", "2s left in the break", "0:15", "Powerful. Compact. Quiet.", "Learn more"),
            adKeywordsEnabled = true,
            adKeywords = SettingsRepositoryDefaults.AD_KEYWORDS
        )
        assertEquals(true, result.isAdPlaying)
    }

    @Test
    fun `real device text - brand-sponsored card with a repeated brand name`() {
        // Actual texts= from the log: a Samsung-branded ad card. Brand name
        // appears twice (icon + label, both collected) - not a duplicate-handling
        // concern for AdDetector (firstOrNull short-circuits), but real shape.
        val result = AdDetector.evaluate(
            screenTexts = listOf("Samsung", "Advertisement", "More options", "Samsung", "Learn more"),
            adKeywordsEnabled = true,
            adKeywords = SettingsRepositoryDefaults.AD_KEYWORDS
        )
        assertEquals(true, result.isAdPlaying)
    }

    @Test
    fun `real device text - ad break with playback controls visible`() {
        // Actual texts= from the log: "Your music will continue after the break"
        // - Spotify's own copy explaining the break, shown alongside a visible
        // Next control (this is one of the two real TAPPED occurrences).
        val result = AdDetector.evaluate(
            screenTexts = listOf(
                "Tap to show controls", "Your music will continue after the break", "More options",
                "Advertisement", "1 of 2", "0:00", "30s left in the break", "0:15",
                "Thumb Up", "Previous", "Pause", "Next", "Thumb Down", "Learn more"
            ),
            adKeywordsEnabled = true,
            adKeywords = SettingsRepositoryDefaults.AD_KEYWORDS
        )
        assertEquals(true, result.isAdPlaying)
    }

    @Test
    fun `real device text - ordinary song playback is never mistaken for an ad`() {
        // Not from the real log (that text is personal listening history), but
        // shaped like it: normal "Playing from Playlist" screens made up zero
        // false-positive ad detections across the entire real log.
        val result = AdDetector.evaluate(
            screenTexts = listOf("Playing from Playlist", "Road Trip Mix", "More options for song Example Song", "Example Song", "Example Artist", "1:53", "4:34", "Track position"),
            adKeywordsEnabled = true,
            adKeywords = SettingsRepositoryDefaults.AD_KEYWORDS
        )
        assertEquals(false, result.isAdPlaying)
    }
}

/** Mirrors SettingsRepository.DEFAULT_AD_KEYWORDS without depending on the
  * android.content.Context-requiring SettingsRepository class from this
  * plain-JVM test. */
private object SettingsRepositoryDefaults {
    val AD_KEYWORDS = listOf("Advertisement")
}

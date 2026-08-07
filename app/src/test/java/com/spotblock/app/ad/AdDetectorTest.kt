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
}

package dev.wadackel.caretto.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boundary behaviour as the device actually segments it.
 *
 * The JVM tests run against the JDK's segmenter, which is not the one Android ships. Anything that
 * depends on the platform's own rules — words in a language written without spaces, clusters built
 * from joiners — can only be pinned down here.
 */
@RunWith(AndroidJUnit4::class)
class TextBoundaryDeviceTest {

    @Test
    fun japaneseIsSegmentedIntoWords() {
        val text = "これは日本語です"
        val first = TextBoundary.nextWordLength(text)
        assertTrue("expected a word shorter than the whole run, got $first", first in 1 until text.length)

        // Walking forward has to reach the end without stalling or overshooting.
        var walked = 0
        while (walked < text.length) {
            val step = TextBoundary.nextWordLength(text.substring(walked))
            assertTrue("stalled at $walked", step > 0)
            walked += step
        }
        assertEquals(text.length, walked)
    }

    /** Walking one way and back must find the same boundaries, or a round trip drifts. */
    @Test
    fun japaneseWalksBackOverTheSameBoundaries() {
        val text = "これは日本語です"

        val forward = generateSequence(0) { at ->
            val step = TextBoundary.nextWordLength(text.substring(at))
            (at + step).takeIf { it < text.length }
        }.drop(1).toSet()

        val backward = generateSequence(text.length) { at ->
            val step = TextBoundary.previousWordLength(text.substring(0, at))
            (at - step).takeIf { it > 0 }
        }.drop(1).toSet()

        assertEquals(forward, backward)
        assertTrue("expected interior boundaries, found none", forward.isNotEmpty())
    }

    @Test
    fun aWordBoundaryNeverSplitsAJapaneseCharacter() {
        val text = "漢字とひらがなとカタカナ"
        var at = 0
        while (at < text.length) {
            val step = TextBoundary.nextWordLength(text.substring(at))
            assertTrue("split a surrogate at $at", !Character.isLowSurrogate(text[at + step - 1]) || step > 1)
            at += step
        }
    }

    /** The JVM tests deliberately stop at surrogate pairs; joiners are the device's business. */
    @Test
    fun joinedEmojiMoveAsOneCluster() {
        assertEquals(11, TextBoundary.nextClusterLength("👨‍👩‍👧‍👦ab"))
        assertEquals(4, TextBoundary.nextClusterLength("🇯🇵ab"))
        assertEquals(11, TextBoundary.previousClusterLength("ab👨‍👩‍👧‍👦"))
    }

    @Test
    fun englishSegmentsTheSameWayItDoesOffDevice() {
        assertEquals(5, TextBoundary.nextWordLength("hello world"))
        assertEquals(7, TextBoundary.nextWordLength("  hello world"))
        assertEquals(5, TextBoundary.previousWordLength("hello world"))
    }
}

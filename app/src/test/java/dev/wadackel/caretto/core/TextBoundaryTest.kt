package dev.wadackel.caretto.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBoundaryTest {

    @Test
    fun asciiMovesOneChar() {
        assertEquals(1, TextBoundary.nextClusterLength("abc"))
        assertEquals(1, TextBoundary.previousClusterLength("abc"))
    }

    @Test
    fun japaneseMovesOneChar() {
        assertEquals(1, TextBoundary.nextClusterLength("にほんご"))
        assertEquals(1, TextBoundary.previousClusterLength("にほんご"))
    }

    @Test
    fun surrogatePairIsNeverSplit() {
        val emoji = "😀"
        assertEquals(2, TextBoundary.nextClusterLength(emoji))
        assertEquals(2, TextBoundary.previousClusterLength(emoji))
        assertEquals(2, TextBoundary.previousClusterLength("ab$emoji"))
        assertEquals(2, TextBoundary.nextClusterLength("${emoji}ab"))
    }

    @Test
    fun combiningMarkStaysWithBaseCharacter() {
        val combined = "é"
        assertEquals(2, TextBoundary.nextClusterLength(combined))
        assertEquals(2, TextBoundary.previousClusterLength(combined))
    }

    @Test
    fun emptyInputMovesNothing() {
        assertEquals(0, TextBoundary.nextClusterLength(""))
        assertEquals(0, TextBoundary.previousClusterLength(""))
    }

    // Word segmentation for languages without spaces is left to the platform, and the JVM's
    // segmenter is not the one Android ships. Those cases are checked on a device instead.

    @Test
    fun forwardWordStopsAtTheEndOfTheNextWord() {
        assertEquals(5, TextBoundary.nextWordLength("hello world"))
        assertEquals(7, TextBoundary.nextWordLength("  hello world"))
        assertEquals(3, TextBoundary.nextWordLength("foo, bar"))
    }

    @Test
    fun backwardWordStopsAtTheStartOfThePreviousWord() {
        assertEquals(5, TextBoundary.previousWordLength("hello world"))
        assertEquals(8, TextBoundary.previousWordLength("hello   "))
        assertEquals(5, TextBoundary.previousWordLength("foo, bar  "))
    }

    @Test
    fun identifiersAndNumbersStayWhole() {
        assertEquals(7, TextBoundary.nextWordLength("foo_bar baz"))
        assertEquals(6, TextBoundary.nextWordLength("abc123 def"))
        assertEquals(4, TextBoundary.nextWordLength("3.14 x"))
    }

    @Test
    fun aRunOfEmojiIsNotAWord() {
        assertEquals(6, TextBoundary.nextWordLength("\uD83D\uDE00\uD83D\uDE01ab"))
    }

    @Test
    fun aRunWithNoWordInItIsCrossedWhole() {
        assertEquals(3, TextBoundary.nextWordLength("   "))
        assertEquals(3, TextBoundary.previousWordLength("..."))
    }

    @Test
    fun wordLookupHandlesEmptyInput() {
        assertEquals(0, TextBoundary.nextWordLength(""))
        assertEquals(0, TextBoundary.previousWordLength(""))
    }

    @Test
    fun carriageReturnAndLineFeedCountAsOneBreak() {
        assertEquals(2, TextBoundary.indexOfLineBreakStart("ab\r\ncd"))
        assertEquals(2, TextBoundary.lineBreakLengthAt("ab\r\ncd", 2))
        assertEquals(2, TextBoundary.indexOfLineBreakStart("ab\ncd"))
        assertEquals(1, TextBoundary.lineBreakLengthAt("ab\ncd", 2))
        assertEquals(-1, TextBoundary.indexOfLineBreakStart("no break"))
    }

    @Test
    fun newlineLookupFindsNearestBoundary() {
        assertEquals(5, TextBoundary.lastIndexOfNewline("first\nsecond"))
        assertEquals(5, TextBoundary.indexOfNewline("first\nsecond"))
        assertEquals(-1, TextBoundary.lastIndexOfNewline("no newline"))
        assertEquals(-1, TextBoundary.indexOfNewline("no newline"))
    }
}

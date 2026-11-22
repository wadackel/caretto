package dev.wadackel.caretto.core

import java.text.BreakIterator

/**
 * Grapheme cluster boundaries for cursor movement and deletion.
 *
 * A plain `cursor - 1` would split UTF-16 surrogate pairs and break emoji / combining sequences,
 * which AccessibilityService callers are explicitly warned against.
 */
object TextBoundary {

    fun nextClusterLength(after: CharSequence): Int {
        if (after.isEmpty()) return 0
        val text = after.toString()
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val boundary = iterator.following(0)
        return if (boundary == BreakIterator.DONE) text.length else boundary
    }

    fun previousClusterLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val text = before.toString()
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        val boundary = iterator.preceding(text.length)
        return if (boundary == BreakIterator.DONE) text.length else text.length - boundary
    }

    /**
     * Distance to the end of the next word, skipping whatever separates it from the cursor.
     *
     * Segment boundaries come from [BreakIterator], but which segments count as a word is decided
     * here rather than through ICU's rule status, so the rule stays testable off-device.
     */
    fun nextWordLength(after: CharSequence): Int {
        if (after.isEmpty()) return 0
        val text = after.toString()
        val iterator = BreakIterator.getWordInstance().apply { setText(text) }
        var start = 0
        var boundary = iterator.following(0)
        while (boundary != BreakIterator.DONE) {
            if (holdsWord(text, start, boundary)) return boundary
            start = boundary
            boundary = iterator.next()
        }
        return text.length
    }

    /** Distance back to the start of the previous word, skipping what separates it. */
    fun previousWordLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val text = before.toString()
        val iterator = BreakIterator.getWordInstance().apply { setText(text) }
        var end = text.length
        var boundary = iterator.preceding(end)
        while (boundary != BreakIterator.DONE) {
            if (holdsWord(text, boundary, end)) return text.length - boundary
            end = boundary
            boundary = iterator.preceding(boundary)
        }
        return text.length
    }

    private fun holdsWord(text: String, start: Int, end: Int): Boolean {
        var index = start
        while (index < end) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    fun lastIndexOfNewline(text: CharSequence): Int {
        for (i in text.length - 1 downTo 0) {
            if (text[i] == '\n') return i
        }
        return -1
    }

    fun indexOfNewline(text: CharSequence): Int {
        for (i in text.indices) {
            if (text[i] == '\n') return i
        }
        return -1
    }

    /** Where the next line break begins, counting CR LF as one break rather than two. */
    fun indexOfLineBreakStart(text: CharSequence): Int {
        val newline = indexOfNewline(text)
        if (newline <= 0) return newline
        return if (text[newline - 1] == '\r') newline - 1 else newline
    }

    fun lineBreakLengthAt(text: CharSequence, start: Int): Int =
        if (start + 1 < text.length && text[start] == '\r' && text[start + 1] == '\n') 2 else 1
}

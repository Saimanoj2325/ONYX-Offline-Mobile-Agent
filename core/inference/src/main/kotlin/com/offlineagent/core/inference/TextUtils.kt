package com.offlineagent.core.inference

import java.io.ByteArrayOutputStream

/**
 * Incremental UTF-8 decoder for token pieces. A single token can end in the middle of a
 * multi-byte character (emoji, Telugu/Hindi script, CJK); those bytes are held until complete.
 */
class Utf8StreamDecoder {
    private val pending = ByteArrayOutputStream()

    /** Feeds bytes; returns every fully decodable character so far (possibly ""). */
    fun feed(bytes: ByteArray): String {
        pending.write(bytes)
        val all = pending.toByteArray()
        val cut = completePrefixLength(all)
        pending.reset()
        if (cut < all.size) pending.write(all, cut, all.size - cut)
        return if (cut == 0) "" else String(all, 0, cut, Charsets.UTF_8)
    }

    /**
     * Ends the stream. Anything still pending is the start of a character that never completed
     * (e.g. generation hit the token limit mid-emoji); it is dropped rather than shown as U+FFFD.
     */
    fun flush(): String {
        pending.reset()
        return ""
    }

    companion object {
        /** Length of the prefix that does not end in an incomplete UTF-8 sequence. */
        internal fun completePrefixLength(b: ByteArray): Int {
            val n = b.size
            // Look back at most 3 bytes for the start of the last sequence.
            var i = n - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val c = b[i].toInt() and 0xFF
                if (c and 0xC0 != 0x80) {
                    val need = when {
                        c and 0x80 == 0 -> 1
                        c and 0xE0 == 0xC0 -> 2
                        c and 0xF0 == 0xE0 -> 3
                        c and 0xF8 == 0xF0 -> 4
                        else -> 1 // invalid lead byte: let the charset replace it
                    }
                    return if (n - i >= need) n else i
                }
                i--; back++
            }
            return n
        }
    }
}

/** Splits Qwen3-style output into reasoning (`<think>…</think>`) and the visible answer, while streaming. */
class ThinkSplitter {
    private val raw = StringBuilder()

    data class Parts(val reasoning: String, val answer: String, val thinkingInProgress: Boolean)

    fun append(text: String): Parts {
        raw.append(text)
        return split(raw.toString())
    }

    fun current(): Parts = split(raw.toString())

    companion object {
        private const val OPEN = "<think>"
        private const val CLOSE = "</think>"

        fun split(text: String): Parts {
            val start = text.indexOf(OPEN)
            if (start < 0) {
                // A partial "<thi" at the very start is held back so it never flashes in the UI.
                val t = text.trimStart()
                if (t.isNotEmpty() && OPEN.startsWith(t)) return Parts("", "", false)
                return Parts("", text.trimStart(), false)
            }
            val end = text.indexOf(CLOSE, start + OPEN.length)
            if (end < 0) {
                return Parts(text.substring(start + OPEN.length).trim(), text.substring(0, start).trim(), true)
            }
            val reasoning = text.substring(start + OPEN.length, end).trim()
            val answer = (text.substring(0, start) + text.substring(end + CLOSE.length)).trimStart()
            return Parts(reasoning, answer, false)
        }

        /** Removes any think block (used when re-sending history, as the official template does). */
        fun stripThinking(text: String): String = split(text).answer
    }
}

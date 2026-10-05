package com.localreader.app.core.search

import java.io.Closeable
import java.io.File

/** Coordinates are the original UTF-16 units, never normalized or rendered offsets. */
data class SourceRange(val start: Long, val end: Long) {
    init { require(start >= 0 && end >= start) }
}

/** One logical document. Technical renderer parts must not split this cursor. */
interface CanonicalDocument {
    val length: Long
    fun slice(start: Long, count: Int): String
    /** Applied after whole-document normalization; this never resets Unicode context. */
    fun permits(range: SourceRange): Boolean
}

data class SearchScan(val ranges: List<SourceRange>, val truncated: Boolean)
class SearchResourceLimit(message: String) : IllegalArgumentException(message)

/** Owns temporary text and inverse-map tapes; cancellation and close remove every file. */
class NormalizedDocument internal constructor(private val tape: MappedTape, private val directory: File) : Closeable {
    val length get() = tape.length
    fun slice(start: Long, count: Int) = tape.slice(start, count)
    fun sourceRange(start: Long, end: Long, checkpoint: () -> Unit = {}) = tape.inverse(start, end, checkpoint)
    fun find(query: String, document: CanonicalDocument, limit: Int = 200, checkpoint: () -> Unit = {}): SearchScan {
        require(query.isNotEmpty() && query.length <= 4096 && limit in 1..200)
        val matches = linkedSetOf<SourceRange>()
        var at = 0L
        var tail = ""
        while (at < length) {
            checkpoint()
            val text = slice(at, 16384)
            val joined = tail + text
            val base = at - tail.length
            var from = 0
            while (true) {
                val index = joined.indexOf(query, from)
                if (index < 0) break
                checkpoint()
                val source = sourceRange(base + index, base + index + query.length, checkpoint)
                if (source.start < source.end && document.permits(source) && matches.add(source) && matches.size > limit)
                    return SearchScan(matches.take(limit), true)
                from = index + 1
            }
            tail = joined.takeLast(query.length - 1)
            at += text.length
        }
        return SearchScan(matches.toList(), false)
    }
    override fun close() { tape.close();directory.deleteRecursively() }
}

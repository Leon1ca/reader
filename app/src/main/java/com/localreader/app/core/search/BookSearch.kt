package com.localreader.app.core.search

import com.localreader.app.core.Locator
import java.io.Closeable
import java.io.File
import java.util.UUID

data class SearchExcerpt(val text: String, val matchStart: Int, val matchEnd: Int, val truncated: Boolean = false)
data class SearchHit(val id: String, val locator: Locator, val range: SourceRange, val excerpt: SearchExcerpt, val chapter: String)
enum class SearchPhase { IDLE, RUNNING, DONE, FAILED, CANCELED }
data class SearchState(val query: String = "", val phase: SearchPhase = SearchPhase.IDLE, val hits: List<SearchHit> = emptyList(),
                       val truncated: Boolean = false, val completedDocuments: Int = 0, val totalDocuments: Int = 0, val message: String? = null)
interface SearchDocument : CanonicalDocument, Closeable {
    val id: String
    fun locator(range: SourceRange): Locator
    fun chapter(range: SourceRange): String
}

/** Formats supply real source cursors. Renderer indices and normalized offsets stay private. */
class BookSearch(private val root: File, private val revision: String, private val count: Int,
                 private val open: (Int, File, () -> Unit) -> SearchDocument, private val active: () -> Unit,
                 private val scanLease: (() -> Unit) -> Closeable = { Closeable {} }) {
    fun scan(query: String, limit: Int = 200, checkpoint: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<List<SearchHit>, Boolean> {
        require(limit in 1..200)
        active();checkpoint()
        val directory = File(root, "query-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法创建正文搜索目录" }
        try {
            val normalizer = UnicodeSearchNormalizer(directory)
            val needle = normalizer.query(query)
            if (needle.isEmpty()) return emptyList<SearchHit>() to false
            val hits = mutableListOf<SearchHit>()
            scanLease { active();checkpoint() }.use {
                for (index in 0 until count) {
                    active();checkpoint();progress(index, count)
                    open(index, directory) { active();checkpoint() }.use { document ->
                        normalizer.normalize(document) { active();checkpoint() }.use { normalized ->
                            val remaining = limit - hits.size
                            // When already full, one additional distinct source position proves truncation.
                            val result = normalized.find(needle, document, maxOf(1, remaining)) { active();checkpoint() }
                            if (remaining == 0 && result.ranges.isNotEmpty()) return hits to true
                            for (range in result.ranges.take(remaining)) {
                                active();checkpoint()
                                hits += SearchHit("$revision:${document.id}:${range.start}:${range.end}", document.locator(range), range,
                                    excerpt(document, range), document.chapter(range))
                            }
                            if (result.truncated) return hits to true
                        }
                    }
                    progress(index + 1, count)
                }
                return hits to false
            }
        } finally { directory.deleteRecursively() }
    }
    companion object {
        fun excerpt(document: CanonicalDocument, range: SourceRange): SearchExcerpt {
            val base = (range.start - 64).coerceAtLeast(0)
            val start = (range.start - base).toInt()
            val before = document.slice(base, start)
            val beforePoints = before.codePointCount(0, before.length)
            var beforeStart = before.offsetByCodePoints(0, maxOf(0, beforePoints - 30))
            if (beforeStart < before.length && before[beforeStart].isLowSurrogate()) beforeStart++
            val matched = document.slice(range.start, minOf(range.end - range.start, 256).toInt())
            val safeMatch = if (matched.lastOrNull()?.isHighSurrogate() == true) matched.dropLast(1) else matched
            val truncated = safeMatch.length.toLong() < range.end - range.start
            val after = if (truncated) "" else document.slice(range.end, 64)
            val afterPoints = after.codePointCount(0, after.length)
            var afterEnd = after.offsetByCodePoints(0, minOf(30, afterPoints))
            if (afterEnd > 0 && after[afterEnd - 1].isHighSurrogate()) afterEnd--
            val prefix = before.substring(beforeStart)
            return SearchExcerpt(prefix + safeMatch + (if (truncated) "…" else after.substring(0, afterEnd)), prefix.length,
                prefix.length + safeMatch.length, truncated)
        }
    }
}

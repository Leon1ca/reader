package com.localreader.app.core

import java.io.Closeable

enum class ContentFormat { TXT, EPUB }
data class ContentMetadata(val title: String, val author: String)
enum class TocSource { CHAPTERS, TEXT_SEGMENTS }
data class TocEntry(val id: String, val title: String, val depth: Int = 0)
data class TextControls(val encoding: String, val availableEncodings: List<String>, val tocSource: TocSource)
data class ReaderBookmark(val id: Long, val contentId: String, val locator: Locator, val excerpt: String, val created: Long)
/** Persistent reading extent is a format-owned measure, not a navigable locator. */
interface ReadingExtent
data class ReadingSnapshot(val current: Locator, val furthest: ReadingExtent, val rules: ReaderRules, val bookmarks: List<ReaderBookmark>)

/** Owns a format's open resources. The session, not the renderer or UI, closes this handle. */
interface OpenedBook : Closeable {
    val contentId: String
    val format: ContentFormat
    val revision: String
    val metadata: ContentMetadata
    val publication: ReaderPublication
    val search: com.localreader.app.core.search.BookSearch? get() = null
    val textControls: TextControls? get() = null
    fun paginationChapterEnd(locator: Locator): PaginationBoundary? = null
    fun toc(): List<TocEntry>
    fun locatorForToc(id: String): Locator?
    fun chapterOf(locator: Locator): TocEntry?
    fun fractionOf(locator: Locator): Double
    fun fractionOf(extent: ReadingExtent): Double
    fun excerpt(locator: Locator, maxChars: Int): String
    /** Accept only current-document positions; historical bookmarks still go through publication.target. */
    fun accepts(locator: Locator): Boolean
    fun furthestOf(first: ReadingExtent, second: Locator): ReadingExtent
}

fun interface OpenedBookFactory { suspend fun open(contentId: String): OpenedBook }

/** No database entities or format-specific offsets cross this storage boundary. */
interface ReadingStore {
    fun loadSnapshot(contentId: String): ReadingSnapshot
    fun savePosition(contentId: String, revision: String, current: Locator, furthest: ReadingExtent)
    fun bookmarks(contentId: String): List<ReaderBookmark>
    fun addBookmark(contentId: String, revision: String, locator: Locator, excerpt: String): Boolean
    fun deleteBookmark(contentId: String, id: Long)
    fun patchRules(contentId: String, revision: String, patch: RulesPatch): ReaderRules
}

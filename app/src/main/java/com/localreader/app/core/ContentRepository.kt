package com.localreader.app.core

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

data class ContentRecord(val id: String, val format: ContentFormat, val revision: String, val title: String,
    val author: String, val hash: String, val bytes: Long, val imported: Long, val lastRead: Long,
    val language: String? = null, val originalName: String? = null, val coverPath: String? = null, val finishedAt: Long? = null, val hiddenFromContinue: Boolean = false)
data class ContentSummary(val id: String, val format: ContentFormat, val title: String, val author: String,
    val bytes: Long, val imported: Long, val lastRead: Long, val fraction: Double, val furthestFraction: Double,
    val encoding: String? = null, val epubLayout: String? = null, val finishedAt: Long? = null,
    val hiddenFromContinue: Boolean = false, val coverPath: String? = null)

data class ImportResult(val name: String,val status: String,val bookId: String? = null,val outcome: ImportOutcome = ImportOutcome.FAILED)
/** SAF is a platform boundary; the repository receives a cancellable stream capability. */
data class ImportSource(val name: String,val mime: String,val declaredBytes: Long?,val open: () -> InputStream)
interface ContentRepository {
    fun observeAll(): Flow<List<ContentSummary>>
    fun summaries(): List<ContentSummary>
    fun content(id: String): ContentRecord?
    fun import(source: ImportSource,cancelled: () -> Boolean = { false }): ImportResult
    fun deleteDetailed(book: ContentRecord): ContentDeletion { delete(book);return ContentDeletion(book.id,true) }
    fun delete(book: ContentRecord)
    fun updateMetadata(id: String,title: String,author: String)
    fun markRead(id: String, revision: String, time: Long = System.currentTimeMillis())
    fun setFinished(id: String, finished: Boolean)
    fun hideFromContinue(id: String, hidden: Boolean)
    fun detail(id: String): Flow<BookDetail?>

}
data class BookDetail(val content: ContentRecord,val summary: ContentSummary,val characters: Long?,
    val bookmarkCount: Int,val hasFixedViewState: Boolean)

data class ContentDeletion(val id: String,val metadataRemoved: Boolean,val fileCleanupPending: Boolean=false,val error: String?=null)

package com.localreader.app.core

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

data class ContentRecord(val id: String, val format: ContentFormat, val revision: String, val title: String,
    val author: String, val hash: String, val bytes: Long, val imported: Long, val lastRead: Long,
    val language: String? = null, val originalName: String? = null, val coverPath: String? = null, val finishedAt: Long? = null)
data class ContentSummary(val id: String, val format: ContentFormat, val title: String, val author: String,
    val bytes: Long, val imported: Long, val lastRead: Long, val fraction: Double, val furthestFraction: Double,
    val encoding: String? = null, val epubLayout: String? = null, val finishedAt: Long? = null,
    val coverPath: String? = null,
    /** Records survive without their book copy, e.g. after restoring a records-only backup. */
    val filesMissing: Boolean = false,
    /** Ids of the categories this book is in; an empty set is the system view "uncategorized". */
    val categories: Set<Long> = emptySet())

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
    fun markRead(id: String, revision: String, time: Long = System.currentTimeMillis())
    fun setFinished(id: String, finished: Boolean)

}

data class ContentDeletion(val id: String,val metadataRemoved: Boolean,val fileCleanupPending: Boolean=false,val error: String?=null)

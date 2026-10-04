package com.localreader.app.core

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

data class ContentRecord(val id: String, val format: ContentFormat, val revision: String, val title: String,
    val author: String, val hash: String, val bytes: Long, val imported: Long, val lastRead: Long,
    val language: String? = null, val originalName: String? = null, val coverPath: String? = null, val finishedAt: Long? = null)
data class ContentSummary(val id: String, val format: ContentFormat, val title: String, val author: String,
    val bytes: Long, val imported: Long, val lastRead: Long, val fraction: Double, val furthestFraction: Double,
    val encoding: String? = null)

data class ImportResult(val name: String,val status: String,val bookId: String? = null)
/** SAF is a platform boundary; the repository receives a cancellable stream capability. */
data class ImportSource(val name: String,val mime: String,val declaredBytes: Long?,val open: () -> InputStream)
interface ContentRepository {
    fun observeAll(): Flow<List<ContentSummary>>
    fun summaries(): List<ContentSummary>
    fun content(id: String): ContentRecord?
    fun import(source: ImportSource,cancelled: () -> Boolean = { false }): ImportResult
    fun delete(book: ContentRecord)
    fun updateMetadata(id: String,title: String,author: String)
}

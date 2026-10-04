package com.localreader.app.data

import com.localreader.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.localreader.app.formats.txt.TxtOpenedBook
import com.localreader.app.formats.epub.*

class LibraryOpenedBookFactory(private val repository: LibraryRepository) : OpenedBookFactory, TextReencoder {
    private suspend fun create(work: suspend () -> OpenedBook): OpenedBook {
        var created: OpenedBook? = null
        try { return withContext(Dispatchers.IO) { work().also { created = it; currentCoroutineContext().ensureActive() } } }
        catch (error: Throwable) { created?.close(); throw error }
    }
    override suspend fun open(contentId: String): OpenedBook = create {
        val book = requireNotNull(repository.db.content(contentId)) { "书籍不存在" }
        if(book.format==ContentFormat.EPUB) {
            val cache=repository.db.epubCache(book.id)
            val source=EpubSource(repository.directory(book),cache)
            try { return@create EpubOpenedBook(contentId,ContentMetadata(book.title,book.author),source) }
            catch(error: Throwable) { source.close();throw error }
        }
        val source = repository.engine(requireNotNull(repository.db.txt(book.id)))
        try {
            val coroutine = currentCoroutineContext()
            TxtOpenedBook(contentId,ContentMetadata(book.title,book.author),source) { coroutine.ensureActive() }
                .also { coroutine.ensureActive() }
        } catch (error: Throwable) { source.close(); throw error }
    }
    override suspend fun reencode(contentId: String, encoding: String): ReencodedBook {
        var prepared: ReencodedBook? = null
        try { return withContext(Dispatchers.IO) {
        val book = requireNotNull(repository.db.content(contentId))
        var opened: OpenedBook? = null
        try {
            val coroutine = currentCoroutineContext()
            repository.reencodePrepared(requireNotNull(repository.db.txt(book.id)),encoding,checkpoint = { coroutine.ensureActive() }) { candidate ->
                val source = repository.engine(candidate)
                try {
                    val handle = TxtOpenedBook(contentId,ContentMetadata(candidate.title,candidate.author),source) { coroutine.ensureActive() }
                    opened = handle
                    val snapshot = SqliteReadingStore(repository.db).candidateSnapshot(candidate)
                    val presentation = ReadingPresentation.prepare(handle,snapshot)
                    coroutine.ensureActive()
                    ReencodedBook(handle,snapshot,presentation).also { prepared = it }
                }
                catch (error: Throwable) { val handle = opened; if (handle != null) handle.close() else source.close(); throw error }
            }
        } catch (error: Throwable) { opened?.close(); throw error }
        } } catch (error: Throwable) { prepared?.opened?.close(); throw error }
    }
}

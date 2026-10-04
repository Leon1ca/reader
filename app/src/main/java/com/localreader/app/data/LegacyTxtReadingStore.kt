package com.localreader.app.data

import com.localreader.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.localreader.app.formats.txt.TxtOpenedBook
import com.localreader.app.formats.txt.TxtReadingExtent

class LibraryOpenedBookFactory(private val repository: LibraryRepository) : OpenedBookFactory, TextReencoder {
    private suspend fun create(work: suspend () -> OpenedBook): OpenedBook {
        var created: OpenedBook? = null
        try { return withContext(Dispatchers.IO) { work().also { created = it; currentCoroutineContext().ensureActive() } } }
        catch (error: Throwable) { created?.close(); throw error }
    }
    override suspend fun open(contentId: String): OpenedBook = create {
        val book = requireNotNull(repository.db.book(contentId)) { "书籍不存在" }
        val source = repository.engine(book)
        try {
            val coroutine = currentCoroutineContext()
            TxtOpenedBook(contentId,ContentMetadata(book.title,book.author),source) { coroutine.ensureActive() }
                .also { coroutine.ensureActive() }
        } catch (error: Throwable) { source.close(); throw error }
    }
    override suspend fun reencode(contentId: String, encoding: String): ReencodedBook {
        var prepared: ReencodedBook? = null
        try { return withContext(Dispatchers.IO) {
        val book = requireNotNull(repository.db.book(contentId))
        var opened: OpenedBook? = null
        try {
            val coroutine = currentCoroutineContext()
            repository.reencodePrepared(book,encoding,checkpoint = { coroutine.ensureActive() }) { candidate ->
                val source = repository.engine(candidate)
                try {
                    val handle = TxtOpenedBook(contentId,ContentMetadata(candidate.title,candidate.author),source) { coroutine.ensureActive() }
                    opened = handle
                    val snapshot = LegacyTxtReadingStore(repository.db).snapshot(candidate)
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

/** SQLite v3 stays byte/field compatible. Only this adapter understands its TXT-specific rows. */
class LegacyTxtReadingStore(private val db: LibraryDb) : ReadingStore {
    private fun book(id: String) = requireNotNull(db.book(id)) { "书籍不存在" }
    private fun <T> transaction(work: () -> T): T {
        val database = db.writableDatabase
        database.beginTransaction()
        try { return work().also { database.setTransactionSuccessful() } }
        finally { database.endTransaction() }
    }
    override fun loadSnapshot(contentId: String): ReadingSnapshot = transaction { snapshot(book(contentId)) }
    internal fun snapshot(book: Book): ReadingSnapshot {
        return ReadingSnapshot(book.current,TxtReadingExtent(book.version,book.furthest),
            ReaderRules(fontSize = book.fontSize.coerceIn(12f,36f),theme = book.theme.takeIf { it in setOf("white","paper","dark") } ?: "white",
                flow = if (book.flow == "scrolled") ReaderFlow.SCROLLED else ReaderFlow.PAGINATED),bookmarks(book.id))
    }
    private fun currentText(book: Book, revision: String, locator: Locator): Locator.Text {
        require(book.version == revision && locator is Locator.Text && locator.version == revision &&
            locator.sourceOffset in 0..book.characters && locator.paragraph in 0 until book.paragraphs && locator.character >= 0) { "定位不属于当前书籍版本" }
        return locator
    }
    override fun savePosition(contentId: String, revision: String, current: Locator, furthest: ReadingExtent): Unit = transaction {
        val book = book(contentId)
        val anchor = currentText(book,revision,current)
        require(furthest is TxtReadingExtent && furthest.revision == revision && furthest.sourceOffset in 0..book.characters)
        db.savePosition(contentId,anchor,furthest.sourceOffset)
    }
    override fun bookmarks(contentId: String): List<ReaderBookmark> = db.bookmarks(contentId).map {
        ReaderBookmark(it.id,it.bookId,it.locator,it.excerpt,it.created)
    }
    override fun addBookmark(contentId: String, revision: String, locator: Locator, excerpt: String): Boolean = transaction {
        db.addBookmark(contentId,currentText(book(contentId),revision,locator),excerpt)
    }
    override fun deleteBookmark(contentId: String, id: Long): Unit = transaction {
        require(db.bookmarks(contentId).any { it.id == id }) { "书签不属于该书" }
        db.deleteBookmark(id)
    }
    override fun saveRules(contentId: String, revision: String, rules: ReaderRules): Unit = transaction {
        require(book(contentId).version == revision) { "书籍版本已更改" }
        db.setStyle(contentId,rules.fontSize,rules.theme)
        db.setFlow(contentId,rules.flow.wire)
    }
}

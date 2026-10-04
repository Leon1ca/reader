package com.localreader.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PreparedReader(val owner: ReadingSessionOwner,val session: ReadingSession)
/** Opens the source and prepares its UI state before transferring ownership. Cancellation closes the handle. */
class ReaderControllerFactory(private val books: OpenedBookFactory,private val store: ReadingStore) {
    suspend fun open(contentId: String,generation: Long,loadSnapshot: suspend (OpenedBook)->ReadingSnapshot = { book -> withContext(Dispatchers.IO) { store.loadSnapshot(book.contentId) } }): PreparedReader {
        val opened=books.open(contentId)
        try {
            val snapshot=loadSnapshot(opened)
            currentCoroutineContext().ensureActive()
            check(opened.revision==snapshot.current.version) { "书籍版本在打开期间改变" }
            val owner=ReadingSessionOwner(opened,generation,store,snapshot)
            val session=withContext(Dispatchers.IO) { owner.state() }
            currentCoroutineContext().ensureActive()
            return PreparedReader(owner,session)
        } catch(error: Throwable) { opened.close();throw error }
    }
}
/** UI commands call this typed port rather than database/format/view instances. */
data class ReaderActions(val goTo: (Locator)->Unit = {},val goToToc: (String)->Unit = {},val addBookmark: ()->Unit = {},
    val deleteBookmark: (Long)->Unit = {},val patchRules: (RulesPatch)->Unit = {},val resetRules: ()->Unit = {},
    val finished: (Boolean)->Unit = {},val close: ()->Unit = {},val flush: ()->Boolean = { true })

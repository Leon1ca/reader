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
            val stored=loadSnapshot(opened)
            val navigation=withContext(Dispatchers.IO) { val coroutine=currentCoroutineContext();opened.prepareNavigation(stored.current) { coroutine.ensureActive() } }
            val snapshot=stored.copy(current=navigation)
            currentCoroutineContext().ensureActive()
            check(opened.revision==snapshot.current.version) { "书籍版本在打开期间改变" }
            val owner=ReadingSessionOwner(opened,generation,store,snapshot)
            val session=withContext(Dispatchers.IO) { owner.state(notice=if(navigation!=stored.current)"附属正文不可用，已恢复至主文附近；原书签仍保留。" else null) }
            currentCoroutineContext().ensureActive()
            return PreparedReader(owner,session)
        } catch(error: Throwable) { opened.close();throw error }
    }
}
/** UI commands call this typed port rather than database/format/view instances. */
data class ReaderActions(val goTo: (Locator)->Unit = {},val goToToc: (String)->Unit = {},val addBookmark: ()->Unit = {},
    val deleteBookmark: (Long)->Unit = {},val patchRules: (RulesPatch)->Unit = {},
    val finished: (Boolean)->Unit = {},val close: ()->Unit = {},val flush: ()->Boolean = { true },val reencode: (String,ChapterRule?)->Unit = { _,_ -> },val restoreBookmark: (ReaderBookmark)->Unit = { })

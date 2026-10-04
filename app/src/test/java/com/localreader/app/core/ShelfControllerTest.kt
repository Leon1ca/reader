package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class ShelfControllerTest {
    private fun book(id: String,format: ContentFormat=ContentFormat.TXT,title: String=id,lastRead: Long=0,finished: Long?=null,hidden: Boolean=false)=
        ContentSummary(id,format,title,"作者",1,id.toLongOrNull() ?: 0,lastRead,0.2,0.6,finishedAt=finished,hiddenFromContinue=hidden)
    @Test fun thousandItemsFilterNormalizedTitleAndContinueAreIndependent() {
        val all=(1..1000).map { book(it.toString(),if(it%2==0)ContentFormat.EPUB else ContentFormat.TXT,lastRead=it.toLong()) }+
            book("fullwidth",title="ＡＢＣ１２３",lastRead=1001)+book("hidden",lastRead=1002,hidden=true)+book("finished",lastRead=1003,finished=1)
        val query=ShelfProjection.project(all,ShelfState(query="abc123"))
        assertEquals(listOf("fullwidth"),query.items.map { it.id });assertEquals(20,query.continueReading.size)
        assertEquals("fullwidth",query.continueReading.first().id);assertFalse(query.continueReading.any { it.hiddenFromContinue || it.finishedAt!=null })
        val epub=ShelfProjection.project(all,ShelfState(filter=ShelfFilter.EPUB,selected=setOf("1","2")))
        assertEquals(500,epub.items.size);assertEquals(setOf("2"),epub.selected)
        assertEquals(query.continueReading,epub.continueReading)
    }
    @Test fun collatorOrderHasDeterministicTieBreakAndRecentUnreadsUseImportTime() {
        val all=listOf(book("1",title="中"),book("2",title="阿"),book("3",title="阿"),book("4",title="中",lastRead=1))
        assertEquals(listOf("2","3","1","4"),ShelfProjection.project(all,ShelfState(sort=ShelfSort.TITLE)).items.map { it.id })
        assertEquals(listOf("4","3","2","1"),ShelfProjection.project(all,ShelfState()).items.map { it.id })
    }
    @Test fun crashPolicyRecoversTwiceAndNeedsExplicitRetryAfterExhaustion() {
        val renderer=object: RendererBackend {
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) {}
            override fun goTo(locator: Locator) {};override fun setRules(rules: ReaderRules,locator: Locator) {}
            override fun next() {};override fun prev() {};override fun close() {}
        }
        var invoked=0
        val controller=ReaderController(1,{ {} },{ true },ReaderActions(finished={invoked++}))
        controller.attach(renderer)
        repeat(2) { controller.receive(renderer,RendererEvent.Crashed("failure"));assertEquals(ReaderPhase.RECOVERING,controller.state.value.phase) }
        controller.receive(renderer,RendererEvent.Crashed("failure"));assertEquals(ReaderPhase.FAILED,controller.state.value.phase)
        assertEquals(2,controller.state.value.recoveryAttempt)
        controller.retry();assertEquals(3,controller.state.value.recoveryAttempt)
        controller.detach();controller.markFinished(true);assertEquals(0,invoked)
    }
}

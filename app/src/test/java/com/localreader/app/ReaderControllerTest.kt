package com.localreader.app

import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.Assert.*
import org.junit.Test

class ReaderControllerTest {
    @Test fun actualCommandConsumerRejectsRetiredRendererAndDoesNotJumpOnPositionUpdates() {
        val closed=mutableListOf<RendererBackend>();val events=mutableListOf<RendererEvent>();val first=FakeBackend();val second=FakeBackend()
        val controller=ReaderController(7,{ backend -> { closed.add(backend);backend.close() } },{true})
        controller.observe { events.add(it) };val document=ArticleSamplePublication();val anchor=Locator.Article(document.revision,"0",0)
        fun screen(serial: Int=0,rules: ReaderRules=ReaderRules())=ReadingSession("id",document.revision,7,ContentMetadata("t","a"),document,emptyList(),null,
            ReadingSnapshot(anchor,TxtReadingExtent(document.revision,0),rules,emptyList()),0.0,0.0,anchor,serial)
        val unbind=controller.attach(first);controller.present(screen());controller.present(screen());controller.next();controller.present(screen(1));controller.present(screen(1,ReaderRules(fontSize=18f)))
        assertEquals(listOf("open","next","goTo","setRules"),first.calls)
        controller.attach(second);controller.present(screen());controller.receive(first,RendererEvent.Error("old"));controller.receive(second,RendererEvent.Ready("profile",1))
        assertEquals(1,events.size);unbind();assertEquals(listOf("open"),second.calls)
        controller.detach();assertTrue(closed.contains(first));assertTrue(closed.contains(second))
    }
    @Test fun acceptedEndGeometryLatchesThroughReflowAndOnlyExplicitActionMarksFinished() {
        val backend=FakeBackend();val retired=FakeBackend();val finished=mutableListOf<Boolean>();var storedFinished=false
        val controller=ReaderController(4,{ { it.close() } },{true},ReaderActions(finished={ finished.add(it);storedFinished=it }),isFinished={storedFinished})
        val document=ArticleSamplePublication();val source=document.sourceRevision;val anchor=Locator.Article(source,"0",0)
        val mark=ReaderBookmark(1,"id",anchor,"visible",1)
        val session=ReadingSession("id",source,4,ContentMetadata("t","a"),document,emptyList(),null,ReadingSnapshot(anchor,TxtReadingExtent(source,0),ReaderRules(),listOf(mark)),0.0,0.0,anchor)
        controller.attach(backend);controller.present(session)
        fun position(end: Boolean,sig: String="layout")=RendererEvent.Position(anchor,"anchor",IntentCause.Restore,ReadingViewport(source,0,sig,listOf(PaginationSample(0,1,2,false)),listOf(VisibleSourceRange(anchor,anchor.copy(textOffset=3))),emptyList(),emptyList(),end))
        controller.receive(retired,position(true));assertFalse(controller.state.value.endPrompt)
        controller.receive(backend,RendererEvent.Ready("p",1));assertFalse(controller.state.value.endPrompt)
        controller.receive(backend,position(true));assertTrue(controller.state.value.endPrompt);assertEquals(listOf(mark),controller.state.value.currentPageBookmarks);assertTrue(finished.isEmpty())
        controller.receive(backend,RendererEvent.Loading);assertFalse(controller.state.value.endPrompt)
        controller.receive(backend,position(true,"same-end-after-rotation"));assertTrue(controller.state.value.endPrompt)
        controller.dismissEnd();controller.receive(backend,RendererEvent.Loading);controller.receive(backend,position(true,"resized"));assertFalse(controller.state.value.endPrompt)
        controller.receive(backend,position(false));controller.receive(backend,position(true));assertTrue(controller.state.value.endPrompt)
        controller.markFinished(true);assertEquals(listOf(true),finished);assertFalse(controller.state.value.endPrompt)
        controller.receive(backend,position(false));controller.receive(backend,position(true));assertFalse(controller.state.value.endPrompt)
        controller.markFinished(false);controller.receive(backend,position(false));controller.receive(backend,position(true));assertTrue(controller.state.value.endPrompt)
        controller.detach()
    }
    @Test fun tocEstimatesFollowAcceptedLayoutAndAreUnavailableDuringReplacement() {
        val backend=FakeBackend();val controller=ReaderController(4,{ { it.close() } },{true})
        val document=ArticleSamplePublication();val anchor=Locator.Article(document.revision,"0",0)
        val session=ReadingSession("id",document.revision,4,ContentMetadata("t","a"),document,listOf(TocEntry("toc","chapter")),null,
            ReadingSnapshot(anchor,TxtReadingExtent(document.revision,0),ReaderRules(),emptyList()),0.0,0.0,anchor,tocCoordinates=mapOf("toc" to PaginationBoundary(0,.5,true)))
        controller.attach(backend);controller.present(session)
        assertNull(controller.pageForToc("toc"))
        fun position(pages: Int,sig: String)=RendererEvent.Position(anchor,"anchor",IntentCause.Restore,ReadingViewport(document.revision,0,sig,listOf(PaginationSample(0,1,pages,false)),emptyList(),emptyList(),emptyList(),false))
        controller.receive(backend,position(4,"before"));assertEquals(PageEstimate(3,true),controller.pageForToc("toc"))
        controller.receive(backend,RendererEvent.Loading);assertNull(controller.pageForToc("toc"))
        controller.receive(backend,position(6,"after"));assertEquals(PageEstimate(4,true),controller.pageForToc("toc"))
        assertNull(controller.pageForToc("missing"));controller.detach();assertNull(controller.pageForToc("toc"))
    }
    private class FakeBackend: RendererBackend {
        val calls=mutableListOf<String>()
        override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) { calls.add("open") }
        override fun goTo(locator: Locator) { calls.add("goTo") }
        override fun setRules(rules: ReaderRules,locator: Locator) { calls.add("setRules") }
        override fun next() { calls.add("next") };override fun prev() { calls.add("prev") };override fun close() { calls.add("close") }
    }
}

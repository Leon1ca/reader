package com.localreader.app

import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.Assert.*
import org.junit.Test

class ReaderControllerTest {
    @Test fun uiTimingIncludesQueueWaitAndOnlyOwnerAcceptedCurrentPositionCompletesIt() {
        var time=0L;var ownerAccepts=false;var captured: ReaderOperationToken?=null
        val ledger=ReaderOperationLedger({time})
        val identity=RendererIdentity("runtime","render",3,9)
        val document=ArticleSamplePublication();val initial=Locator.Article(document.revision,"0",0)
        val destination=initial.copy(textOffset=3)
        val renderer=object: RendererBackend {
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) {}
            override fun setRules(rules: ReaderRules,locator: Locator) {}
            override fun goTo(locator: Locator) {}
            override fun next() {};override fun prev() {};override fun close() {}
            override fun next(operation: ReaderOperationToken?) {assertNotNull(operation);captured=operation}
        }
        val controller=ReaderController(7,{ {} },{ownerAccepts},timings=ledger)
        controller.attach(renderer)
        controller.present(ReadingSession("own",document.revision,7,ContentMetadata("own",""),document,emptyList(),null,ReadingSnapshot(initial,TxtReadingExtent(document.revision,0),ReaderRules(),emptyList()),0.0,0.0,initial))
        ownerAccepts=true;controller.receive(renderer,RendererEvent.Position(initial));time=10
        controller.next();val token=requireNotNull(captured);assertEquals(10L,token.startedNanos)
        time=210;assertTrue(ledger.bind(token,7,identity))
        ownerAccepts=false
        controller.receive(renderer,RendererEvent.Position(destination,operation=token,identity=identity))
        assertEquals(1,ledger.activeCount());assertTrue(ledger.records().isEmpty())
        ownerAccepts=true;time=310
        controller.receive(renderer,RendererEvent.Ready("profile",1));assertEquals(1,ledger.activeCount())
        controller.receive(renderer,RendererEvent.Position(destination,operation=token,identity=identity.copy(commandId=8)))
        assertEquals(1,ledger.activeCount())
        controller.receive(renderer,RendererEvent.Position(destination,operation=token,identity=identity))
        val record=ledger.records().single();assertEquals(300L,record.elapsedNanos)
        assertEquals(identity,requireNotNull(record.acceptance).identity);assertEquals(destination,record.acceptance?.locator)
        controller.receive(renderer,RendererEvent.Position(destination,operation=token,identity=identity))
        assertEquals(1,ledger.records().size);controller.detach()
    }
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
    @Test fun trustedTapUsesOneBackendCommandAndSelectionClearIsIndependentOfModal() {
        val renderer=FakeBackend();val controller=ReaderController(1,{ {} },{true})
        val doc=ArticleSamplePublication();val anchor=Locator.Article(doc.revision,"0",0)
        controller.attach(renderer);controller.present(ReadingSession("id",doc.revision,1,ContentMetadata("t","a"),doc,emptyList(),null,ReadingSnapshot(anchor,TxtReadingExtent(doc.revision,0),ReaderRules(),emptyList()),0.0,0.0,anchor))
        controller.receive(renderer,RendererEvent.Position(anchor))
        controller.receive(renderer,RendererEvent.Tap(TapRegion.RIGHT));assertEquals("next",renderer.calls.last())
        controller.receive(renderer,RendererEvent.Tap(TapRegion.LEFT,true));assertEquals(2,renderer.calls.count { it=="next" })
        controller.receive(renderer,RendererEvent.Selection(ReaderSelection("word",anchor,anchor.copy(textOffset=4))))
        val before=renderer.calls.size;controller.receive(renderer,RendererEvent.Tap(TapRegion.RIGHT));assertEquals(before,renderer.calls.size)
        controller.receive(renderer,RendererEvent.Selection(null));controller.receive(renderer,RendererEvent.Tap(TapRegion.CENTER));assertEquals(1L,controller.centerTaps.value)
        controller.receive(renderer,RendererEvent.Loading);assertNull(controller.selectionState.value)
        controller.detach();controller.receive(renderer,RendererEvent.Tap(TapRegion.CENTER));assertEquals(1L,controller.centerTaps.value)
    }
    @Test fun themeOverrideIsSessionOnlyAndNullRestoresPersistedRules() {
        val rendered=mutableListOf<ReaderRules>();var writes=0
        val renderer=object: RendererBackend {
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) { rendered+=rules }
            override fun setRules(rules: ReaderRules,locator: Locator) { rendered+=rules }
            override fun goTo(locator: Locator) {};override fun next() {};override fun prev() {};override fun close() {}
        }
        val controller=ReaderController(1,{ {} },{true},ReaderActions(patchRules={writes++}))
        val doc=ArticleSamplePublication();val anchor=Locator.Article(doc.revision,"0",0);val persisted=ReaderRules(theme="paper")
        controller.attach(renderer);controller.present(ReadingSession("id",doc.revision,1,ContentMetadata("t","a"),doc,emptyList(),null,ReadingSnapshot(anchor,TxtReadingExtent(doc.revision,0),persisted,emptyList()),0.0,0.0,anchor))
        controller.setThemeOverride("dark");controller.setThemeOverride(null)
        assertEquals(listOf("paper","dark","paper"),rendered.map { it.theme });assertEquals(0,writes);controller.detach()
    }
    private class FakeBackend: RendererBackend {
        val calls=mutableListOf<String>()
        override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) { calls.add("open") }
        override fun goTo(locator: Locator) { calls.add("goTo") }
        override fun setRules(rules: ReaderRules,locator: Locator) { calls.add("setRules") }
        override fun next() { calls.add("next") };override fun prev() { calls.add("prev") };override fun close() { calls.add("close") }
    }
}

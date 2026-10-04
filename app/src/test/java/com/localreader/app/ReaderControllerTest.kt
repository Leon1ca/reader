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
    private class FakeBackend: RendererBackend {
        val calls=mutableListOf<String>()
        override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules) { calls.add("open") }
        override fun goTo(locator: Locator) { calls.add("goTo") }
        override fun setRules(rules: ReaderRules,locator: Locator) { calls.add("setRules") }
        override fun next() { calls.add("next") };override fun prev() { calls.add("prev") };override fun close() { calls.add("close") }
    }
}

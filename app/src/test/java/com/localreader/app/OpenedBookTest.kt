package com.localreader.app

import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtOpenedBook
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OpenedBookTest {
    private fun fixture(block: (TxtOpenedBook, TxtEngine, MutableList<String>) -> Unit) {
        val directory = Files.createTempDirectory("opened-book").toFile()
        val source = File(directory,"original.txt").apply {
            writeText("第一章 开始\r\n\r\n中文😀 English 123。\r\n" + "x".repeat(99) + "😀Z\r\n" + "后续正文。\r\n".repeat(70) + "第二章 结束\r\n结尾。")
        }
        val events = mutableListOf<String>()
        TxtParser.build(source,directory)
        val engine = TxtEngine(directory,"revision")
        val counted = object : TxtTextSource by engine { override fun close() { events += "source"; engine.close() } }
        val opened = TxtOpenedBook("book",ContentMetadata("标题","作者"),counted)
        try { block(opened,engine,events) } finally { opened.close(); directory.deleteRecursively() }
    }
    @Test fun realTocPublicationMappingExcerptAndHistoricalRestore() = fixture { opened, engine, _ ->
        assertEquals(2,opened.toc().size)
        opened.toc().forEach { entry ->
            val locator = requireNotNull(opened.locatorForToc(entry.id))
            val target = opened.publication.target(locator)
            assertEquals(locator,opened.publication.locator(target.section,target.blockId,target.character)?.let {
                (it as Locator.Text).copy(fallbackFraction = 0.0)
            })
            assertEquals(entry,opened.chapterOf(locator))
        }
        assertEquals(opened.toc().map { it.id }.toSet(),opened.paginationTocCoordinates().keys)
        assertTrue(opened.paginationTocCoordinates().values.all { it.approximate })
        assertEquals(PaginationBoundary(opened.publication.paginationParts.lastIndex,1.0,true),opened.publication.paginationCoordinate(Locator.Text(opened.revision,engine.index.totalCharacters,0)))
        assertNull(opened.publication.paginationCoordinate(Locator.Text("old",0,0)))
        val text = engine.block(2)
        val locator = Locator.Text(engine.version,text.sourceOffset + 2,2,2)
        assertTrue(opened.accepts(locator))
        assertEquals("😀 English",opened.excerpt(locator,10))
        assertEquals(locator.sourceOffset.toDouble() / (engine.index.totalCharacters - 1),opened.fractionOf(locator),0.0)
        assertFalse(opened.accepts(locator.copy(version = "old")))
        assertFalse(opened.accepts(locator.copy(sourceOffset = 0)))
        assertNull(opened.locatorForToc("missing"))
        val target = opened.publication.target(locator.copy(version = "old",fallbackFraction = 0.75))
        assertTrue(target.nearby)
        assertTrue(target.blockId.toInt() > 2)
        assertEquals("",opened.excerpt(locator,0))
        val boundary = engine.block(3)
        fun at(character: Int) = Locator.Text(engine.version,boundary.sourceOffset + character,3,character)
        assertEquals("",opened.excerpt(at(99),1))
        assertEquals("😀",opened.excerpt(at(99),2))
        assertEquals("Z",opened.excerpt(at(100),1)) // inside a pair skips that pair rather than producing a fragment
        assertEquals("Z",opened.excerpt(at(100),2))
        assertEquals("x".repeat(99),opened.excerpt(at(0),100))
        assertEquals("x".repeat(99) + "😀",opened.excerpt(at(0),101))
        assertEquals("Z",opened.excerpt(at(101),1))
        assertEquals("",opened.excerpt(at(99),0))
    }
    private class Store(var snapshot: ReadingSnapshot) : ReadingStore {
        var fail = false
        var writes = 0
        override fun loadSnapshot(contentId: String) = snapshot
        override fun savePosition(contentId: String, revision: String, current: Locator, furthest: ReadingExtent) {
            if (fail) error("disk unavailable")
            writes++; snapshot = snapshot.copy(current = current,furthest = furthest)
        }
        override fun bookmarks(contentId: String) = snapshot.bookmarks
        override fun addBookmark(contentId: String, revision: String, locator: Locator, excerpt: String) = true
        override fun deleteBookmark(contentId: String, id: Long) = Unit
        override fun patchRules(contentId: String, revision: String, patch: RulesPatch) = ReaderRules()
    }
    private fun snapshot(opened: TxtOpenedBook) = ReadingSnapshot(requireNotNull(opened.locatorForToc(opened.toc().first().id)),
        TxtReadingExtent(opened.revision,0),ReaderRules(),emptyList())
    @Test fun backtrackingFlushRetainsExtentAndFailedSaveRemainsRetryable() = fixture { opened, engine, _ ->
        val store = Store(snapshot(opened))
        val owner = ReadingSessionOwner(opened,4,store,store.snapshot)
        fun locator(index: Int) = engine.block(index).let { Locator.Text(engine.version,it.sourceOffset,index) }
        assertTrue(owner.position(locator(60),true,100))
        assertEquals(400L,owner.saveDelay(101))
        assertTrue(owner.position(locator(10),true,350))
        assertEquals(100L,owner.saveDelay(1000)) // continuous events still have a one-second deadline
        assertFalse(owner.position(locator(60).copy(version = "old"),true,1000))
        store.fail = true
        try { owner.flush(); fail("save should fail") } catch (_: IllegalStateException) { }
        assertTrue(owner.hasPending)
        store.fail = false; owner.flush()
        assertFalse(owner.hasPending)
        assertEquals(locator(10),store.snapshot.current)
        assertEquals(locator(60).sourceOffset,(store.snapshot.furthest as TxtReadingExtent).sourceOffset)
        assertEquals(1,store.writes)
        assertTrue(owner.position(locator(70),false,1200)); owner.flush()
        assertEquals(locator(60).sourceOffset,(store.snapshot.furthest as TxtReadingExtent).sourceOffset)
    }
    @Test fun backendRevocationMustPrecedeExactlyOnceSourceClose() = fixture { opened, _, events ->
        val store = Store(snapshot(opened)); val owner = ReadingSessionOwner(opened,1,store,store.snapshot)
        val backend = object : RendererBackend {
            override fun open(document: ReaderPublication, locator: Locator, rules: ReaderRules) = Unit
            override fun goTo(locator: Locator) = Unit
            override fun setRules(rules: ReaderRules, locator: Locator) = Unit
            override fun next() = Unit
            override fun prev() = Unit
            override fun close() { events += "renderer" }
        }
        owner.bind(backend)
        try { owner.closeSource(); fail("must revoke first") } catch (_: IllegalStateException) { }
        assertTrue(events.isEmpty())
        owner.revokeRenderers(); owner.closeSource(); owner.closeSource(); opened.close()
        assertEquals(listOf("renderer","source"),events)
        assertFalse(owner.position(Locator.Text("revision",0,0),true,100))
    }
    @Test fun legacyExtentPreservesRawNewlineOffsetWithoutBecomingNavigationAnchor() = fixture { opened, _, _ ->
        val initial = snapshot(opened).copy(furthest = TxtReadingExtent(opened.revision,7))
        val store = Store(initial); val owner = ReadingSessionOwner(opened,1,store,initial)
        assertEquals(7L,(owner.snapshot.furthest as TxtReadingExtent).sourceOffset)
        assertEquals(opened.fractionOf(initial.furthest),owner.state().furthestFraction,0.0)
        owner.position(initial.current,false,100); owner.flush()
        assertEquals(7L,(store.snapshot.furthest as TxtReadingExtent).sourceOffset)
    }
}

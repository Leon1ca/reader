package com.localreader.app.core

import com.localreader.app.formats.txt.TxtOpenedBook
import com.localreader.app.formats.txt.TxtReadingExtent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReaderControllerFactoryTest {
    @Test fun defaultSnapshotReadsOffCallerAndFailedPreparationClosesActualSource() = runBlocking {
        val caller=Thread.currentThread()
        var closed=0
        val source=object: TxtTextSource {
            override val version="rev"
            override val index=TextIndex("UTF-8",1,3,emptyList())
            override fun block(paragraph: Int)=TextBlock(0,"abc",0,false)
            override fun resolve(locator: Locator)=ResolvedText(0,0,false)
            override fun close() { closed++ }
        }
        var fail=false
        val store=object: ReadingStore {
            override fun loadSnapshot(contentId: String): ReadingSnapshot {
                assertNotSame(caller,Thread.currentThread())
                if(fail)throw IOException("snapshot unavailable")
                return ReadingSnapshot(Locator.Text("rev",0,0),TxtReadingExtent("rev",0),ReaderRules(),emptyList())
            }
            override fun savePosition(contentId: String,revision: String,current: Locator,furthest: ReadingExtent) {}
            override fun bookmarks(contentId: String)=emptyList<ReaderBookmark>()
            override fun addBookmark(contentId: String,revision: String,locator: Locator,excerpt: String)=false
            override fun deleteBookmark(contentId: String,id: Long) {}
            override fun patchRules(contentId: String,revision: String,patch: RulesPatch)=ReaderRules()
        }
        val factory=ReaderControllerFactory(OpenedBookFactory { TxtOpenedBook("book",ContentMetadata("title","author"),source) },store)
        val prepared=factory.open("book",1)
        assertEquals("book",prepared.session.contentId)
        prepared.owner.closeSource();assertEquals(1,closed)
        fail=true
        assertThrows(IOException::class.java) { runBlocking { factory.open("book",2) } }
        assertEquals(2,closed)
    }
}

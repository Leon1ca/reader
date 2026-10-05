package com.localreader.app.formats.epub

import com.localreader.app.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EpubOpenedBookTest {
    @Test fun canonicalEndpointsElementsQuotesAndCoordinateOnlyChapterLookup() {
        val root=kotlin.io.path.createTempDirectory("epub-source").toFile()
        try {
            val path=PackagePath.entry("OPS/a.xhtml")
            val text="😀alpha尾"
            File(root,"sections").mkdirs();File(root,"sections/0.txt").writeText(text)
            val section=EpubSectionRecord(0,path,text.length,"sections/0.txt","sections/0-author.html","sections/0-custom.html",mapOf("tail" to CanonicalFragment(7,null)),mapOf("image" to text.length))
            val record=EpubCacheRecord("revision","title","author",null,"id",listOf(section),listOf(EpubToc("start",PackageHref(path,null),0),EpubToc("tail",PackageHref(path,"tail"),1)),emptyList(),emptyList(),"reflowable",false,"ltr")
            val source=EpubSource(root,record);val book=EpubOpenedBook("id",ContentMetadata("title","author"),source)
            val end=book.publication.locator(RendererAnchor.Text(0,text.length))!!
            assertTrue(book.accepts(end));assertEquals(1.0,book.fractionOf(end),0.0)
            assertEquals("tail",book.chapterOf(end)?.title)
            assertEquals(RendererAnchor.Element(0,"image"),book.publication.target(book.publication.locator(RendererAnchor.Element(0,"image"))!!).anchor)
            assertEquals("",book.excerpt(Locator.Epub("revision",0,0),1));assertEquals("😀",book.excerpt(Locator.Epub("revision",0,0),2))
            assertNotEquals(LocatorCodec.key(Locator.Epub("revision",0,0,elementId="one")),LocatorCodec.key(Locator.Epub("revision",0,0,elementId="two")))
            assertEquals("epub|revision|0|0",LocatorCodec.key(Locator.Epub("revision",0,0)))
            assertNull(book.publication.locator(RendererAnchor.Text(0,-1)))
            assertNull(book.publication.linkTarget(PackageHref(PackagePath.entry("OPS/a%20.xhtml"),null).identity))
            assertEquals(7,(book.publication.linkTarget(PackageHref(path,"tail").identity) as Locator.Epub).offset)
            // Chapter names use cached coordinates and remain available after a file disappears.
            File(root,"sections/0.txt").delete();assertEquals("tail",book.chapterOf(end)?.title)
            assertEquals(PaginationBoundary(0,7.0/text.length,true),book.paginationTocCoordinates()["epub-toc:1"])
            assertEquals(PaginationBoundary(0,1.0,true),book.publication.paginationCoordinate(end))
            assertNull(book.publication.paginationCoordinate(Locator.Epub("old",0,0)))
            book.close();assertFalse(book.accepts(end));assertThrows(IllegalStateException::class.java) { source.open("sections/0.txt") }
        } finally { root.deleteRecursively() }
    }
    @Test fun auxiliaryPublicationMapsOnlyItsCanonicalDocumentAndKeepsUtf16ExcerptSafe() {
        val root=kotlin.io.path.createTempDirectory("aux-source").toFile()
        try {
            File(root,"sections").mkdirs();File(root,"sections/0.txt").writeText("😀note")
            val section=EpubSectionRecord(0,PackagePath.entry("notes.xhtml"),6,"sections/0.txt","sections/0-author.html","sections/0-custom.html",emptyMap(),emptyMap())
            val record=EpubCacheRecord("source","notes","author",null,"id",listOf(section),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr")
            EpubSource(root,record).use { source ->
                val main=Locator.Epub("source",2,40)
                val aux=EpubResourcePublication(EpubPublication(source),source,"a".repeat(32),main)
                val first=aux.locator(RendererAnchor.Text(0,0)) as Locator.EpubResource
                assertEquals(main,first.returnTo);assertEquals("a".repeat(32),first.docKey)
                assertEquals(RendererAnchor.Text(0,3),aux.target(first.copy(offset=3)).anchor)
                assertEquals("",aux.excerpt(first,1));assertEquals("😀",aux.excerpt(first,2));assertEquals("no",aux.excerpt(first.copy(offset=1),2))
                assertTrue(aux.auxiliary);assertTrue(aux.paginationParts.isEmpty());assertNull(aux.paginationCoordinate(first))
                assertFalse(aux.accepts(first.copy(docKey="b".repeat(32))))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun technicalTrailingCaretIsNotTheNextPartBookmarkButLogicalEofRemainsVisible() {
        val root=kotlin.io.path.createTempDirectory("epub-visible-endpoint").toFile()
        try {
            File(root,"sections").mkdirs();File(root,"sections/0.txt").writeText("ABCDEFGHIJ")
            val section=EpubSectionRecord(0,PackagePath.entry("a.xhtml"),10,"sections/0.txt","sections/0-author.html","sections/0-custom.html",emptyMap(),emptyMap())
            val parts=listOf(EpubPartRecord(0,0,0,5,"sections/0-author.html","sections/0-custom.html",emptyMap()),EpubPartRecord(1,0,5,5,"sections/1-author.html","sections/1-custom.html",emptyMap()))
            val record=EpubCacheRecord("source","t","a",null,"id",listOf(section),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr",parts=parts)
            EpubOpenedBook("id",ContentMetadata("t","a"),EpubSource(root,record)).use { book ->
                assertNull(book.publication.visiblePoint(RendererAnchor.Text(0,5)))
                assertEquals(5,(book.publication.visiblePoint(RendererAnchor.Text(1,0)) as Locator.Epub).offset)
                assertEquals(10,(book.publication.visiblePoint(RendererAnchor.Text(1,5)) as Locator.Epub).offset)
                assertEquals(PaginationBoundary(1,0.0,true),book.publication.paginationCoordinate(Locator.Epub("source",0,5)))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun historicalRecoveryIsNearbyAndExtentOrdersSpineBeforeOffset() {
        val root=kotlin.io.path.createTempDirectory("epub-recovery").toFile()
        try {
            File(root,"sections").mkdirs();File(root,"sections/0.txt").writeText("before target after")
            val section=EpubSectionRecord(0,PackagePath.entry("a.xhtml"),19,"sections/0.txt","sections/0-author.html","sections/0-custom.html",emptyMap(),emptyMap())
            val record=EpubCacheRecord("new","t","a",null,"id",listOf(section),emptyList(),emptyList(),emptyList(),"reflowable",false,"ltr")
            EpubOpenedBook("id",ContentMetadata("t","a"),EpubSource(root,record)).use { book ->
                val target=book.publication.target(Locator.Epub("old",0,999,"target",fallbackFraction=0.5))
                assertTrue(target.nearby);assertEquals(RendererAnchor.Text(0,7),target.anchor)
                assertEquals(EpubReadingExtent("new",0,15),book.furthestOf(EpubReadingExtent("new",0,15),Locator.Epub("new",0,2)))
            }
        } finally { root.deleteRecursively() }
    }
}

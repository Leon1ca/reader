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
            book.close();assertFalse(book.accepts(end));assertThrows(IllegalStateException::class.java) { source.open("sections/0.txt") }
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

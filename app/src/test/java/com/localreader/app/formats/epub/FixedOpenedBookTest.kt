package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FixedOpenedBookTest {
    @Test fun viewportUsesPageRootNotInlineSvgAndRejectsNonFiniteOrigins() {
        assertEquals(600.0 to 900.0,EpubFixedViewport.resolve("<html><body><svg viewBox='0 0 32 32'></svg></body></html>","width=600,height=900"))
        assertEquals(600.0 to 900.0,EpubFixedViewport.resolve("<body><svg viewBox='0 0 600 900'><image href='local'/></svg></body>",null))
        assertEquals(.5 to .5,EpubFixedViewport.resolve("<svg viewBox='0 0 .5 .5'></svg>",null,true))
        assertEquals(600.0 to 900.0,EpubFixedViewport.resolve("<svg viewBox='0,0,600,900'></svg>",null,true))
        assertEquals(400.0 to 500.0,EpubFixedViewport.resolve("<svg viewBox='NaN 0 600 900'></svg>","width=400,height=500",true))
        assertEquals(300.0 to 150.0,EpubFixedViewport.resolve("<svg width='300px' height='150px'></svg>",null,true))
        assertEquals(800.0 to 1200.0,EpubFixedViewport.resolve("<meta name='viewport' content='width=800, height=1200'><svg viewBox='0 0 32 32'></svg>","width=600,height=900"))
        assertEquals(4000.0 to 6000.0,EpubFixedViewport.resolve("<img src='local'>",null,imageSize={4000.0 to 6000.0}))
        assertNull(EpubFixedViewport.resolve("<meta name='viewport' content='width=Infinity,height=1'>",null))
    }
    @Test fun pageCoordinatesRoundTripWithoutBecomingTextOffsetsAndBookmarksDeduplicateByPage() {
        val page=Locator.Page("revision",29,.25,.66)
        assertEquals(page,LocatorCodec.decode(LocatorCodec.encode(page)))
        assertEquals(LocatorCodec.key(page),LocatorCodec.key(page.copy(x=.9,y=.1)))
        assertNotEquals(LocatorCodec.key(page),LocatorCodec.key(page.copy(page=28)))
        val extent=FixedReadingExtent("revision",29,.25,.66)
        assertEquals(extent,ReadingExtentCodec.decode(ReadingExtentCodec.encode(extent)))
        assertEquals((29+.66)/30,FixedCoordinates.fraction(30,29,.66),0.000001)
        assertThrows(IllegalArgumentException::class.java) { ReadingExtentCodec.encode(extent.copy(x=Double.NaN)) }
        listOf(page.copy(x=Double.NaN),page.copy(y=1.1),page.copy(x=Double.POSITIVE_INFINITY),page.copy(page=-1)).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { LocatorCodec.encode(invalid) } }
    }
    @Test fun fixedPublicationUsesLogicalPageAndKeepsAuthorHtmlAndLegacyBookmarks() {
        val directory=Files.createTempDirectory("fixed-publication").toFile()
        try {
            File(directory,"sections").mkdir()
            val sections=(0..2).map { i ->
                File(directory,"sections/$i-author.html").writeText("<body style='font:700 31px serif'><p>Page$i</p></body>")
                File(directory,"sections/$i.txt").writeText("Page$i")
                EpubSectionRecord(i,PackagePath.entry("$i.xhtml"),5,"sections/$i.txt","sections/$i-author.html","sections/$i-author.html",emptyMap(),emptyMap())
            }
            val record=EpubCacheRecord("revision","Book","Writer",null,"uid",sections,listOf(EpubToc("Page2",PackageHref(sections[2].path,null),0)),emptyList(),emptyList(),"pre-paginated",false,"rtl",renderRevision="revision-fxl1",fixedPages=sections.map { EpubFixedPage(it.index,if(it.index==0).5 else 600.0,if(it.index==0).5 else 900.0,"pre-paginated","auto",null) })
            FixedOpenedBook("id",ContentMetadata("Book","Writer"),EpubSource(directory,record)).use { opened ->
                val pub=opened.publication
                assertEquals(WebContentKind.FIXED,pub.kind);assertEquals(.5,requireNotNull(pub.fixedLayout).pages.first().width,0.0);assertEquals(2,pub.protocolVersion);assertEquals("rtl",pub.direction)
                val page=Locator.Page(opened.revision,2,.25,.66)
                assertEquals(page,pub.locator(pub.target(page).anchor))
                assertTrue(pub.section(2).html.contains("31px"))
                assertEquals(page,opened.furthestOf(FixedReadingExtent(opened.revision,0,0.0,0.0),page).let { Locator.Page((it as FixedReadingExtent).revision,it.page,it.x,it.y) })
                val legacy=Locator.Epub(opened.revision,2,50)
                assertTrue(pub.target(legacy).nearby);assertEquals(2,pub.target(legacy).section)
                assertFalse(pub.target(legacy.copy(offset=0)).nearby)
                assertEquals(Locator.Page(opened.revision,2,0.0,0.0),opened.locatorForToc("fixed-toc:0"))
                val css=RuleCompiler.compile(WebContentKind.FIXED,ReaderRules(fontSize=36f,epubStyleMode="CUSTOM"),"https://appassets.androidplatform.net/fonts/readest.css")
                assertEquals("",css.beforeAuthor);assertEquals("",css.afterAuthor)
            }
        } finally { directory.deleteRecursively() }
    }
}

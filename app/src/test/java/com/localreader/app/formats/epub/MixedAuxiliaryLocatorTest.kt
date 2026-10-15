package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** R-01: a mixed book must accept, measure and excerpt auxiliary body locators prepared by its own interactions. */
class MixedAuxiliaryLocatorTest {
    private val revision="a".repeat(32)
    private val key=MessageDigest.getInstance("SHA-256").digest("note.xhtml".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun writeCache(dir: File,spines: List<String>,mixed: Boolean) {
        File(dir,"sections").mkdirs()
        val sections=JSONArray();val parts=JSONArray()
        spines.forEachIndexed { index,name ->
            val text=if(name=="note.xhtml")"note text" else "main text"
            File(dir,"sections/$index.txt").writeText(text)
            for(kind in listOf("author","custom"))File(dir,"sections/$index-$kind.html").writeText("<html><head></head><body><p>$text</p></body></html>")
            sections.put(JSONObject().put("index",index).put("path",name).put("characters",text.length).put("canonical","sections/$index.txt")
                .put("authorHtml","sections/$index-author.html").put("customHtml","sections/$index-custom.html").put("fragments",JSONObject()).put("elements",JSONObject()).put("hiddenRanges",JSONArray()))
            parts.put(JSONObject().put("index",index).put("spine",index).put("start",0).put("characters",text.length)
                .put("authorHtml","sections/$index-author.html").put("customHtml","sections/$index-custom.html").put("elements",JSONObject()))
        }
        val fixed=JSONArray().apply { if(mixed)put(JSONObject().put("index",1).put("width",600).put("height",800).put("layout","pre-paginated").put("spread","auto").put("pageSpread","")) }
        File(dir,"package.json").writeText(JSONObject().put("schema",2).put("revision",revision)
            .put("renderRevision",revision+"-parts2-style5-split384"+(if(mixed)"-mixed1" else ""))
            .put("title","fixture").put("author","").put("identifier","id").put("language","zh").put("sections",sections).put("parts",parts)
            .put("toc",JSONArray()).put("resources",JSONArray()).put("warnings",JSONArray()).put("layout","reflowable").put("mixed",mixed).put("direction","ltr")
            .put("fixedPages",fixed).put("splitTargetBytes",384*1024).put("splitMaxBytes",512*1024).toString())
    }

    private fun withBook(block: (MixedOpenedBook,Locator.Epub,Locator.Page) -> Unit) {
        val root=kotlin.io.path.createTempDirectory("mixed-aux").toFile()
        try {
            writeCache(root,listOf("main.xhtml","fixed.xhtml"),true)
            writeCache(File(root,"auxiliary-1-split384/$key"),listOf("note.xhtml"),false)
            val book=MixedOpenedBook("fixture",ContentMetadata("fixture",""),EpubSource(root,EpubCache.read(root),root),File(root,"original.epub"),null,"hash")
            try { block(book,Locator.Epub(revision,0,0),Locator.Page(revision,1,0.25,0.5)) } finally { book.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun preparedAuxiliaryLocatorIsAcceptedWithReflowReturn() = withBook { book,main,_ ->
        val prepared=book.prepareNavigation(Locator.EpubResource(revision,key,0,main,"",null),main) {}
        assertTrue(prepared is Locator.EpubResource)
        assertTrue(book.interactions!!.accepts(prepared))
        assertTrue(book.accepts(prepared))
        assertEquals("note text".take(4),book.excerpt(prepared,4))
        assertEquals(book.fractionOf(main),book.fractionOf(prepared),0.0)
        val first=EpubReadingExtent(revision,0,3)
        assertSame(first,book.furthestOf(first,prepared))
    }

    @Test fun preparedAuxiliaryLocatorIsAcceptedWithFixedPageReturn() = withBook { book,main,page ->
        val prepared=book.prepareNavigation(Locator.EpubResource(revision,key,0,page,"",null),main) {}
        assertTrue(prepared is Locator.EpubResource)
        assertEquals(page,(prepared as Locator.EpubResource).returnTo)
        assertTrue(book.accepts(prepared))
        assertEquals(book.fractionOf(page),book.fractionOf(prepared),0.0)
        assertEquals(4,book.excerpt(prepared,4).length)
        val fixedExtent=FixedReadingExtent(revision,1,0.25,0.5)
        assertSame(fixedExtent,book.furthestOf(fixedExtent,prepared))
    }

    @Test fun unknownOrClosedAuxiliaryLocatorIsRejected() = withBook { book,main,_ ->
        val unknown=Locator.EpubResource(revision,"f".repeat(32),0,main,"",null)
        assertFalse(book.accepts(unknown))
        assertEquals("",book.excerpt(unknown,8))
        assertThrows(IllegalArgumentException::class.java) { book.furthestOf(EpubReadingExtent(revision,0,0),unknown) }
        val prepared=book.prepareNavigation(Locator.EpubResource(revision,key,0,main,"",null),main) {}
        book.close()
        assertFalse(book.accepts(prepared))
    }
}

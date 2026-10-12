package com.localreader.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.formats.epub.*
import com.localreader.app.ui.WebReaderAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MixedEpubRendererTest {
    private fun waitFor(predicate: ()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+20000
        while(!predicate()) { check(SystemClock.uptimeMillis()<deadline) { "混排渲染超时" };Thread.sleep(25) }
    }
    @Test fun authoredOverridesSwitchBackendBothDirectionsAndKeepStableSourceLocators() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"mixed-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { for(global in listOf("reflowable","pre-paginated")) {
            val original=File(root,"$global.epub")
            ZipOutputStream(original.outputStream()).use { zip ->
                fun entry(name: String,value: String,stored: Boolean=false) {
                    val bytes=value.toByteArray();val item=ZipEntry(name)
                    if(stored) { item.method=ZipEntry.STORED;item.size=bytes.size.toLong();item.compressedSize=item.size;item.crc=CRC32().apply { update(bytes) }.value }
                    zip.putNextEntry(item);zip.write(bytes);zip.closeEntry()
                }
                entry("mimetype","application/epub+zip",true)
                entry("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
                entry("book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">mixed-test</dc:identifier><dc:title>混排</dc:title><dc:language>zh</dc:language><meta property="rendition:layout">$global</meta></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a" properties="rendition:layout-reflowable"/><itemref idref="b" properties="rendition:layout-pre-paginated"/><itemref idref="c" properties="rendition:layout-reflowable"/></spine></package>""")
                entry("a.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>前</title></head><body><p>前章正文</p></body></html>")
                entry("b.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>页</title><meta name='viewport' content='width=600,height=800'/><style>html,body{margin:0;width:600px;height:800px}p{position:absolute;left:120px;top:240px;color:rgb(220,0,0)}</style></head><body><p>固定页设计</p></body></html>")
                entry("c.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>后</title></head><body><p>末章正文</p></body></html>")
            }
            val hash=MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) }
            val directory=File(root,"cache-$global");val cache=EpubCache.build(original,directory,hash)
            assertTrue(cache.mixed);assertEquals(listOf(1),cache.fixedPages.map { it.index })
            val source=EpubSource(directory,cache);val book=MixedOpenedBook("mixed",ContentMetadata("混排",""),source)
            val position=AtomicReference<RendererEvent.Position?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun evaluate(code: String): String { val value=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript(code) { value.set(it) } };waitFor { value.get()!=null };return value.get()!! }
                fun awaitPosition(): RendererEvent.Position { waitFor { position.get()!=null || error.get()!=null };assertNull(error.get());return position.get()!! }
                fun next(): RendererEvent.Position { position.set(null);scenario.onActivity { adapter.next() };return awaitPosition() }
                fun prev(): RendererEvent.Position { position.set(null);scenario.onActivity { adapter.prev() };return awaitPosition() }
                try {
                    scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) { is RendererEvent.Position -> position.set(event);is RendererEvent.Error -> error.set(event.message);else -> Unit } };activity.setContentView(adapter.view);adapter.open(book.publication,Locator.Epub(book.revision,0,0),ReaderRules()) }
                    val first=awaitPosition();assertTrue(first.locator is Locator.Epub);assertFalse(first.viewport!!.atEnd)
                    assertEquals("\"FOLIATE-PAGINATOR\"",evaluate("document.getElementById('renderer').tagName"))
                    val page=next();assertEquals(1,(page.locator as Locator.Page).page);assertEquals(IntentCause.UserNavigation,page.intent);assertFalse(page.viewport!!.atEnd)
                    assertEquals("\"FOLIATE-FXL\"",evaluate("document.getElementById('renderer').tagName"))
                    assertTrue(book.accepts(page.locator));assertTrue(page.viewport!!.contains(page.locator))
                    evaluate("window.originalEngine=document.getElementById('renderer');true")
                    position.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fxlZoom="2"),page.locator) };awaitPosition()
                    assertEquals("true",evaluate("window.originalEngine===document.getElementById('renderer')"))
                    val last=next();assertEquals(2,(last.locator as Locator.Epub).spine);assertTrue(last.viewport!!.atEnd)
                    val back=prev();assertEquals(1,(back.locator as Locator.Page).page)
                    val restored=prev();assertEquals(0,(restored.locator as Locator.Epub).spine)
                    position.set(null);scenario.onActivity { adapter.goTo(Locator.Page(book.revision,1,0.0,0.0)) };assertEquals(1,(awaitPosition().locator as Locator.Page).page)
                } finally { scenario.onActivity { adapter.close() };book.close() }
            }
        } } finally { root.deleteRecursively() }
    }
}

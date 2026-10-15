package com.localreader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.ImportSource
import com.localreader.app.data.LibraryOpenedBookFactory
import com.localreader.app.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * UIF-03: a legitimate book whose body TEXT contains `style="font-family: serif;"` (a code sample) must read exactly as indexed.
 * The font redirect may only touch real style attributes, so the served text of every section equals its canonical text.
 */
class EpubLiteralStyleTextTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<MainActivity>()

    private fun epub(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String,text: String,stored: Boolean = false) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if(stored) { entry.method = ZipEntry.STORED;entry.size = bytes.size.toLong();entry.compressedSize = bytes.size.toLong();entry.crc = java.util.zip.CRC32().also { it.update(bytes) }.value }
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
            add("mimetype","application/epub+zip",stored = true)
            add("META-INF/container.xml","<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
            add("OEBPS/content.opf","<?xml version=\"1.0\" encoding=\"utf-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>字面样式</dc:title><dc:language>zh-CN</dc:language><meta property=\"dcterms:modified\">2026-10-14T00:00:00Z</meta></metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>")
            add("OEBPS/nav.xhtml","<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>nav</title></head><body><nav epub:type=\"toc\"><ol><li><a href=\"c1.xhtml\">第一章</a></li></ol></nav></body></html>")
            add("OEBPS/c1.xhtml","<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head><body><h1>第一章</h1><p>给元素写上 style=\"font-family: serif;\" 就能改字体。</p><p style=\"font-family: serif;\">这一段的真实属性要改。</p><pre>style=\"font-family: serif;\"</pre></body></html>")
        }
        return out.toByteArray()
    }

    @Test fun servedSectionTextEqualsTheIndexedTextAndOnlyTheRealAttributeIsRedirected() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir,"literal-style-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object: android.content.ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getCacheDir() = File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: android.database.sqlite.SQLiteDatabase.CursorFactory?,handler: android.database.DatabaseErrorHandler?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
        }
        val repository = LibraryRepository(context);repository.recover()
        try {
            val bytes = epub()
            val id = requireNotNull(repository.import(ImportSource("literal.epub","application/epub+zip",bytes.size.toLong()) { bytes.inputStream() }).bookId)
            val book = runBlocking { LibraryOpenedBookFactory(repository).open(id) }
            try {
                val publication = book.publication
                val canonical = File(repository.directory(requireNotNull(repository.content(id))),"sections/0.txt").readText()
                val html = publication.section(0).html
                assertEquals("the text a browser sees is the text the index recorded",canonical,Jsoup.parse(html).body().wholeText())
                assertTrue("the real attribute is redirected: $html",Regex("<p style=\"[^\"]*font-family:\\s*var\\(--reader-font\\)").containsMatchIn(html))
                assertEquals("the body text keeps its literal style example",2,Regex("style=\"font-family: serif;\"").findAll(Jsoup.parse(html).body().wholeText()).count())
            } finally { book.close() }
        } finally { runCatching { repository.db.close() };root.deleteRecursively() }
    }
    /** Uses the real import → Activity → renderer bridge; canonical inspection alone cannot prove WebView READY. */
    @Test fun literalStyleTextOpensInTheProductionWebView() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val app = target.applicationContext as ReaderApplication
        lateinit var model: com.localreader.app.ui.ReaderViewModel
        compose.runOnUiThread { model = androidx.lifecycle.ViewModelProvider(compose.activity)[com.localreader.app.ui.ReaderViewModel::class.java] }
        compose.waitUntil(30000) { !model.state.value.loading }
        val bytes = epub()
        val id = requireNotNull(app.repository.import(ImportSource("literal-web.epub","application/epub+zip",bytes.size.toLong()) { bytes.inputStream() }).bookId)
        try {
            compose.runOnUiThread { model.open(id) }
            compose.waitUntil(30000) {
                val reader = model.state.value.reader
                reader?.contentId == id && model.readerController(reader.generation).state.value.phase in setOf(com.localreader.app.core.ReaderPhase.READY,com.localreader.app.core.ReaderPhase.FAILED)
            }
            val reader = requireNotNull(model.state.value.reader)
            val state = model.readerController(reader.generation).state.value
            assertEquals(state.message,com.localreader.app.core.ReaderPhase.READY,state.phase)
            assertNotNull("the renderer reported a real text position",state.position)
        } finally {
            compose.runOnUiThread { model.closeReader() }
            assertTrue(runBlocking { model.settleProgress() })
            compose.waitUntil(30000) { model.state.value.reader == null }
            app.repository.content(id)?.let(app.repository::delete)
        }
    }

}

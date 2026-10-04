package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.*
import com.localreader.app.formats.epub.*
import com.localreader.app.ui.ReaderSurface
import com.localreader.app.ui.WebReaderAdapter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubPersistenceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val uris=mutableListOf<Uri>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"epub-test-${UUID.randomUUID()}").apply { mkdirs() }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { repository.db.close();root.deleteRecursively();uris.forEach { context.contentResolver.delete(it,null,null) } }
    private fun fixture(bytes: ByteArray,suffix: String="epub"): Uri {
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/epub-${UUID.randomUUID()}.$suffix")
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) };uris.add(uri);return uri
    }
    private fun import(bytes: ByteArray,suffix: String="epub"): String {
        val result=repository.import(fixture(bytes,suffix));assertNotNull(result.status,result.bookId);return requireNotNull(result.bookId)
    }
    private fun xml(body: String)="<?xml version='1.0' encoding='UTF-8'?><html xmlns='http://www.w3.org/1999/xhtml'><head><link rel='stylesheet' href='book.css'/></head><body>$body</body></html>"
    private fun epub(font: ByteArray?=null,algorithm: String?=null,ncx: Boolean=false,nav: Boolean=true): ByteArray {
        val identifier=if(algorithm?.contains("adobe")==true)"urn:uuid:12345678-1234-5678-1234-567812345678" else "\u00a0 urn:test: raw-key \u00a0"
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String,bytes: ByteArray,stored: Boolean=false) {
                val item=ZipEntry(name).apply { time=315532800000L;if(stored) { method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value } }
                zip.putNextEntry(item);zip.write(bytes);zip.closeEntry()
            }
            fun text(name: String,text: String)=entry(name,text.toByteArray())
            entry("mimetype","application/epub+zip".toByteArray(),true)
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="${if(ncx)"2.0" else "3.0"}" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$identifier</dc:identifier><dc:title>原创 EPUB 闭环</dc:title><dc:creator>测试作者</dc:creator><dc:language>zh-CN</dc:language></metadata><manifest><item id="a" href="first.xhtml" media-type="application/xhtml+xml"/><item id="b" href="second.xhtml" media-type="application/xhtml+xml"/><item id="c" href="images.xhtml" media-type="application/xhtml+xml"/><item id="css" href="book.css" media-type="text/css"/><item id="image" href="image.svg" media-type="image/svg+xml" properties="cover-image"/>${if(nav)"<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>" else ""}${if(ncx)"<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>" else ""}${if(font!=null)"<item id='font' href='font.ttf' media-type='font/ttf'/>" else ""}</manifest><spine ${if(ncx)"toc='ncx'" else ""}><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>""")
            text("OPS/first.xhtml",xml("\n    <p id='same'>中文😀 English 123 <em>italic</em>&#160;尾</p>\n    <p>正文第一章。</p><script src='evil'/><p id='after'>自闭合之后的安全正文</p>"))
            text("OPS/second.xhtml",xml("<h1 id='same'>第二章</h1><p class='semantic-font'>语义<span class='reset-font'>默认</span><span class='angled-font'>倾斜</span><span class='priority-font' style='font:italic 700 18px PackageFont!important;font-weight:400'>重要</span></p>"+"<p>可重排正文 English 123 汉字段落😀。</p>".repeat(80)+"<table><tr><td id='cell'>Table cell</td></tr></table>"))
            text("OPS/images.xhtml",xml("<img id='one' src='image.svg'/><img id='two' src='image.svg'/><a href='second.xhtml#same'>章节链接</a>"))
            text("OPS/image.svg","<s:svg xmlns:s='http://www.w3.org/2000/svg' xmlns:l='http://www.w3.org/1999/xlink' viewBox='0 0 480 240'><s:defs><s:rect id='tile' width='480' height='240' fill='#66a'/></s:defs><s:use l:href='#tile'/><s:text x='20' y='100'>LOCAL IMAGE</s:text></s:svg>")
            text("OPS/book.css",(if(font!=null)"@font-face{font-family:PackageFont;src:local('NoExternal'),url('font.ttf')}body{font-family:PackageFont}" else "")+"p{color:rgb(255 0 0);width:calc(100% - 2em);margin:1em 0}em{font-style:italic}.semantic-font{font:italic 700 18px/1.4 PackageFont}.reset-font{font:18px PackageFont}.angled-font{font:oblique 10deg 600 18px/1.4 PackageFont}")
            if(nav)text("OPS/nav.xhtml","<html xmlns='http://www.w3.org/1999/xhtml' xmlns:ops='http://www.idpf.org/2007/ops'><body><nav ops:type='toc'><ol><li><a href='first.xhtml#same'>第一章</a><ol><li><a href='second.xhtml#same'>第二章</a><ol><li><a href='second.xhtml#cell'>表格</a></li></ol></li></ol></li><li><a href='images.xhtml#one'>图片</a></li></ol></nav></body></html>")
            if(ncx)text("OPS/toc.ncx","<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint id='n1'><navLabel><text>NCX第一章</text></navLabel><content src='first.xhtml#same'/><navPoint id='n2'><navLabel><text>NCX第二章</text></navLabel><content src='second.xhtml#same'/></navPoint></navPoint></navMap></ncx>")
            if(font!=null)entry("OPS/font.ttf",if(algorithm!=null)FontObfuscation.decode(font,identifier,algorithm) else font)
            if(algorithm!=null)text("META-INF/encryption.xml","<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='$algorithm'/><e:CipherData><e:CipherReference URI='OPS/font.ttf'/></e:CipherData></e:EncryptedData></encryption>")
        }
        return output.toByteArray().also { bytes ->
            fun word(offset: Int)=(bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
            // Android adds a timestamp extra for pre-DOS dates. EPUB's first
            // mimetype entry must be stored with no local extra field.
            assertEquals(ZipEntry.STORED,word(8));assertEquals(0,word(28));assertEquals(8,word(26))
            assertEquals("mimetype",bytes.copyOfRange(30,38).toString(Charsets.US_ASCII))
        }
    }
    @Test fun safMagicFactoryCanonicalTocAndSqliteRestartAreReal() {
        val bytes=epub();val id=import(bytes,"txt") // PK magic must win over text/plain/name.
        val record=requireNotNull(repository.db.content(id));assertEquals(ContentFormat.EPUB,record.format);assertEquals("原创 EPUB 闭环",record.title);assertEquals("测试作者",record.author);assertNotNull(record.coverPath)
        assertTrue(File(context.filesDir,"library/$id/original.epub").readBytes().contentEquals(bytes))
        val store=SqliteReadingStore(repository.db)
        val opened=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        try {
            assertEquals(listOf(0,1,2,0),opened.toc().map { it.depth });assertTrue(opened.excerpt(opened.locatorForToc("epub-toc:0")!!,100).contains("中文😀"))
            val publication=opened.publication as EpubPublication
            val text=EpubSource(repository.directory(record),repository.db.epubCache(id)).use { it.text(0) }
            assertTrue(text.contains("自闭合之后的安全正文"))
            val first=requireNotNull(publication.locator(RendererAnchor.Text(1,3)))
            val far=EpubReadingExtent(opened.revision,1,100)
            store.savePosition(id,opened.revision,first,far)
            assertTrue(store.addBookmark(id,opened.revision,first,opened.excerpt(first,100)))
            val imageSection=publication.record.sections[2]
            val imageLocators=imageSection.elements.keys.take(2).map { requireNotNull(publication.locator(RendererAnchor.Element(2,it))) }
            assertEquals(2,imageLocators.size);assertEquals(imageLocators[0].offset,imageLocators[1].offset)
            imageLocators.forEach { assertTrue(store.addBookmark(id,opened.revision,it,"图片")) }
            store.patchRules(id,opened.revision,RulesPatch(epubStyleMode=FieldUpdate.Set("CUSTOM"),fontSize=FieldUpdate.Set(22f)))
            assertEquals(id,repository.import(fixture(bytes)).bookId)
        } finally { opened.close() }
        repository.db.close();val reopened=LibraryRepository(context);reopened.recover()
        try { val snapshot=SqliteReadingStore(reopened.db).loadSnapshot(id)
            assertEquals(1,(snapshot.current as Locator.Epub).spine);assertEquals(3,(snapshot.current as Locator.Epub).offset);assertEquals(EpubReadingExtent(record.revision,1,100),snapshot.furthest)
            assertEquals(3,snapshot.bookmarks.size);assertEquals("CUSTOM",snapshot.rules.epubStyleMode);assertEquals(22f,snapshot.rules.fontSize)
        } finally { reopened.db.close() }
    }
    @Test fun packagedIdpfAndAdobeFontsDecodeToExactOriginalBytes() {
        val font=context.assets.open("fonts/Bitter-Regular-VF.ttf").use { it.readBytes() }
        listOf("http://www.idpf.org/2008/embedding","http://ns.adobe.com/pdf/enc#RC").forEach { algorithm ->
            val id=import(epub(font,algorithm));val cache=repository.db.epubCache(id)
            val resource=cache.resources.single { it.mime.startsWith("font/") }
            EpubSource(repository.directory(requireNotNull(repository.db.content(id))),cache).use { source -> assertArrayEquals(font,source.open(resource.file,resource.bytes).use { it.readBytes() }) }
        }
    }
    @Test fun epubTwoNcxAndMissingTocFallbackAreConsumed() {
        val ncx=import(epub(ncx=true,nav=false));val fallback=import(epub(nav=false))
        runBlocking { LibraryOpenedBookFactory(repository).open(ncx) }.use { book -> assertEquals(listOf("NCX第一章","NCX第二章"),book.toc().map { it.title });assertEquals(1,(book.locatorForToc(book.toc()[1].id) as Locator.Epub).spine) }
        runBlocking { LibraryOpenedBookFactory(repository).open(fallback) }.use { book -> assertEquals(3,book.toc().size);assertNotNull(book.locatorForToc(book.toc()[2].id)) }
    }
    private fun waitUntil(timeout: Long=16000,predicate: () -> Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(!predicate()) { if(System.currentTimeMillis()>end)throw AssertionError("EPUB runtime condition timed out");Thread.sleep(30) }
    }
    @Test fun protocolTwoRuntimeAuthorCustomReflowElementAndQueuedPageAck() {
        val id=import(epub(context.assets.open("fonts/Bitter-Regular-VF.ttf").use { it.readBytes() }));val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val position=AtomicReference<RendererEvent.Position?>();val positionCount=java.util.concurrent.atomic.AtomicInteger();val error=AtomicReference<String?>();val notice=AtomicReference<String?>();var badFontBook: OpenedBook?=null;lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(script: String): String { val value=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript(script) { value.set(it) } };waitUntil { value.get()!=null };return requireNotNull(value.get()) }
            fun diagnostics()=JSONObject(org.json.JSONTokener(evaluate("JSON.stringify(readerHost.diagnostics())")).nextValue() as String)
            fun assertSemanticFont() {
                val probe="(()=>{const d=document.getElementById('renderer').getContents().find(x=>x.index===1).doc;const s=c=>getComputedStyle(d.querySelector(c));return s('.semantic-font').fontStyle==='italic'&&s('.semantic-font').fontWeight==='700'&&s('.reset-font').fontStyle==='normal'&&s('.reset-font').fontWeight==='400'&&s('.angled-font').fontStyle.startsWith('oblique')&&s('.angled-font').fontWeight==='600'&&s('.priority-font').fontStyle==='italic'&&s('.priority-font').fontWeight==='700'})()"
                assertEquals("true",evaluate(probe))
            }
            try {
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) {
                    is RendererEvent.Position -> { position.set(event);positionCount.incrementAndGet() }
                    is RendererEvent.Notice -> notice.set(event.message)
                    is RendererEvent.Error -> error.set(event.message)
                    is RendererEvent.Crashed -> error.set(event.message)
                    else -> Unit
                } };activity.setContentView(adapter.view);adapter.open(book.publication,Locator.Epub(book.revision,0,0),ReaderRules(flow=ReaderFlow.SCROLLED)) }
                waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue((position.get()!!.locator as Locator.Epub).offset>0)
                assertEquals("\"rgb(255, 0, 0)\"",evaluate("getComputedStyle(document.getElementById('renderer').getContents().find(x=>x.index===0).doc.querySelector('p')).color"))
                val target=Locator.Epub(book.revision,1,3)
                position.set(null);scenario.onActivity { adapter.goTo(target) };waitUntil { (position.get()?.locator as? Locator.Epub)?.spine==1 || error.get()!=null };assertNull(error.get());assertEquals(3,(position.get()!!.locator as Locator.Epub).offset);assertSemanticFont()
                val epoch=diagnostics().getInt("layoutEpoch")
                val beforePages=positionCount.get();position.set(null);scenario.onActivity { adapter.next();adapter.next();adapter.prev() };waitUntil { positionCount.get()>=beforePages+3 && diagnostics().getInt("commandId")>=5 || error.get()!=null };assertNull(error.get());assertEquals(epoch,diagnostics().getInt("layoutEpoch"));assertEquals(IntentCause.UserNavigation,position.get()!!.intent);assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diagnostics().getJSONObject("anchorGeometry").getDouble("width")>0.1)
                position.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=24f,flow=ReaderFlow.PAGINATED),target) };waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertEquals(IntentCause.Reflow,position.get()!!.intent)
                val oldUrl=adapter.currentUrl
                position.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=24f,flow=ReaderFlow.PAGINATED,epubStyleMode="CUSTOM"),target) };waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertNotEquals(oldUrl,adapter.currentUrl);assertSemanticFont()
                assertEquals("\"rgb(39, 44, 40)\"",evaluate("getComputedStyle(document.getElementById('renderer').getContents().find(x=>x.index===1).doc.querySelector('p')).color"))
                val image=(book.publication as EpubPublication).record.sections[2].elements.keys.first()
                position.set(null);scenario.onActivity { adapter.goTo(Locator.Epub(book.revision,2,0,elementId=image)) };waitUntil { (position.get()?.locator as? Locator.Epub)?.elementId==image || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"))
                assertTrue(adapter.cachedResourceBytes<=4*1024*1024)
                val badId=import(epub(byteArrayOf(0,1,0,0,0,0,0,0,0,0,0,0)))
                val bad=runBlocking { LibraryOpenedBookFactory(repository).open(badId) };badFontBook=bad
                position.set(null);notice.set(null)
                scenario.onActivity { adapter.open(bad.publication,Locator.Epub(bad.revision,0,0),ReaderRules()) }
                waitUntil { position.get()!=null && notice.get()?.contains("备用字体")==true || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"))
            } finally { scenario.onActivity { adapter.close() };badFontBook?.close();book.close() }
        }
    }
    @Test fun physicalTwentyMiBImportStreamsCanonicalPartsAndRestoresLogicalPositionAfterReflow() {
        data class Expected(val file: File,val characters: Int,val sha: String,val target: Int)
        fun chapter(name: String,minimum: Int,giant: Boolean): Expected {
            val file=File(root,name);var characters=0;var bytes=0;var selected=-1;var random=17L
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            file.outputStream().buffered().use { output ->
                fun write(value: String) { val encoded=value.toByteArray();output.write(encoded);bytes+=encoded.size }
                fun text(value: String) { write(value);characters+=value.length;digest.update(value.toByteArray(Charsets.UTF_16LE)) }
                write("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>原创长章</title></head><body>")
                if(giant)write("<p id='start'>")
                var paragraph=0
                while(bytes<minimum) {
                    if(!giant)write("<p${if(paragraph%100==0)" id='p$paragraph'" else ""}>")
                    if(selected<0 && bytes>minimum*3/4)selected=characters
                    val value=buildString { repeat(512) { random=(random*1664525+1013904223) and 0xffffffffL;append((0x4e00+(random%20000).toInt()).toChar());if(it%127==126)append("😀") } }
                    text(value)
                    if(!giant)write("</p>")
                    paragraph++
                }
                if(giant)write("</p>")
                write("<p id='tail'>");text("真实章节末尾 END");write("</p></body></html>")
            }
            return Expected(file,characters,digest.digest().joinToString("") { "%02x".format(it) },selected)
        }
        val first=chapter("giant.xhtml",5*1024*1024,true);val second=chapter("many.xhtml",15*1024*1024,false)
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/stream-${UUID.randomUUID()}.epub");uris.add(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { stream -> ZipOutputStream(stream).use { zip ->
            fun entry(name: String,file: File) {
                val crc=CRC32();file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;crc.update(buffer,0,n) } }
                val item=ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=file.length();compressedSize=size;this.crc=crc.value }
                zip.putNextEntry(item);file.inputStream().use { it.copyTo(zip,65536) };zip.closeEntry()
            }
            fun text(name: String,value: String) { val file=File(root,"entry-${UUID.randomUUID()}");file.writeText(value);entry(name,file);file.delete() }
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("book.opf","<package version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>own-long-20mib</dc:identifier><dc:title>真实20MiB EPUB</dc:title></metadata><manifest><item id='one' href='giant.xhtml' media-type='application/xhtml+xml'/><item id='two' href='many.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>")
            entry("giant.xhtml",first.file);entry("many.xhtml",second.file)
        } }
        val imported=repository.import(uri);assertNotNull(imported.status,imported.bookId);val id=requireNotNull(imported.bookId)
        val record=requireNotNull(repository.db.content(id));assertTrue(record.bytes>20L*1024*1024)
        val cache=repository.db.epubCache(id);assertEquals(2,cache.sections.size);assertTrue(cache.parts.size>40);assertTrue(cache.parts.all { it.characters<512*1024 })
        val directory=repository.directory(record)
        listOf(first,second).forEachIndexed { spine,expected ->
            assertEquals(expected.characters,cache.sections[spine].characters)
            val hash=java.security.MessageDigest.getInstance("SHA-256");File(directory,cache.sections[spine].canonicalFile).inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n) } }
            assertEquals(expected.sha,hash.digest().joinToString("") { "%02x".format(it) })
        }
        val store=SqliteReadingStore(repository.db);val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val target=Locator.Epub(book.revision,1,second.target);assertTrue(book.accepts(target));assertTrue(book.publication.target(target).section>1)
        val current=AtomicReference<RendererEvent.Position?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun diagnostics(): JSONObject {
                val result=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript("JSON.stringify(readerHost.diagnostics())") { result.set(it) } };waitUntil { result.get()!=null }
                return JSONObject(org.json.JSONTokener(result.get()).nextValue() as String)
            }
            try {
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) { is RendererEvent.Position -> current.set(event);is RendererEvent.Error -> error.set(event.message);is RendererEvent.Crashed -> error.set(event.message);else -> Unit } };activity.setContentView(adapter.view);adapter.open(book.publication,target,ReaderRules(flow=ReaderFlow.SCROLLED)) }
                waitUntil { current.get()?.locator==target || (current.get()?.locator as? Locator.Epub)?.let { it.spine==target.spine && it.offset==target.offset }==true || error.get()!=null };assertNull(error.get())
                var diag=diagnostics();assertTrue(diag.getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diag.getJSONArray("contents").length()<=8);val loaded=diag.getJSONArray("contents");assertTrue((0 until loaded.length()).sumOf { loaded.getJSONObject(it).optString("canonicalLength").toLongOrNull() ?: 0L }<first.characters.toLong()+second.characters);assertTrue(adapter.cachedResourceBytes<=4*1024*1024)
                current.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=26f,flow=ReaderFlow.PAGINATED),target) }
                waitUntil { (current.get()?.locator as? Locator.Epub)?.let { it.spine==target.spine && it.offset==target.offset }==true || error.get()!=null };assertNull(error.get())
                diag=diagnostics();assertTrue(diag.getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diag.getJSONArray("contents").length()<=8)
                val saved=current.get()!!.locator;store.savePosition(id,book.revision,saved,EpubReadingExtent(book.revision,1,second.target));assertTrue(store.addBookmark(id,book.revision,saved,book.excerpt(saved,100)))
            } finally { scenario.onActivity { adapter.close() };book.close() }
        }
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { reopened -> val snapshot=store.loadSnapshot(id);val locator=snapshot.current as Locator.Epub;assertEquals(1,locator.spine);assertEquals(second.target,locator.offset);assertEquals(locator,store.bookmarks(id).single().locator);assertTrue(reopened.accepts(locator)) }
    }

    @Test fun factoryAtomicallyRefreshesSchemaTwoDerivedStylesWithoutChangingStoredSourceOrLocators() {
        val imported=repository.import(fixture(epub()));val id=requireNotNull(imported.bookId)
        val content=requireNotNull(repository.db.content(id));val directory=repository.directory(content)
        val metadata=File(directory,"package.json");val json=JSONObject(metadata.readText())
        json.put("renderRevision",content.revision+"-parts2-style3");metadata.writeText(json.toString())
        val original=File(directory.parentFile!!.parentFile,"original.epub")
        val old=EpubCache.read(directory);val originalHash=original.inputStream().use { input ->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) };digest.digest().toList()
        }
        val canonical=old.sections.associate { it.canonicalFile to File(directory,it.canonicalFile).readBytes().toList() }
        val store=SqliteReadingStore(repository.db);val position=Locator.Epub(content.revision,1,3)
        store.savePosition(id,content.revision,position,EpubReadingExtent(content.revision,1,3));assertTrue(store.addBookmark(id,content.revision,position,"source"))
        val before=store.loadSnapshot(id)
        // An interrupted rebuild must leave the complete old cache and reading metadata usable.
        var preparedFailure=false
        val fallback=EpubCache.refresh(original,directory,content.hash) {
            if(directory.listFiles()?.any { it.name.startsWith(".derived-staging-") && File(it,"package.json").isFile }==true) {
                preparedFailure=true;error("injected refresh failure before publish")
            }
        }
        assertTrue(preparedFailure);assertFalse(directory.listFiles()!!.any { it.name.startsWith(".derived-staging-") })
        assertEquals(directory,fallback.first);assertEquals(old.renderRevision,fallback.second.renderRevision)
        assertTrue(fallback.second.warnings.any { it.contains("刷新失败") });assertEquals(directory,EpubCache.activeDirectory(directory))
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { opened ->
            assertEquals(content.revision,opened.revision);assertTrue(opened.accepts(position))
            assertEquals(content.revision+"-"+EpubCache.RENDER_ALGORITHM,opened.publication.revision)
            assertNotEquals(directory,EpubCache.activeDirectory(directory))
        }
        assertEquals(before,store.loadSnapshot(id));assertEquals(position,store.bookmarks(id).single().locator)
        assertEquals(old.renderRevision,JSONObject(metadata.readText()).getString("renderRevision"))
        canonical.forEach { (name,bytes) -> assertEquals(bytes,File(directory,name).readBytes().toList());assertEquals(bytes,File(EpubCache.activeDirectory(directory),name).readBytes().toList()) }
        val afterHash=original.inputStream().use { input ->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) };digest.digest().toList()
        }
        assertEquals(originalHash,afterHash)
    }

    @Test fun controllerKeepsSurfaceAcrossRealRendererTerminationAndSourceGlyphRestore() {
        val id=import(epub());val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val store=SqliteReadingStore(repository.db)
        val owner=ReadingSessionOwner(book,1,store,store.loadSnapshot(id));val events=java.util.concurrent.CopyOnWriteArrayList<RendererEvent>();lateinit var surface: ReaderSurface
        val controller=ReaderController(1,{ owner.bind(it) },{ event -> events.add(event);if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,System.currentTimeMillis()) else true })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity -> surface=ReaderSurface(activity);surface.bind(controller);activity.setContentView(surface);surface.present(owner.state()) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(ReaderPhase.READY,controller.state.value.phase)
                lateinit var old: WebView
                scenario.onActivity { old=surface.getChildAt(0) as WebView;assertNotNull(old.webViewRenderProcess);assertTrue(old.webViewRenderProcess!!.terminate()) }
                waitUntil { events.any { it is RendererEvent.Crashed } }
                val stable=surface
                scenario.onActivity { surface.present(owner.state(),1) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(ReaderPhase.READY,controller.state.value.phase)
                scenario.onActivity { assertSame(stable,surface);assertNotSame(old,surface.getChildAt(0));assertNull(old.parent) }
                assertTrue(book.accepts(controller.state.value.position!!));owner.flush()
            } finally { scenario.onActivity { surface.close();owner.revokeRenderers();owner.closeSource() } }
        }
    }
}
